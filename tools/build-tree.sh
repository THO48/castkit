#!/usr/bin/env bash
# CastKit: /sdcard 是 noexec 挂载 —— 构建过程中生成的脚本（CMake 的 prefab_command、
# OpenSSL/FFmpeg 的 in-source configure 等）在那里无法执行，所以真正的构建树放在
# $CASTKIT_TREE（默认 /root/castkit，位于可执行文件系统）。
#
# 本脚本把工作区源码单向同步到构建树；构建树里的 third_party（原生依赖）与 build
# 产物不受影响。
#
# 注意：这里用 tar 而不是 rsync —— 本机文件系统层会让 rsync 建目录失败（EROFS），
# 而 tar/cp -a 正常。
set -euo pipefail

export CASTKIT_TREE="${CASTKIT_TREE:-/root/castkit}"

sync_project() { # $1 = receiver|sender
  local name="$1"
  local src_root dst_root
  src_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  dst_root="$CASTKIT_TREE"

  mkdir -p "$dst_root/$name"
  (
    cd "$src_root/$name"
    tar cf - \
      --exclude=./build \
      --exclude=./app/build \
      --exclude=./.gradle \
      --exclude=./.cxx \
      --exclude=./app/.cxx \
      --exclude=./local.properties \
      --exclude=./app/src/main/cpp/third_party \
      . 2>/dev/null | (cd "$dst_root/$name" && tar xf - 2>/dev/null) || true
  )
  echo "[build-tree] 已同步 $name -> $dst_root/$name"
}

"$@"
