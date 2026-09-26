#!/usr/bin/env bash
# CastKit 构建环境变量（source 本文件后再执行 gradle）
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME=/opt/android-sdk/ndk/27.0.12077973
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
export CASTKIT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 构建树：必须在可执行文件系统上（/sdcard 是 noexec，见 tools/build-tree.sh）
export CASTKIT_TREE="${CASTKIT_TREE:-/root/castkit}"

# 构建用的 gradle 参数：内存受限、给 native 构建留出余量；
# 连接/读取超时设短一些，避免卡在不可达仓库上（mavenCentral/plugins.gradle.org 本机不可达）
export CASTKIT_GRADLE_ARGS="${CASTKIT_GRADLE_ARGS:---no-daemon --console=plain \
  -Dorg.gradle.internal.http.connectionTimeout=15000 \
  -Dorg.gradle.internal.http.socketTimeout=30000}"
