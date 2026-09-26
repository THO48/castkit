# 第三方组件与许可

## 接收端（`receiver/`）

| 组件 | 版本/来源 | 许可 | 用途 |
|---|---|---|---|
| [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) | commit `c8defdd`（2026-08-23） | **GPL-3.0** | 接收端基座：Kotlin/Compose UI + JNI 桥 + MediaCodec 渲染 |
| [FDH2/UxPlay](https://github.com/FDH2/UxPlay) | gitcode 镜像 commit `942d7b2` | **GPL-3.0** | AirPlay/RAOP 协议栈、FairPlay、mDNS |
| UxPlay 内置 `playfair` | 随 UxPlay | **GPL-3.0**（逆向实现的 FairPlay） | AirPlay 视频解密密钥协商 |
| UxPlay 内置 `llhttp` | 随 UxPlay | MIT | HTTP 解析 |
| [libimobiledevice/libplist](https://github.com/libimobiledevice/libplist) | `f41b1ea6` | LGPL-2.1 | binary plist 解析 |
| [FFmpeg](https://github.com/FFmpeg/FFmpeg) | `38b88335`（仅 `--enable-decoder=alac`） | LGPL-2.1（本构建未启用 GPL 组件） | ALAC 软解兜底 |
| [OpenSSL](https://github.com/openssl/openssl) | `openssl-3.4.4` | Apache-2.0 | AES/SHA/RSA |
| [viaduck/openssl-cmake](https://github.com/viaduck/openssl-cmake) | `4edd36a8` | MIT | Android 交叉编译 OpenSSL 的 CMake 封装 |
| [google/oboe](https://github.com/google/oboe) | 1.9.3（Google Maven） | Apache-2.0 | 低延迟音频输出 |
| Next Player（ExoPlayer/Media3） | 1.11.0-beta01 | Apache-2.0 | HLS/视频播放 |

> 由于 `receiver/` 链接了 GPL-3.0 的 UxPlay/playfair，**整个接收端 App 必须以 GPL-3.0 分发**；
> 同时 FairPlay 相关代码是社区逆向实现，Apple 对未授权 AirPlay 接收端有 MFi 认证要求 —— 本工程仅用于
> 个人/自用场景，商用需自行评估法律风险或改走商业 SDK 授权。

## 发送端（`sender/`）

| 组件 | 许可 | 说明 |
|---|---|---|
| AndroidX（core/activity/lifecycle/compose/material3） | Apache-2.0 | UI 与生命周期 |
| kotlinx-coroutines | Apache-2.0 | 并发 |
| MediaProjection / MediaCodec（系统 API） | — | 采集与编码 |

发送端**未**引入 GPL 代码；LANCast 协议与实现均为本项目自研（`docs/LANCast-v1.md`）。

## 与上游的差异（接收端）

1. `applicationId` 改为 `com.dsh.castkit.receiver`，应用名/图标中文化（可与 F-Droid 版本共存）。
2. `app/build.gradle.kts`：新增 `-PcastkitAbis` 以限制构建的 ABI（默认只出 arm64-v8a）。
3. `applyUxplayPatches` 任务：离线 vendored 模式下跳过（原实现依赖 git submodule）。
4. `settings.gradle.kts`：仓库改为可达镜像（阿里云 + Google Maven）。
5. `third_party/openssl-cmake`：改用本地 OpenSSL 源码（原 URL 不可达）。
6. 新增 `net/` 下的 LANCast 接收实现与相关设置项（见 `docs/LANCast-v1.md`）。
7. 新增 `values-zh-rCN` 中文资源与显示模式/局域网接收设置。
