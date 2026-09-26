#!/usr/bin/env python3
"""对取回来的第三方源码做适配补丁，幂等可重复执行。

用法: python3 tools/port-sources.py <third_party_dir> <repo_root>

    third_party_dir = <repo>/receiver/app/src/main/cpp/third_party
    repo_root       = 仓库根目录

做三件事（都是平台无关的纯文件改写，Windows / Linux 都能跑）：

  1) openssl-cmake: 默认从 mirror.viaduck.org 下载 OpenSSL 源码（本机不可达）
     -> 改为直接使用本地已取好的 third_party/openssl-src 源码目录
  2) receiver 的 gradle wrapper: services.gradle.org 不可达 -> 腾讯镜像
  3) UxPlay: 手工移植 fork 补丁 0005 的 UxPlay 侧改动（RESET_TYPE_HLS_CONN_CLOSED 等），
     否则 android_raop_callbacks.c 无法编译（上游 942d7b2 没有该枚举）。

为什么第 3 步是必需的手工移植而不是 `git apply`：
仓库自带的 patches/UxPlay/000[1-6] 是相对上游 commit 4621533 写的，而所有可达镜像里
都没有该 commit（上游 rebase/强推），本方案改用镜像中最新的 942d7b2，6 个补丁的上下文
全部对不上。其中只有 0005 的 UxPlay 侧改动是**编译必需**的（CastKit 自己的
android_raop_callbacks.c 引用了 RESET_TYPE_HLS_CONN_CLOSED），所以在这里手工移植；
其余 5 个是崩溃/UAF/兼容性修复，不影响主链路，暂不移植（见 docs/BUILD.md 第 3 节）。

原先是 tools/patch-sources.sh 里的一段内联 heredoc。抽出来是因为 Windows 侧
（JDK + Android SDK 原生构建，不经过 aarch64 容器）同样需要它，而 patch-sources.sh
剩余部分（~/.gradle 镜像）是容器专用的。
"""

import pathlib
import re
import sys

