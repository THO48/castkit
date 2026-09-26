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

## 启动图标（两个 App 共用一套）

两个 App 的图标取自 Apache-2.0 的开源图标集，**形状相同、字重不同** —— 发送端用描边版、
接收端用实心版：桌面上一眼能分开，又明显是同一套产品。

| App | 图形 | 来源 | 许可 |
|---|---|---|---|
| 发送端 | `cast`（**描边**版） | [Material Design Icons](https://github.com/Templarian/MaterialDesign)（Pictogrammers） | **Apache-2.0**（其 LICENSE 中注明 `# Icons: Apache 2.0`） |
| 接收端 | `cast`（**实心**版） | [Material Symbols](https://github.com/google/material-design-icons)（Google） | **Apache-2.0** |

为什么不选 `cast` + `connected_tv` 那种"更贴语义"的组合：两者都是"矩形 + 波纹"，
缩到桌面图标尺寸（约 48dp）后分不出来。描边 / 实心的差异在小尺寸下才立得住。

转换方式：两个 SVG 都是 24×24 视口，转成 Android VectorDrawable 时用 `group`
缩放 2.5 倍并平移 24，映射到 108×108 自适应图标画布的中间 60×60（安全区 66dp）。
接收端的单色层（Android 13+ 主题图标）用的是**实心版** —— 系统只取 alpha 通道着色，
描边在主题图标下会变成一圈细线。

## 与上游的差异（接收端）

1. `applicationId` 改为 `com.dsh.castkit.receiver`，应用名/图标中文化（可与 F-Droid 版本共存）。
2. `app/build.gradle.kts`：新增 `-PcastkitAbis` 以限制构建的 ABI（默认只出 arm64-v8a）。
3. `applyUxplayPatches` 任务：离线 vendored 模式下跳过（原实现依赖 git submodule）。
4. `settings.gradle.kts`：仓库改为可达镜像（阿里云 + Google Maven）。
5. `third_party/openssl-cmake`：改用本地 OpenSSL 源码（原 URL 不可达）。
6. 新增 `net/` 下的 LANCast 接收实现与相关设置项（见 `docs/LANCast-v1.md`）。
7. 新增 `values-zh-rCN` 中文资源与显示模式/局域网接收设置。
8. 启动图标从上游的"白底 + 灰色三层阴影 AirPlay 图形"改为开源图标集的实心 `cast`
   （深蓝底 `#14304A` + 白色图形），并与发送端的描边版配成一套，见上文「启动图标」一节。
9. **补上 `mipmap-mdpi … mipmap-xxxhdpi` 的传统位图**（5 档 × 方形/圆形）。
   上游 `minSdk = 24` 却只提供 `mipmap-anydpi-v26`，而 `anydpi-v26` 只在 API 26+ 生效 ——
   在 Android 7.0/7.1 上 `@mipmap/ic_launcher` 解析不到，桌面图标会是空白。
   这是上游就存在的问题，本次顺带修掉。
