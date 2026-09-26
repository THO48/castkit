#!/usr/bin/env bash
# 预构建接收端原生依赖：OpenSSL 3.4.4 + 最小 FFmpeg（仅 ALAC 解码）。
#
# 为什么不用上游的 ExternalProject（openssl-cmake / BuildFFmpeg）：
# 本机（aarch64 容器）里 ninja → python 包装器 → bash → make 的嵌套调用会触发
# GNU make jobserver 死锁（make 永远阻塞在 jobserver 管道上，无子进程），
# 而同样的 make 由普通 shell 直接启动即可在 30 秒内跑完。
# 因此这里以普通 shell 预构建，再由 app/src/main/cpp/CMakeLists.txt 通过
# -DCASTKIT_DEPS_ROOT=<root> 使用（目录结构 <root>/<abi>/{openssl,ffmpeg}）。
#
# 用法: bash tools/build-native-deps.sh [abi]        # 默认 arm64-v8a
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TREE="${CASTKIT_TREE:-/root/castkit}"
ABI="${1:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-24}"
JOBS="${JOBS:-6}"

TP="$TREE/receiver/app/src/main/cpp/third_party"
NDK="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/27.0.12077973}"
NDK_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
NDK_SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
DEPS="$TREE/native-deps/$ABI"

case "$ABI" in
  arm64-v8a)   OPENSSL_TARGET=android-arm64 ;;
  armeabi-v7a) OPENSSL_TARGET=android-arm ;;
  x86_64)      OPENSSL_TARGET=android-x86_64 ;;
  *) echo "不支持的 ABI: $ABI" >&2; exit 1 ;;
esac

log() { printf '[native-deps] %s\n' "$*"; }

[ -x "$NDK_BIN/clang" ] || { echo "NDK 宿主工具未就绪，请先跑 tools/setup-host-compat.sh" >&2; exit 1; }
[ -d "$TP/openssl-src" ] || { echo "缺少 $TP/openssl-src，请先跑 tools/fetch-sources.sh" >&2; exit 1; }

export ANDROID_NDK_ROOT="$NDK"
export PATH="$NDK_BIN:$PATH"
export LC_ALL=C

mkdir -p "$DEPS"

# ---------------- OpenSSL ----------------
# 快路径：如果 third_party/openssl-src 里已经有上一次 in-source 构建的静态库，直接复用
if [ ! -f "$DEPS/openssl/lib/libcrypto.a" ] && \
   [ -f "$TP/openssl-src/libcrypto.a" ] && [ -f "$TP/openssl-src/libssl.a" ] && \
   [ -d "$TP/openssl-src/include/openssl" ]; then
  log "复用 third_party/openssl-src 里已构建的 OpenSSL 产物"
  mkdir -p "$DEPS/openssl/lib" "$DEPS/openssl/include"
  cp -f "$TP/openssl-src/libcrypto.a" "$TP/openssl-src/libssl.a" "$DEPS/openssl/lib/"
  cp -a "$TP/openssl-src/include/openssl" "$DEPS/openssl/include/"
fi

if [ ! -f "$DEPS/openssl/lib/libcrypto.a" ]; then
  log "构建 OpenSSL 3.4.4 ($ABI, API $API_LEVEL) -> $DEPS/openssl"
  SRC="$TP/openssl-src"
  ( cd "$SRC" && make clean >/dev/null 2>&1 || true )
  rm -f "$SRC/configdata.pm" "$SRC/Makefile"
  # 注意：Configure 的每个选项都必须是独立的 argv。
  # 早先这里把整串选项塞进一对引号当成单个参数传进去，OpenSSL 会报
  #   ***** Unsupported options: no-cast no-md2 ...
  #   Failure! build file wasn't produced.
  # 之前被上面的“快路径”（复用 third_party/openssl-src 里的旧静态库）掩盖了。
  ( cd "$SRC" && ./Configure "$OPENSSL_TARGET" \
      "-D__ANDROID_API__=$API_LEVEL" \
      --libdir=lib \
      --prefix="$DEPS/openssl" \
      no-cast no-md2 no-md4 no-mdc2 no-rc4 no-rc5 no-engine no-idea no-camellia no-ssl3 \
      no-heartbeats no-gost no-deprecated no-capieng no-comp no-dtls no-psk no-srp no-dso no-dsa no-rc2 no-des \
      no-tests no-hw > "$DEPS/openssl-configure.log" 2>&1 )
  log "OpenSSL 配置完成，编译中…"
  ( cd "$SRC" && make -j"$JOBS" > "$DEPS/openssl-build.log" 2>&1 )
  ( cd "$SRC" && make install_sw > "$DEPS/openssl-install.log" 2>&1 )
  log "OpenSSL 完成: $(ls -la "$DEPS/openssl/lib/libcrypto.a" | awk '{print $5" bytes"}')"
else
  log "OpenSSL 已存在: $DEPS/openssl"
fi

# ---------------- FFmpeg（仅 ALAC 软解） ----------------
if [ ! -f "$DEPS/ffmpeg/lib/libavcodec.a" ]; then
  log "构建 FFmpeg（仅 ALAC 解码）-> $DEPS/ffmpeg"
  BUILD="$DEPS/ffmpeg-build"
  rm -rf "$BUILD"; mkdir -p "$BUILD"
  ( cd "$BUILD" && "$TP/ffmpeg/configure" \
      --prefix="$DEPS/ffmpeg" \
      --target-os=android --arch="$([ "$ABI" = arm64-v8a ] && echo aarch64 || ([ "$ABI" = armeabi-v7a ] && echo arm || echo x86_64))" \
      --enable-cross-compile \
      --cc="$NDK_BIN/aarch64-linux-android$API_LEVEL-clang" \
      --cxx="$NDK_BIN/aarch64-linux-android$API_LEVEL-clang++" \
      --ar="$NDK_BIN/llvm-ar" --nm="$NDK_BIN/llvm-nm" \
      --ranlib="$NDK_BIN/llvm-ranlib" --strip="$NDK_BIN/llvm-strip" \
      --sysroot="$NDK_SYSROOT" \
      --enable-pic --disable-asm --disable-x86asm \
      --disable-all --disable-debug --disable-network --disable-autodetect \
      --enable-avcodec --enable-decoder=alac --enable-static --disable-shared \
      > "$DEPS/ffmpeg-configure.log" 2>&1 )
  ( cd "$BUILD" && make -j"$JOBS" > "$DEPS/ffmpeg-build.log" 2>&1 )
  ( cd "$BUILD" && make install > "$DEPS/ffmpeg-install.log" 2>&1 )
  log "FFmpeg 完成"
else
  log "FFmpeg 已存在: $DEPS/ffmpeg"
fi

log "依赖就绪：$DEPS"
ls "$DEPS/openssl/lib/libcrypto.a" "$DEPS/ffmpeg/lib/libavcodec.a" "$DEPS/ffmpeg/lib/libavutil.a"
