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
| Next Player（ExoPlayer/Media3） | 1.11.0（Google Maven） | Apache-2.0 | AirPlay HLS + 局域网视频播放（引擎） |
| [anilbeesetti/nextlib](https://github.com/anilbeesetti/nextlib) | `nextlib-media3ext` 1.11.0-0.15.0（Maven Central） | **GPL-3.0** | 给 Media3 补 **FFmpeg 软解**（视频 H.264/HEVC/VP8/VP9/AV1；音频 AC3/EAC3/DTS/TrueHD/FLAC/…）。官方 `media3-decoder-ffmpeg` 只有音频，视频必须靠它 |
| [libVLC（VLC for Android）](https://code.videolan.org/videolan/vlc-android) | `org.videolan.android:libvlc-all` 3.7.6（Maven Central） | **LGPL-2.1**（`libvlc-all` 打包版；VLC 引擎部分为 LGPL-2.1+） | 兜底内核：自带 FFmpeg **解封装器**，播 NextLib / Media3 都打不开的容器（ASF/WMV/WMA） |

> 由于 `receiver/` 链接了 GPL-3.0 的 UxPlay/playfair，**整个接收端 App 必须以 GPL-3.0 分发**；
> 同时 FairPlay 相关代码是社区逆向实现，Apple 对未授权 AirPlay 接收端有 MFi 认证要求 —— 本工程仅用于
> 个人/自用场景，商用需自行评估法律风险或改走商业 SDK 授权。

## 发送端（`sender/`）

| 组件 | 许可 | 说明 |
|---|---|---|
| AndroidX（core/activity/lifecycle/compose/material3） | Apache-2.0 | UI 与生命周期 |
| kotlinx-coroutines | Apache-2.0 | 并发 |
| MediaProjection / MediaCodec（系统 API） | — | 采集与编码 |
| [Next Player（ExoPlayer/Media3）](https://github.com/androidx/media) | 1.11.0（Google Maven） | Apache-2.0 | 本地视频播放引擎 |
| [anilbeesetti/nextlib](https://github.com/anilbeesetti/nextlib) | `nextlib-media3ext` 1.11.0-0.15.0（Maven Central） | **GPL-3.0** | FFmpeg 软解（视频 H.264/HEVC/VP8/VP9/AV1；音频 AC3/EAC3/DTS/TrueHD/FLAC/…） |
| [libVLC（VLC for Android）](https://code.videolan.org/videolan/vlc-android) | `org.videolan.android:libvlc-all` 3.7.6（Maven Central） | **LGPL-2.1** | 兜底内核：给本地播放补上 ASF/WMV 解封装；同时给文件列表补系统拿不到的时长、分辨率与缩略图（与接收端同一版本） |

> **发送端现在也含 GPL-3.0 代码**（`nextlib-media3ext`）。发送端自身仍是本仓库自研代码，
> 但分发带 NextLib 的 APK 时整体需按 **GPL-3.0** 处理；若将来要闭源分发，需要把 NextLib 换掉。
> LANCast 协议与实现均为本项目自研（`docs/LANCast-v1.md`）。
> NextLib 的 FFmpeg `.so` 按 ABI 各带一份且 AGP 默认**不压缩**打包，四个 ABI 合计约 34 MB。
> 再叠加 libVLC（4 个 ABI 合计约 201 MB 未压缩），发送端 APK 现在约 239 MB ——
> 已确认「不超过 500 MB 可接受」，所以没有做 ABI 裁剪；若要瘦身，优先砍 `x86/x86_64`
> （真机都是 arm，x86 只在模拟器上用）。

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
10. **局域网「投视频文件」的播放引擎从系统 `MediaPlayer` 换成 Media3 ExoPlayer + NextLib FFmpeg**
    （`renderer/LanVideoPlayer.kt`）。原因见该文件顶部注释，简要版：
    - 系统 `MediaPlayer` 用的是安卓自带的 `MPEG4Extractor`，遇到病态容器时间基会**直接弃轨**
      （实测日志 `MPEG4Extractor: track->timescale overflow`，容器 `mdhd` timescale = 2^31−1），
      表现为"有声音、进度在走、画面全黑"，且**连视频解码器都不会创建**。
      Media3 的 `Mp4Extractor` 是它自己重写的实现，时间戳走防溢出的 `Util.scaleLargeTimestamp`。
    - 解码器侧仍可能吃不下：实测 1080i MBAFF 隔行流会被小米的 `c2.xring.avc.decoder`
      **静默丢弃每一个 buffer**（`MediaCodec discarded an unknown buffer`），同样黑屏且不报错。
      NextLib 把这个流交给了 FFmpeg 软解。
    - 策略是**硬解优先 + 看门狗回退**：正常片源仍走硬解；起播后若干秒没渲染出第一帧才切 FFmpeg。
      实测正常 MP4/MKV/FLV/TS/MOV 仍走硬解，只有隔行/MPEG-2 这类才落到软解。
    - 代价：APK 增大约 7.9 MB（arm64-v8a 的 `libavcodec/swscale/avutil/swresample/media3ext`）。
11. **再加一层 libVLC 兜底内核**（`renderer/LanVideoPlayer.kt`），专治 ExoPlayer + NextLib 都打不开的容器：
    - NextLib 只提供解码器、不提供解封装器（源码里开了 `--enable-avformat`，但发布出的 AAR 不带
      `libavformat.so`），Media3 也没有 ASF 解封装器 —— 所以 **WMV/ASF/WMA 会在解析阶段就失败**
      （`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`）。libVLC 自带 FFmpeg 解封装，一次全兜住。
    - 策略：**ExoPlayer 优先**。只有它报「容器/编码不认识」时才切 libVLC，从当前进度接着播；
      已实测的 MP4/MKV/AVI/FLV/TS/PS/MOV/FC2 路径完全不受影响（仍走原来的硬解/FFmpeg 软解）。
    - 代价：arm64-v8a 的 `libvlc.so` + `libvlcjni.so` 约 46 MB，接收端 APK 30.2 MB → 82 MB。
    - 坑（已修，值得记）：libVLC 的 vout 必须用 `setWindowSize()` 拿到**渲染面的真实像素尺寸**。
      给成屏幕尺寸（1440×3200）而 SurfaceView 只有 1440×810 时它**不报任何错**：照样解码、
      日志里 `Received first picture`、进度正常走，但画面按 1440×3200 画布居中排版，
      SurfaceView 只显示其中一条 —— 表现就是**全黑**。尺寸只能从 `SurfaceHolder.getSurfaceFrame()`
      拿（`Surface` 自己查不到），所以 UI 层把 `SurfaceHolder` 一路传到了播放器。
    - 另一个坑：libVLC 的 access 模块里**没有 `content://`**（日志 `no access modules matched`），
      发送端本地播 MediaStore 文件时必须先 `openFileDescriptor` 再用 fd 建 `Media`。