# 显式 UTF-8 + LF：Windows 上 pathlib 默认用本地代码页（简中即 GBK），
# 读 openssl-cmake/UxPlay 这类含非 ASCII 字节的源码会抛 UnicodeDecodeError；
# 而 write_text 默认会把 \n 翻成 \r\n，污染 vendored 源码。
ENC = "utf-8"
NL = "\n"


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    tp = pathlib.Path(sys.argv[1])
    root = pathlib.Path(sys.argv[2])
    changed = []

    def patch(path: pathlib.Path, anchor: str, insert: str, marker: str, where: str = "after"):
        """幂等插入：文件里已有 marker 就跳过；否则在 anchor 之后/之前插入 insert。"""
        if not path.exists():
            print(f"[patch] 跳过（文件不存在）: {path}")
            return False
        text = path.read_text(encoding=ENC)
        if marker in text:
            return False
        if anchor not in text:
            print(f"[patch] 警告：{path.name} 找不到锚点，跳过（上游可能已变更）")
            return False
        idx = text.index(anchor)
        if where == "after":
            out = text[: idx + len(anchor)] + insert + text[idx + len(anchor):]
        else:
            out = text[:idx] + insert + text[idx:]
        path.write_text(out, encoding=ENC, newline=NL)
        changed.append(str(path))
        return True

    # ---------- 1) openssl-cmake -> 本地 OpenSSL 源码 ----------
    f = tp / "openssl-cmake" / "cmake" / "BuildOpenSSL.cmake"
    if not f.exists():
        print(f"[patch] 跳过（文件不存在）: {f}")
    else:
        src = f.read_text(encoding=ENC)
        old = """        URL https://mirror.viaduck.org/openssl/openssl-${OPENSSL_BUILD_VERSION}.tar.gz
        ${OPENSSL_CHECK_HASH}"""
        new = """        # CastKit: 离线构建。本机无法访问 mirror.viaduck.org，
        # 改用 tools/fetch-sources.sh 预先取好的 OpenSSL 源码目录。
        DOWNLOAD_COMMAND ""
        SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../openssl-src"""
        if new not in src:
            if old not in src:
                raise SystemExit("BuildOpenSSL.cmake: 未找到预期的 URL 行，请检查上游是否变更")
            f.write_text(src.replace(old, new), encoding=ENC, newline=NL)
            print("[patch] openssl-cmake -> 本地 openssl-src")
        else:
            print("[patch] openssl-cmake 已打过补丁")

    # ---------- 2) gradle wrapper -> 腾讯镜像 ----------
    w = root / "receiver" / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if not w.exists():
        print(f"[patch] 跳过（文件不存在）: {w}")
    else:
        t = w.read_text(encoding=ENC)
        t2 = re.sub(
            r"distributionUrl=.*",
            "distributionUrl=https\\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip",
            t,
        )
        t2 = re.sub(r"validateDistributionUrl=.*", "validateDistributionUrl=false", t2)
        if t2 != t:
            w.write_text(t2, encoding=ENC, newline=NL)
            print("[patch] gradle-wrapper -> 腾讯镜像")
        else:
            print("[patch] gradle-wrapper 已是镜像")

    # ---------- 3) UxPlay: 移植 fork 补丁 0005 的 UxPlay 侧改动 ----------
    uxplay_lib = tp / "UxPlay" / "lib"
    if not (uxplay_lib / "raop.h").exists():
        print("[patch] 未找到 UxPlay 源码，跳过 UxPlay 补丁")
        return 0

    raop_h = uxplay_lib / "raop.h"
    text = raop_h.read_text(encoding=ENC)
    if "RESET_TYPE_HLS_CONN_CLOSED" not in text:
        old_enum = "    RESET_TYPE_RTP_TO_HLS_TEARDOWN\n} reset_type_t;"
        if old_enum in text:
            text = text.replace(
                old_enum,
                "    RESET_TYPE_RTP_TO_HLS_TEARDOWN,\n    RESET_TYPE_HLS_CONN_CLOSED\n} reset_type_t;",
            )
            raop_h.write_text(text, encoding=ENC, newline=NL)
            print("[patch] UxPlay raop.h: 增加 RESET_TYPE_HLS_CONN_CLOSED")
        else:
            print("[patch] 警告：raop.h 未找到 reset_type_t 锚点")

    raop_c = uxplay_lib / "raop.c"
    patch(
        raop_c,
        anchor="    bool hls_pending;\n",
        insert="    void *video_play_conn;\n",
        marker="void *video_play_conn;",
    )
    patch(
        raop_c,
        anchor='    logger_log(raop->logger, LOGGER_DEBUG, "Destroying connection");\n',
        insert="""
    if (raop->video_play_conn == ptr) {
        raop->video_play_conn = NULL;
        if (raop->callbacks.video_reset) {
            raop->callbacks.video_reset(raop->callbacks.cls, RESET_TYPE_HLS_CONN_CLOSED);
        }
    }
""",
        marker="RESET_TYPE_HLS_CONN_CLOSED",
    )
    patch(
        raop_c,
        anchor="    raop->hls_support = false;\n    raop->hls_pending = false;\n",
        insert="    raop->video_play_conn = NULL;\n",
        marker="raop->video_play_conn = NULL;",
    )
    patch(
        raop_c,
        anchor="void raop_remove_hls_connections(raop_t * raop) {\n",
        insert="    raop->video_play_conn = NULL;\n",
        marker="void raop_remove_hls_connections(raop_t * raop) {\n    raop->video_play_conn = NULL;",
    )

    hh = uxplay_lib / "http_handlers.h"
    patch(
        hh,
        anchor='    logger_log(raop->logger, LOGGER_INFO, "client HTTP request POST stop");\n',
        insert="\n    raop->video_play_conn = NULL;\n",
        # 注意 marker 里的 `");` 不能写成 `";`：原 tools/patch-sources.sh 的内联版本
        # 把它写在双引号字符串里（"… stop\";\n\n…"），少了一个 `)`，于是幂等判断永远为假、
        # 每跑一次就往 http_handler_stop 里多插一行。容器里每次都是重新 fetch 源码后只跑一遍，
        # 所以一直没暴露；Windows 侧是同一份工作树反复跑，第一次就炸出来了。
        marker='client HTTP request POST stop");\n\n    raop->video_play_conn = NULL;',
    )
    text = hh.read_text(encoding=ENC)
    if "raop->video_play_conn = (void *) conn;" not in text:
        needle = "    plist_mem_free(playback_location);\n\n    if (req_root_node) {"
        if needle in text:
            text = text.replace(needle, "    raop->video_play_conn = (void *) conn;\n\n" + needle)
            hh.write_text(text, encoding=ENC, newline=NL)
            print("[patch] UxPlay http_handlers.h: 记录 video_play_conn")
        else:
            print("[patch] 警告：http_handlers.h 未找到 play 成功路径锚点")

    if changed:
        print(f"[patch] 本次改写 {len(changed)} 个文件")
    else:
        print("[patch] UxPlay 补丁已全部就位")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
