#!/usr/bin/env bash
# 让「只提供 x86_64 宿主机二进制」的 Android NDK 在本机 aarch64 上可用。
#
# 背景：本机是 aarch64（Ubuntu 24.04 arm64），而 Android SDK 的 build-tools/aapt2、
# NDK 的 clang/llvm-* 都只提供 linux x86_64 版本；NDK 也没有 Linux-aarch64 发行版
# （SDK 仓库里 NDK 的 aarch64 归档只有 darwin-aarch64，即 macOS Apple Silicon）。
#
# 做法：NDK 的「sysroot / 目标库 / CMake 工具链脚本」都是与宿主架构无关的资源，
# 只有 <prebuilt>/bin 下的工具是 x86_64 二进制。这里把那批二进制换成指向本机
# aarch64 工具链（apt 的 clang-18/lld-18/llvm-18）的包装脚本，其余目录原样保留。
#
# 用法: bash tools/setup-ndk-host-wrappers.sh [ndk 路径]
set -euo pipefail

NDK_PATH="${1:-/opt/android-sdk/ndk/27.0.12077973}"
PRE="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
BIN="$PRE/bin"
ORIG="$PRE/bin.x86_64.orig"
CLANG_BIN=/usr/bin/clang-18
CLANGXX_BIN=/usr/bin/clang++-18

[ -x "$CLANG_BIN" ] || { echo "缺少 $CLANG_BIN，请先 apt-get install clang lld llvm" >&2; exit 1; }
[ -d "$PRE" ] || { echo "找不到 NDK 预编译目录: $PRE" >&2; exit 1; }

# 只做一次备份
if [ ! -d "$ORIG" ]; then
  mv "$BIN" "$ORIG"
  echo "[ndk] 原始宿主机工具已备份到 $ORIG"
fi
mkdir -p "$BIN"

write_clang_wrapper() {
  local name="$1"
  local compiler="$CLANG_BIN"
  case "$name" in
    *clang++*) compiler="$CLANGXX_BIN" ;;
  esac

  # 从调用名里解析 target triple（例如 aarch64-linux-android24-clang）
  local target_expr=""
  case "$name" in
    *-clang|*-clang++) target_expr="--target=" ;;
  esac

  cat > "$BIN/$name" <<EOF
#!/bin/sh
# CastKit: 用本机 aarch64 clang 冒充 NDK 宿主 clang（原二进制为 x86_64）
name=\$(basename "\$0")
compiler="$compiler"
target=""
case "\$name" in
  *-clang|*-clang++)
    trip=\${name%-clang}
    trip=\${trip%-clang++}
    target="--target=\$trip"
    ;;
esac
# -resource-dir 指向 NDK 自带的 clang 资源目录：里面有 Android 版的
# libclang_rt.builtins-aarch64-android.a 与 libatomic.a（系统 clang 的资源目录没有）。
# -rtlib=compiler-rt / -unwindlib=libunwind：Ubuntu 的 clang 默认找 libgcc，
# 而 NDK 里没有 libgcc（NDK 的 clang 默认就是 compiler-rt）。
exec "\$compiler" \$target \\
  --sysroot="$PRE/sysroot" \\
  -resource-dir "$PRE/lib/clang/18" \\
  -rtlib=compiler-rt \\
  -unwindlib=libunwind \\
  "\$@"
EOF
  chmod +x "$BIN/$name"
}

linked=0
skipped=0
for f in "$ORIG"/*; do
  name="$(basename "$f")"
  case "$name" in
    clang|clang++|clang-18|*-clang|*-clang++)
      write_clang_wrapper "$name"
      ;;
    llvm-*|ld.lld|lld|lld-link|ld64.lld)
      if [ -x "/usr/bin/$name" ]; then
        ln -sf "/usr/bin/$name" "$BIN/$name"
        linked=$((linked + 1))
      else
        skipped=$((skipped + 1))
      fi
      ;;
    *.py)
      cp "$f" "$BIN/$name"
      ;;
    *)
      skipped=$((skipped + 1))
      ;;
  esac
done
# clang-18 版本号文件（NDK 里 clang 是 clang-18 的软链）也补上
[ -e "$BIN/clang-18" ] || write_clang_wrapper clang-18

echo "[ndk] clang 包装脚本已生成；llvm 工具软链 $linked 个；跳过（构建不需要）$skipped 个"
echo "[ndk] 自检:"
"$BIN/clang" --version 2>&1 | head -1
printf 'int main(){return 0;}' > /tmp/ndk_probe.c
if "$BIN/aarch64-linux-android24-clang" --sysroot="$PRE/sysroot" -c /tmp/ndk_probe.c -o /tmp/ndk_probe.o 2>/tmp/ndk_probe.err; then
  echo "[ndk] aarch64 目标编译自检通过"
else
  echo "[ndk] 自检失败：" >&2; head -5 /tmp/ndk_probe.err >&2; exit 1
fi
