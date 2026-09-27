# CastKit 架构

## 组件

```
┌──────────────────────────── 接收端 App (com.dsh.castkit.receiver) ────────────────────────────┐
│  Kotlin / Jetpack Compose (fork 自 jqssun/android-airplay-server)                              │
│                                                                                               │
│   MainActivity ── MainViewModel ──┬── SettingsScreen（服务/连接/显示/解码/局域网投屏）            │
│                                   └── MainScreen（状态 + 视频 SurfaceView + 手势/遥控器）        │
│                                                                                               │
│   AirPlayService (LifecycleService, 前台服务 connectedDevice|mediaPlayback)                    │
│      ├── NsdServiceManager         Bonjour mDNS 广播 _airplay._tcp / _raop._tcp                │
│      ├── VideoRenderer             AirPlay 镜像：MediaCodec 硬解 → EGL → Surface               │
│      ├── AirPlayVideoPlayer        ExoPlayer：AirPlay 视频 / HLS                               │
│      ├── AudioRenderer (Oboe)      音频输出（AAC-ELD/LC、ALAC 软解兜底）                        │
│      └── LanCastReceiver           安卓投屏：TCP 8123 → MediaCodec 硬解 → 同一个 Surface        │
│                                                                                               │
│   native/ (JNI, arm64-v8a)                                                                    │
│      UxPlay(RAOP/mirroring/FairPlay/llhttp) + playfair + libplist + OpenSSL + FFmpeg(alac)     │
│      native_bridge.cpp / android_raop_callbacks.c / android_dnssd_shim.c                       │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
                ▲ AirPlay (RAOP/HLS, FairPlay)                ▲ LANCast v1 (TCP, H.264 AU)
                │                                               │
        iPhone / iPad / Mac                       安卓手机 + 发送端 App (com.dsh.castkit.sender)
                                                             MediaProjection → MediaCodec(H.264)
                                                             分辨率/码率/帧率可配 → LanCastClient
```

## 关键数据流

> **播放器不挂在主线程上。** 两端的 Media3/ExoPlayer（发送端 `media/LocalVideoPlayer`、
> 接收端 `renderer/LanVideoPlayer`）都跑在各自的 `HandlerThread` 上，所有对播放器的调用
> 都经由那个 Looper。原因：Media3 把渲染器/分析回调投递到**播放器所在的 Looper**，
> 新版里这类回调是**逐帧**的（`onVideoFrameProcessingOffset`），每个都要读播放位置
> （拿播放器内部锁 + 走时间线）。挂在主线程上时，长片退出播放页会为了排完这些积压
> **单帧耗时 2 秒**（`Davey! duration=2059ms`），用户感受就是"画面在但点不动"。
> 判定过程见 `debug/MainThreadWatchdog`（帧统计看不到主线程阻塞）。

### AirPlay 屏幕镜像（iOS/iPadOS/macOS）
1. 接收端通过 mDNS 广播 `_airplay._tcp`（含 `features`/`pk` 等 TXT），iPhone 的「屏幕镜像」列表中出现设备。
2. 发送端做 FairPlay 握手（`/fp-setup`，由 UxPlay + playfair 处理）解出 AES 密钥。
3. `POST /stream`（SETUP）协商镜像流，**接收端在响应里给出 `width`/`height`/`refreshRate`**
   （来自 `Prefs.RESOLUTION` / `Prefs.MAX_FPS`，经 `NativeBridge.nativeSetDisplaySize`）。
4. H.264/H.265 经 RTP 送达 → UxPlay 解密 → `native_bridge` 回调 → `VideoPipeline`/`MediaCodec` → Surface。

> 码率由 iOS 依据分辨率/帧率/网络自行决定，接收端无法直接设定；要「码率可自定义」请用安卓发送端。

### 安卓局域网投屏（LANCast v1）
1. 接收端 `LanCastReceiver` 监听 TCP 8123（设置里可改），`LanCastDiscovery` 同时监听 UDP 8124
   并周期广播自身；发送端 `LanCastDiscovery` 每 3 秒广播探测，收到回应后列出设备并自动选中（无需手输 IP）。
