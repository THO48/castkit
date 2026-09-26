#!/usr/bin/env bash
# 获取 receiver 原生依赖源码（4 个上游 submodule 的锁定 commit + OpenSSL 源码）。
#
# !! 这些源码必须放在「可执行文件系统」上（默认 /root/castkit）：OpenSSL 与 FFmpeg
#    都是 in-source 构建，会生成并执行 configure 脚本，而 /sdcard 是 noexec 挂载。
#    见 tools/build-tree.sh。
#
# 本机（容器内）无法访问 github.com / raw.githubusercontent.com / openssl.org /
# mirror.viaduck.org，因此统一改走：
#   - gitclone.com  ：GitHub git 协议代理，支持按任意 commit sha 浅取
#   - gitcode.com   ：GitHub 仓库镜像（保留完整 commit 历史，blob 按需拉取）
#
# 结果目录不走 submodule，而是普通目录（见 receiver/.gitignore），
# 便于离线复现：bash tools/fetch-sources.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TREE="${CASTKIT_TREE:-/root/castkit}"
TP="$TREE/receiver/app/src/main/cpp/third_party"
mkdir -p "$TP"

PROXY="https://gitclone.com/github.com"
MIRROR="https://gitcode.com/gh_mirrors"

UXPLAY_COMMIT="942d7b2"   # UxPlay：上游会 rebase，fork 锁定的 4621533 在任何可达镜像中都不存在，
                          # 改用 gitcode/atomgit 镜像里最新的稳定 commit（2026-09-16）。
                          # fork 自带的 6 个补丁见 app/src/main/cpp/patches/UxPlay/，按需手工移植。
UXPLAY_MIRROR="ux/uxplay"
OSSLCMAKE_SHA="4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8" # viaduck/openssl-cmake
LIBPLIST_SHA="f41b1ea67045e0c09339974d83e389972d84f166"  # libimobiledevice/libplist
FFMPEG_SHA="38b88335f99e76ed89ff3c93f877fdefce736c13"    # FFmpeg/FFmpeg
OPENSSL_TAG="openssl-3.4.4"

log() { printf '[fetch] %s\n' "$*"; }

# 通过 gitclone 代理按 sha 浅取单个 commit（代理偶发拒绝，重试若干次）
fetch_sha_proxy() {
  local dir="$1" repo="$2" sha="$3" i
  if [ -f "$dir/.fetched" ]; then log "已存在: $dir"; return 0; fi
  log "gitclone 取 $repo@${sha:0:8} -> $dir"
  rm -rf "$dir"; mkdir -p "$dir"
  git -C "$dir" init -q
  git -C "$dir" remote add origin "$PROXY/$repo.git"
  for i in 1 2 3 4 5 6 7 8; do
    if git -C "$dir" fetch -q --depth 1 origin "$sha" 2>/dev/null; then break; fi
    log "  第 $i 次失败，重试…"; sleep 4
  done
  git -C "$dir" checkout -q FETCH_HEAD
  rm -rf "$dir/.git"
  date > "$dir/.fetched"
}

# 通过 gitcode 镜像（完整历史，blob 按需）检出指定 commit
fetch_sha_mirror() {
  local dir="$1" mirror_path="$2" sha="$3"
  if [ -f "$dir/.fetched" ]; then log "已存在: $dir"; return 0; fi
  log "gitcode 镜像取 $mirror_path@${sha:0:8} -> $dir"
  rm -rf "$dir"
  git clone -q --filter=blob:none --no-checkout "$MIRROR/$mirror_path.git" "$dir"
  git -C "$dir" checkout -q "$sha"
  rm -rf "$dir/.git"
  date > "$dir/.fetched"
}

# 通过 gitcode 镜像完整克隆并检出指定 commit（UxPlay 体积小，直接全量最稳）
fetch_mirror_commit() {
  local dir="$1" mirror_path="$2" commit="$3"
  if [ -f "$dir/.fetched" ]; then log "已存在: $dir"; return 0; fi
  log "gitcode 镜像取 $mirror_path@$commit -> $dir"
  rm -rf "$dir"
  git clone -q "$MIRROR/$mirror_path.git" "$dir"
  git -C "$dir" checkout -q "$commit"
  rm -rf "$dir/.git"
  date > "$dir/.fetched"
}

fetch_sha_proxy     "$TP/openssl-cmake"  "viaduck/openssl-cmake"       "$OSSLCMAKE_SHA"
fetch_sha_mirror    "$TP/libplist"       "li/libplist"                 "$LIBPLIST_SHA"
fetch_sha_mirror    "$TP/ffmpeg"         "ff/FFmpeg"                   "$FFMPEG_SHA"
fetch_mirror_commit "$TP/UxPlay"         "$UXPLAY_MIRROR"             "$UXPLAY_COMMIT"

# OpenSSL 源码（openssl-cmake 默认从 mirror.viaduck.org 下载，本机不可达）
if [ ! -f "$TP/openssl-src/Configure" ]; then
  log "gitclone 取 openssl $OPENSSL_TAG -> $TP/openssl-src"
  rm -rf "$TP/openssl-src"
  git clone -q --depth 1 --branch "$OPENSSL_TAG" "$PROXY/openssl/openssl.git" "$TP/openssl-src"
  rm -rf "$TP/openssl-src/.git"
  date > "$TP/openssl-src/.fetched"
else
  log "已存在: $TP/openssl-src"
fi

log "完成。校验关键文件："
for f in "$TP/UxPlay/lib/raop_rtp_mirror.c" \
         "$TP/openssl-cmake/cmake/BuildOpenSSL.cmake" \
         "$TP/libplist/src/plist.c" \
         "$TP/ffmpeg/configure" \
         "$TP/openssl-src/Configure"; do
  [ -f "$f" ] && echo "  OK  $f" || { echo "  MISSING $f"; exit 1; }
done

# /sdcard 是 noexec 挂载且不保留执行位：如果这些源码是从工作区拷过来的，
# FFmpeg 的 ./configure、OpenSSL 的 ./Configure 会丢掉 +x，导致构建时报 Permission denied。
log "补齐脚本可执行位…"
find "$TP" -maxdepth 4 -type f \( \
      -name 'configure' -o -name 'Configure' -o -name 'config' -o \
      -name '*.sh' -o -name '*.pl' -o -name 'install-sh' -o -name 'config.sub' -o -name 'config.guess' \
    \) -exec chmod +x {} + 2>/dev/null || true
chmod +x "$TP/ffmpeg/configure" "$TP/openssl-src/Configure" 2>/dev/null || true
echo "  configure: $(test -x "$TP/ffmpeg/configure" && echo 可执行 || echo 不可执行)"
