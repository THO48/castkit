#!/usr/bin/env bash
# 对取回来的第三方源码做本机（离线镜像 + aarch64 宿主）适配补丁，幂等可重复执行。
#   1) openssl-cmake: 默认从 mirror.viaduck.org 下载 OpenSSL 源码（不可达）
#      -> 改为直接使用本地已取好的 third_party/openssl-src 源码目录
#   2) receiver 的 gradle wrapper: services.gradle.org 不可达 -> 腾讯镜像
#   3) 全局 Gradle 依赖镜像（~/.gradle/init.d/mirrors.gradle，去掉不可达仓库）
#   4) UxPlay: 手工移植 fork 补丁 0005 的 UxPlay 侧改动（RESET_TYPE_HLS_CONN_CLOSED 等），
#      否则 android_raop_callbacks.c 无法编译（上游 942d7b2 没有该枚举）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TREE="${CASTKIT_TREE:-/root/castkit}"
TP="$TREE/receiver/app/src/main/cpp/third_party"

# tp: 构建树里的第三方源码；root: 工作区（gradle wrapper 等源文件以工作区为准，构建前会同步）
python3 - "$TP" "$ROOT" <<'PY'
import pathlib, re, sys

tp = pathlib.Path(sys.argv[1]); root = pathlib.Path(sys.argv[2])
changed = []

def patch(path: pathlib.Path, anchor: str, insert: str, marker: str, where: str = "after"):
    """幂等插入：文件里已有 marker 就跳过；否则在 anchor 之后/之前插入 insert。"""
    if not path.exists():
        print(f"[patch] 跳过（文件不存在）: {path}")
        return False
    text = path.read_text()
    if marker in text:
        return False
    if anchor not in text:
        print(f"[patch] 警告：{path.name} 找不到锚点，跳过（上游可能已变更）")
        return False
    idx = text.index(anchor)
    if where == "after":
        out = text[:idx + len(anchor)] + insert + text[idx + len(anchor):]
    else:
        out = text[:idx] + insert + text[idx:]
    path.write_text(out)
    changed.append(str(path))
    return True

# ---------- 1) openssl-cmake -> 本地 OpenSSL 源码 ----------
f = tp / "openssl-cmake" / "cmake" / "BuildOpenSSL.cmake"
src = f.read_text()
old = """        URL https://mirror.viaduck.org/openssl/openssl-${OPENSSL_BUILD_VERSION}.tar.gz
        ${OPENSSL_CHECK_HASH}"""
new = """        # CastKit: 离线构建。本机无法访问 mirror.viaduck.org，
        # 改用 tools/fetch-sources.sh 预先取好的 OpenSSL 源码目录。
        DOWNLOAD_COMMAND ""
        SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../openssl-src"""
if new not in src:
    if old not in src:
        raise SystemExit("BuildOpenSSL.cmake: 未找到预期的 URL 行，请检查上游是否变更")
    f.write_text(src.replace(old, new))
    print("[patch] openssl-cmake -> 本地 openssl-src")
else:
    print("[patch] openssl-cmake 已打过补丁")

# ---------- 2) gradle wrapper -> 腾讯镜像 ----------
w = root / "receiver" / "gradle" / "wrapper" / "gradle-wrapper.properties"
t = w.read_text()
t2 = re.sub(r"distributionUrl=.*",
            "distributionUrl=https\\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip", t)
t2 = re.sub(r"validateDistributionUrl=.*", "validateDistributionUrl=false", t2)
if t2 != t:
    w.write_text(t2)
    print("[patch] gradle-wrapper -> 腾讯镜像")
else:
    print("[patch] gradle-wrapper 已是镜像")

# ---------- 4) UxPlay: 移植 fork 补丁 0005 的 UxPlay 侧改动 ----------
await_u = tp / "UxPlay" / "lib"
if (await_u / "raop.h").exists():
    raop_h = await_u / "raop.h"
    text = raop_h.read_text()
    if "RESET_TYPE_HLS_CONN_CLOSED" not in text:
        old_enum = "    RESET_TYPE_RTP_TO_HLS_TEARDOWN\n} reset_type_t;"
        if old_enum in text:
            text = text.replace(
                old_enum,
                "    RESET_TYPE_RTP_TO_HLS_TEARDOWN,\n    RESET_TYPE_HLS_CONN_CLOSED\n} reset_type_t;")
            raop_h.write_text(text)
            print("[patch] UxPlay raop.h: 增加 RESET_TYPE_HLS_CONN_CLOSED")
        else:
            print("[patch] 警告：raop.h 未找到 reset_type_t 锚点")

    raop_c = await_u / "raop.c"
    patch(raop_c,
          anchor="    bool hls_pending;\n",
          insert="    void *video_play_conn;\n",
          marker="void *video_play_conn;")
    patch(raop_c,
          anchor='    logger_log(raop->logger, LOGGER_DEBUG, "Destroying connection");\n',
          insert="""
    if (raop->video_play_conn == ptr) {
        raop->video_play_conn = NULL;
        if (raop->callbacks.video_reset) {
            raop->callbacks.video_reset(raop->callbacks.cls, RESET_TYPE_HLS_CONN_CLOSED);
        }
    }
""",
          marker="RESET_TYPE_HLS_CONN_CLOSED")
    patch(raop_c,
          anchor="    raop->hls_support = false;\n    raop->hls_pending = false;\n",
          insert="    raop->video_play_conn = NULL;\n",
          marker="raop->video_play_conn = NULL;")
    patch(raop_c,
          anchor="void raop_remove_hls_connections(raop_t * raop) {\n",
          insert="    raop->video_play_conn = NULL;\n",
          marker="void raop_remove_hls_connections(raop_t * raop) {\n    raop->video_play_conn = NULL;")

    hh = await_u / "http_handlers.h"
    patch(hh,
          anchor='    logger_log(raop->logger, LOGGER_INFO, "client HTTP request POST stop");\n',
          insert="\n    raop->video_play_conn = NULL;\n",
          marker="client HTTP request POST stop\";\n\n    raop->video_play_conn = NULL;")
    text = hh.read_text()
    if "raop->video_play_conn = (void *) conn;" not in text:
        needle = "    plist_mem_free(playback_location);\n\n    if (req_root_node) {"
        if needle in text:
            text = text.replace(needle,
                                "    raop->video_play_conn = (void *) conn;\n\n" + needle)
            hh.write_text(text)
            print("[patch] UxPlay http_handlers.h: 记录 video_play_conn")
        else:
            print("[patch] 警告：http_handlers.h 未找到 play 成功路径锚点")
else:
    print("[patch] 未找到 UxPlay 源码，跳过 UxPlay 补丁")
PY

# 3) Gradle 全局镜像 init script（幂等；不含本机不可达的 mavenCentral/插件门户）
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
