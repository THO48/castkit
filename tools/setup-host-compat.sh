#!/usr/bin/env bash
# 让整套 Android 构建链在「aarch64 宿主机」上可用。
#
# 背景（已实测）：Android SDK 的 build-tools/aapt2、NDK 的 clang/llvm-* 都只有
# linux x86_64 版本；NDK 也没有 Linux-aarch64 发行版（SDK 仓库里 NDK 的 aarch64
# 归档只有 darwin-aarch64，即 macOS Apple Silicon）。本机是 aarch64 + Ubuntu 24.04。
#
# 解决办法（三个各自独立的替换）：
#   1) aapt2 / zipalign / aapt：只有 x86_64 二进制 -> 装 amd64 运行库，
#      用 qemu-user-static 模拟运行（aapt2 走 AGP 的 aapt2FromMavenOverride）。
#   2) NDK 的宿主机工具：换成本机 aarch64 clang/lld/llvm-*（sysroot/目标库原样保留）。
#   3) CMake/Ninja：SDK 里的是 x86_64 -> 用 apt 的系统版本（local.properties 里 cmake.dir=/usr）。
#
# 依赖：apt-get install clang lld llvm ninja-build cmake qemu-user-static zstd
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_PATH="${NDK_PATH:-/opt/android-sdk/ndk/27.0.12077973}"
BUILD_TOOLS="${BUILD_TOOLS:-/opt/android-sdk/build-tools/36.0.0}"
SYSROOT=/opt/amd64-sysroot
DEBDIR=/opt/amd64-debs
HOSTBIN=/opt/castkit-host/bin
MIRROR=http://mirrors.tuna.tsinghua.edu.cn/ubuntu

log() { printf '[host-compat] %s\n' "$*"; }

# ---------- 1) amd64 运行库 + qemu ----------
if [ ! -x /usr/bin/qemu-x86_64-static ]; then
  log "安装 qemu-user-static / zstd"
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends qemu-user-static zstd
fi

if [ ! -e "$SYSROOT/lib64/ld-linux-x86-64.so.2" ]; then
  log "下载 amd64 运行库到 $SYSROOT"
  mkdir -p "$DEBDIR" "$SYSROOT"
  python3 - "$MIRROR" "$DEBDIR" "$SYSROOT" <<'PY'
import gzip, io, os, re, subprocess, sys, tarfile, urllib.request

mirror, debdir, sysroot = sys.argv[1], sys.argv[2], sys.argv[3]
WANT = {"libc6", "libgcc-s1", "libstdc++6", "zlib1g"}

def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()

found = {}
for suite in ("noble", "noble-updates", "noble-security"):
    try:
        data = gzip.decompress(fetch(f"{mirror}/dists/{suite}/main/binary-amd64/Packages.gz"))
    except Exception as e:
        print("skip", suite, e); continue
    for block in data.decode("utf-8", "ignore").split("\n\n"):
        m = re.search(r"^Package: (\S+)", block, re.M)
        if not m or m.group(1) not in WANT:
            continue
        fn = re.search(r"^Filename: (\S+)", block, re.M)
        if fn:
            found.setdefault(m.group(1), fn.group(1))

def ar_member(path, prefix="data.tar"):
    with open(path, "rb") as f:
        assert f.read(8) == b"!<arch>\n"
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                return None, None
            name = hdr[0:16].decode().strip().rstrip("/")
            size = int(hdr[48:58].decode().strip())
            data = f.read(size)
            if size % 2:
                f.read(1)
            if name.startswith(prefix):
                return name, data

for pkg, fn in found.items():
    dest = os.path.join(debdir, os.path.basename(fn))
    if not os.path.exists(dest):
        print("下载", pkg)
        with open(dest, "wb") as f:
            f.write(fetch(f"{mirror}/{fn}"))
    name, data = ar_member(dest)
    if name.endswith(".zst"):
        data = subprocess.run(["zstd", "-d", "-c"], input=data, capture_output=True, check=True).stdout
    tf = tarfile.open(fileobj=io.BytesIO(data))
    for m in tf.getmembers():
        target = os.path.join(sysroot, m.name.lstrip("./"))
        if m.isdir():
            os.makedirs(target, exist_ok=True)
        elif m.isfile():
            os.makedirs(os.path.dirname(target), exist_ok=True)
            src = tf.extractfile(m)
            if src:
                with open(target, "wb") as out:
                    out.write(src.read())
        elif m.issym():
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if not os.path.lexists(target):
                os.symlink(m.linkname, target)

# Debian/Ubuntu 是 merged-usr，但解释器路径写的是 /lib64/...
for link, target in (("lib", "usr/lib"), ("lib64", "usr/lib64")):
    p = os.path.join(sysroot, link)
    if not os.path.lexists(p):
        os.symlink(target, p)
print("amd64 运行库就绪:", sysroot)
PY
else
  log "amd64 运行库已存在"
fi

# ---------- 2) aapt2 / zipalign / aapt 包装 ----------
mkdir -p "$HOSTBIN"
cat > "$HOSTBIN/aapt2" <<EOF
#!/bin/sh
# CastKit: AGP 需要的 aapt2 只有 linux x86_64 版本，用 qemu 用户态模拟运行
exec /usr/bin/qemu-x86_64-static -L $SYSROOT $BUILD_TOOLS/aapt2 "\$@"
EOF
chmod +x "$HOSTBIN/aapt2"

for t in zipalign aapt; do
  real="$BUILD_TOOLS/$t"
  [ -f "$real" ] || continue
  if [ ! -f "$real.x86_64.orig" ]; then
    mv "$real" "$real.x86_64.orig"
    cat > "$real" <<EOF
#!/bin/sh
exec /usr/bin/qemu-x86_64-static -L $SYSROOT $BUILD_TOOLS/$t.x86_64.orig "\$@"
EOF
    chmod +x "$real"
    log "已包装 build-tools/$t（原文件 -> $t.x86_64.orig）"
  fi
done
# apksigner 是 shell 脚本，不要包装
if [ -f "$BUILD_TOOLS/apksigner.x86_64.orig" ]; then
  mv -f "$BUILD_TOOLS/apksigner.x86_64.orig" "$BUILD_TOOLS/apksigner"
  log "还原 apksigner（脚本，非 ELF）"
fi

log "aapt2 自检: $("$HOSTBIN/aapt2" version 2>&1 | head -1)"

# ---------- 3) NDK 宿主机工具包装 ----------
bash "$ROOT/tools/setup-ndk-host-wrappers.sh" "$NDK_PATH"

log "完成。接下来：bash tools/build-receiver.sh"