2. 发送端 `LanCastClient` 连接后发送握手（分辨率/帧率/码率/CSD），接收端回 `READY`。
3. 发送端按 12 字节帧头推 H.264 访问单元；接收端 `MediaCodec` 解码后直接 `releaseOutputBuffer(…, true)`
   打到与 AirPlay 镜像**同一个** `SurfaceView`。
4. 丢关键帧/切换 Surface 时接收端回 `REQUEST_KEYFRAME`，发送端强制 I 帧。
5. 局域网会话期间界面会渲染视频 Surface（`lanActive` 参与 Overview 的显示条件），并按「自动全屏」设置进入全屏；
   会话建立时若开启了「连接时打开此应用」，Service 会把界面拉到前台（否则后台没有 Surface，等同于“没反应”）。
6. 会话建立时会缓存 CSD：**CSD 早于 Surface 到达**（先开发送端、后开接收端）时，Surface 一到就用缓存 CSD 补建解码器，
   并有 2 秒看门狗兜底，然后请求关键帧。
7. AirPlay 会话优先：AirPlay 客户端接入时，局域网会话被主动关闭；已有局域网会话时新连接被回 `BUSY(3)`。

协议字段/状态机详见 `docs/LANCast-v1.md`。

### 画面比例（CastKit 约定）

- **发送端**：采集尺寸由 `CaptureSize.compute()` 按「短边像素 + 屏幕长宽比」推导（16 对齐、不放大、
  编码器不支持时回退），VirtualDisplay 与编码器使用**同一个**尺寸；旋转时 `VirtualDisplay.resize()`
  + `setSurface()`（Android 14+ 不允许同一 MediaProjection 二次 createVirtualDisplay），
  先发 `TYPE_RESIZE` 再发 CSD。
- **发送端旋转重建的铁律**：屏幕方向变化**绝不允许结束投屏**。旋转的处理顺序是
  「先用旧链路继续投流 → 建好并启动新编码器 → resize + 挂新面 → 成功后才释放旧编码器」，
  任一步失败都回滚到旧链路并只在界面提示。`DisplayListener` 在旋转动画期间会连发多次，
  用单线程 + 350ms 合并窗口处理成一次。每个编码器带一个**代次（generation）**，
  旧代次的帧/CSD 回调一律丢弃，避免旧分辨率的迟到帧混进新流里。
  编码器异步出错时不再 teardown，而是走同一条重建路径自愈（单次会话最多自动重建 5 次）。
- **投送收尾顺序**：结束投送时先在后台把 `TYPE_STOP` 发出去、留 350ms 送达时间，**然后**才关控制连接、
  停 HTTP 文件服务。直接 close 会让 TCP 发 RST 丢掉未读数据，接收端就收不到停止指令，只能等
  HTTP 流被掐断后由播放器报错（实测 `what=-38`）退出。
- **心跳**：发送端统计线程在“1 秒内没有任何字节发出”时累积空闲，超过 `PING_INTERVAL_MS(5s)`
  就发 `TYPE_PING`。否则屏幕静止或重建编码器期间接收端会按 15 秒空闲超时把连接断掉。
- **接收端解码器重建**：只在**真的换了 Surface 对象**（`setSurface` 判重）或**CSD 内容变了**
  时才重建解码器。此前 UI 每次布局/比例变化都会上报同一个 Surface，导致旋转时 100ms 内连续
  建了 4 个解码器，最终耗尽编解码器资源并出现「解码器创建失败」。
- **接收端**：视频 Surface 的比例取自**流本身**（LAN 会话用 `LanCastStatus.aspect`，AirPlay 用解码尺寸
  `videoAspect`），不足处留黑边，不做拉伸。

### 排查通道

- 接收端日志除 App 内日志页外，会节流镜像到 `/sdcard/Download/castkit-receiver-logs.txt`
  （`debug/PublicLogWriter`，API 29+ 走 MediaStore，无需权限），外部工具/DSHA 桥可直接读取。
- **发送端主线程卡顿看门狗**（`debug/MainThreadWatchdog`，仅可调试包启用）：后台线程每 150ms
  往主线程 post 一个空任务量延迟，>400ms 记一条 `MainBlock`，>1.5s 直接把**主线程当时的调用栈**
  打出来。存在的理由：`dumpsys gfxinfo` 的直方图**只统计渲染出来的帧**，
  主线程被 native 调用（`release()`/`stop()`）钉住几秒时那几秒一帧都没有，直方图上反而"很干净"。
