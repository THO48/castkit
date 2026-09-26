#!/usr/bin/env bash
# 对取回来的第三方源码做本机（离线镜像 + aarch64 宿主）适配补丁，幂等可重复执行。
#   1) openssl-cmake: 默认从 mirror.viaduck.org 下载 OpenSSL 源码（不可达）
#      -> 改为直接使用本地已取好的 third_party/openssl-src 源码目录
#   2) receiver 的 gradle wrapper: services.gradle.org 不可达 -> 腾讯镜像
#   3) UxPlay: 手工移植 fork 补丁 0005 的 UxPlay 侧改动（RESET_TYPE_HLS_CONN_CLOSED 等），
#      否则 android_raop_callbacks.c 无法编译（上游 942d7b2 没有该枚举）
#   4) 全局 Gradle 依赖镜像（~/.gradle/init.d/mirrors.gradle）—— 仅容器需要
#
# 1~3 是平台无关的纯文件改写，已抽到 tools/port-sources.py：
# Windows 侧（JDK + Android SDK 原生构建，不经过 aarch64 容器）同样要先跑它，
# 否则 CMake 会在 android_raop_callbacks.c 上报
#   error: use of undeclared identifier 'RESET_TYPE_HLS_CONN_CLOSED'
# 见 docs/BUILD.md 第 3 节。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TREE="${CASTKIT_TREE:-/root/castkit}"
TP="$TREE/receiver/app/src/main/cpp/third_party"

# tp: 构建树里的第三方源码；root: 工作区（gradle wrapper 等源文件以工作区为准，构建前会同步）
python3 "$ROOT/tools/port-sources.py" "$TP" "$ROOT"

# 4) Gradle 全局镜像 init script（幂等；不含本机不可达的 mavenCentral/插件门户）——仅容器需要
mkdir -p /root/.gradle/init.d
cat > /root/.gradle/init.d/mirrors.gradle <<'EOF'
// CastKit: 统一走可达镜像（github.com / Maven Central / Gradle Plugin Portal 在本机不可达）
def aliyunPublic = 'https://maven.aliyun.com/repository/public'
def aliyunPlugin = 'https://maven.aliyun.com/repository/gradle-plugin'
def aliyunGoogle = 'https://maven.aliyun.com/repository/google'

beforeSettings { settings ->
    settings.pluginManagement.repositories {
        maven { url aliyunPublic }
        maven { url aliyunPlugin }
        maven { url aliyunGoogle }
        google()
    }
}

settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositories {
        maven { url aliyunPublic }
        maven { url aliyunGoogle }
        google()
    }
}
EOF
echo "[patch] ~/.gradle/init.d/mirrors.gradle 已写入"