- mDNS 注册结果与 TXT 记录、AirPlay features 位（bit 27 legacy pairing、bit 42 H265）都会写进日志，
  便于定位"iPhone 看不到设备 / 连上没画面"。
- 设置页提供排查开关：`跳过配对（兼容模式）`（features bit 27=0）与 `重置配对密钥`。

## 设置项（接收端）

| 分组 | 关键项 |
|---|---|
| 服务 | 设备名称、端口、自动启动、开机启动、后台运行 |
| 连接 | PIN 配对、允许新连接、连接时打开应用（悬浮窗权限） |
| 显示 | 目标分辨率（自动/720p/1080p/4K/自定义宽高）、最大帧率、过扫描、画面模式 |
| 解码 | H.265、SDR 标记、ALAC/AAC 声明、软件 ALAC、低延迟/缓冲 |
| **局域网投屏** | 启用开关、端口、本机地址显示、当前会话分辨率/码率/解码器 |

## 多屏幕尺寸适配

- Jetpack Compose + `dpadFocus`：手机与 Android TV（`LEANBACK_LAUNCHER`、`values-television`）共用一套 UI。
- `MainActivity` `configChanges` 覆盖 `orientation|screenSize|screenLayout|smallestScreenSize`，
  旋转时不重建 Activity；`AirPlayService.onConfigurationChanged` 跟随设备方向重新协商分辨率。
- 画面缩放：best fit / 拉伸 / 裁剪 / 100% 原始像素（`scale_*` 字符串），由
  `VideoSurfaceView(applyAspectRatio = false)` + 调用方约束实现。
- 画中画：`supportsPictureInPicture` + 随当前视频宽高比更新的 `PictureInPictureParams`。
- 局域网投屏与 AirPlay 共用 Surface，因此任一模式下的尺寸变化都会重建解码器（并请求关键帧）。

## 工程约束（本机特有问题）

见 `docs/BUILD.md`：noexec 的 `/sdcard`（构建树放 `/root/castkit`）、aarch64 宿主跑 x86_64 工具
（qemu 跑 aapt2、NDK 工具换本机 clang）、依赖镜像、以及 GNU make jobserver 死锁导致的
「预构建 OpenSSL/FFmpeg」方案。

## 发送端 UI 规范（Miuix）

发送端界面基于 [Miuix](https://github.com/compose-miuix-ui/miuix)（HyperOS 风格组件库，`top.yukonga.miuix.kmp:miuix:0.3.4`，
对应 Kotlin 2.1.0 + Compose 1.7.3）。改界面前请对照官方规范（仓库 `docs/guide/textstyles.md` 与 `docs/demo/`）：

**字号层级**（务必用令牌，不要随手写 sp）

| 用途 | 令牌 | 字号 |
|---|---|---|
| 卡片/状态主标题 | `textStyles.title4` + `FontWeight.Medium` | 18sp |
| 正文段落、说明文字 | `textStyles.paragraph` | 17sp（行高 1.2em） |
| 次级信息（地址、尺寸等） | `textStyles.body2` | 14sp |
| 三级信息（统计、角标、磁贴副信息） | `textStyles.footnote1` | 13sp |
| 极次要（磁贴元信息） | `textStyles.footnote2` | 11sp |

**布局惯例**

- 区块 = `SmallTitle("标题")` + `Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp))`；
  `SmallTitle` 默认 28dp 内缩，正好对齐卡片 12dp 外边距 + 16dp 内边距后的内容。
- 卡片之间留 **16dp** 垂直间距（早期版本漏了，卡片会贴在一起）。
- 列表项用 `BasicComponent` / `SuperArrow` / `SuperSwitch` / `SuperDropdown`，不要自绘一行；
  按 `BasicComponent` 内部已处理标题与摘要字号，不要再传 style。
- 设置类弹窗用 `SuperDialog`（HyperOS 居中弹窗），选项行右侧用 `MiuixIcons.Check` 表示选中。
- 顶栏动作用 `IconButton` + 图标，不要塞多个文字按钮。
