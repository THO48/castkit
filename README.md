# CastKit

**把一台 Android 平板 / 电视盒子 / 旧手机变成全能投屏接收端。**

- **iPhone / iPad / Mac**：直接使用系统自带「屏幕镜像」（AirPlay）投屏，无需装第三方 App；
- **Android 手机 + CastKit 发送端**：
  - **高帧率屏幕镜像**：支持同步传输系统声音（AAC-LC）、动态码率与自动发现；
  - **原画质投视频文件 & 共享视频库**：通过局域网 HTTP Range 直传原文件，**接收端可随时退出当前视频、浏览发送端完整目录并自由选片切换**。

```text
iPhone / iPad / Mac ──── AirPlay（原生，端口 7000）───────────────────────┐
                                                                          │
Android + CastKit 发送端 ── LANCast v1（TCP 8123，H.264 + AAC 系统音）────┼─→ CastKit 接收端 ──→ 全屏 / PiP 播放
                                                                          │
Android + CastKit 发送端 ── HTTP 视频库与原文件直传（端口 8130，Range 206）┘   （平板可主动退回目录 / 播中侧边栏选片）
```

---

## 下载与安装

当前最新版本：**[v2.0.0](https://github.com/THO48/castkit/releases/tag/v2.0.0)**

| 文件 | 适用设备 | 大小 | 最低系统 | 原生 ABI |
|---|---|---|---|---|
| [`CastKit-Sender-2.0.0-debug.apk`](https://github.com/THO48/castkit/releases/download/v2.0.0/CastKit-Sender-2.0.0-debug.apk) | **发送端**（手机等输出端） | 19.2 MB | Android 8.0 (API 26) | 通用（纯 Java/Kotlin + Media3） |
| [`CastKit-Receiver-2.0.0-debug.apk`](https://github.com/THO48/castkit/releases/download/v2.0.0/CastKit-Receiver-2.0.0-debug.apk) | **接收端**（平板 / 盒子 / 电视） | 31.4 MB | Android 7.0 (API 24) | `arm64-v8a` |

### 安装说明

- 两个安装包均为 **debug 构建**，可直接安装体验；若后续切换到正式签名版本需先卸载再装。
- 接收端包含 UxPlay / OpenSSL / FFmpeg / Oboe 原生库，目前预构建为 **`arm64-v8a`**。
- v2.0.0 两端播放器已全面切换为 **Media3 `ExoPlayer`**（移除体积庞大的 libVLC），安装包体大幅瘦身至 19.2 MB / 31.4 MB。
- 如需在发送端扫描 `.nomedia` 目录或媒体库未收录的视频文件，请授予**「所有文件访问」**权限；镜像传输系统声音需在开启镜像时同意系统音频采集权限。

### 快速上手

1. **接收端**安装并打开 `CastKit-Receiver-2.0.0-debug.apk`，应用会自动开启 AirPlay（端口 `7000`）与 LANCast（TCP `8123` + UDP `8124` 自动发现）。
2. **Apple 设备**：下拉控制中心 →「屏幕镜像」→ 选择接收端设备名即可。
3. **Android 发送端**：安装 `CastKit-Sender-2.0.0-debug.apk`，同一 Wi-Fi 下自动发现接收端：
   - 在**「投屏」**页一键开始屏幕镜像（可同步传输系统声音），或直接**「共享本机视频库」**让平板自行选片；
   - 在**「视频库」**页浏览目录播放，点击播放页右上角投屏按钮即可将当前视频原画质投送到接收端。

---

## 核心功能

### 1. 接收端远程选片 & 目录导航（v2.0.0 新增）

- **退出视频不中断连接**：投送视频时，在接收端点击返回即可退出当前视频，直接回到**该视频所在的发送端文件夹**（自动定位到当前影片），无需重新连接即可挑选下一部。
- **播中侧边栏选片**：播放界面内打开「选片」抽屉即可无缝切集，支持**进入子目录**、**返回上一级**与关键字实时搜索；切换影片后发送端遥控台同步更新标题、封面与进度。
- **主动管理会话**：可在接收端随时刷新视频库、最小化回到主控制台，或一键断开整个远程选片会话。

### 2. 影院级播放体验 & Media3 ExoPlayer 引擎（v2.0.0 升级）

发送端本机播放页与接收端局域网视频播放页均升级为 **Media3 `ExoPlayer`** 并统一手势交互：

- **彻底修复部分视频「有声黑屏」**：
  - 启用硬件解码失败自动回落软件解码（`setEnableDecoderFallback(true)`），并放宽超规轨道限制（兼容 H.264 High Profile Level 5.0+、高参考帧等）；
  - 内置**有声黑屏看门狗**：若硬件解码器未选中视频轨或起播 2.8 秒内未输出首帧，自动切换为软件解码优先并从当前进度无缝续播；
  - `Surface` 生命周期按实例校验释放（`clearSurface`），彻底消除播中连续切片、旋转或切换「适应 / 铺满」导致的 `BufferQueue` 竞态黑屏。
- **大文件秒开与顺滑拖动**：发送端 HTTP 文件服务（端口 `8130`）升级为 `RandomAccessFile` / `FileChannel.position()` 的 **O(1) 随机寻址**，面对 `moov` 在文件末尾的数百 MB ~ 数 GB MP4 也能快速起播与 Seek。
- **统一手势与控件**：

| 操作 | 效果 |
|---|---|
| 单击画面 | 显示 / 隐藏控制栏（播放中自动收起） |
| 双击左 / 右 1/3 | 快退 / 快进 10 秒 |
| 双击中间 1/3 | 播放 / 暂停 |
| 左右滑动 | 调节播放进度 |
| 左 / 右半屏上下滑 | 调节屏幕亮度 / 媒体音量 |
| 长按画面（发送端本机） | 2.0× 倍速快进，松手恢复原倍速 |
| 扩展控制 | 0.5×–2.0× 倍速（两端同步）、适应/铺满比例切换、屏幕锁、旋转、接收端画中画（PiP） |

### 3. 接收端（CastKit Studio 界面）

- **宽屏平板自适应**：横屏/平板自动切换为左侧 `NavigationRail` 导航栏（总览 / 日志 / 设置），竖屏切换为底部导航。
- **原生 AirPlay 接收**：支持 H.264 / H.265 屏幕镜像、AAC / ALAC 音频、HLS 视频播控、PIN 配对、分辨率与帧率上限配置、调试悬浮统计。
- **LANCast 接收**：支持安卓端 H.264 画面 + AAC-LC 系统声音镜像接收、原画质 HTTP 视频播放与发送端视频库远程浏览。

### 4. 发送端（CastKit Studio 界面）

- **屏幕镜像**：`MediaProjection` + `MediaCodec` H.264 编码，支持 `720p / 1080p / 1440p / 跟随本机`、`15 / 24 / 30 / 60 fps`、动态码率调节与 **Android 10+ 系统声音同步采集传输**；支持 UDP 自动发现与手动地址直连、断线自动重连。
- **视频库与本地播放**：目录层级浏览、关键字搜索、排序记忆、缩略图三级缓存、断点续播记忆；开始投视频后手机自动切换为**专属投屏遥控台**（进度同步、倍速、音量、上下集无缝切换、停止投屏后衔接本地继续播放）。

---

## 本地构建

### 发送端（Windows / Linux / macOS）

发送端无原生 C/C++ 编译依赖，安装 JDK 17 与 Android SDK 后即可直接构建：

```bash
cd sender
./gradlew assembleDebug
# 产物：sender/app/build/outputs/apk/debug/app-debug.apk
```

### 接收端

接收端底层依赖 UxPlay / OpenSSL / FFmpeg / Oboe（需要 Android NDK 27 + CMake）：

```bash
bash tools/fetch-sources.sh        # 拉取原生依赖源码
bash tools/patch-sources.sh        # 应用离线化补丁
bash tools/setup-host-compat.sh    # （可选）aarch64 Linux 宿主兼容
bash tools/build-receiver.sh       # 构建接收端 APK
bash tools/build-sender.sh         # 构建发送端 APK
```

> **Windows 快速开发提示**：若已从历史包提取了 `arm64-v8a` 的预编译原生库（`libairplay_native.so`、`libc++_shared.so`、`liboboe.so`）放到 `receiver/app/src/main/prebuiltJniLibs/arm64-v8a/`，在 `receiver/` 目录直接运行 `.\gradlew.bat assembleDebug` 会自动复用预编译库，增量构建仅需十余秒。完整说明见 [`docs/BUILD.md`](docs/BUILD.md)。

---

## 已知限制

- 非 root 普通 App 无法直接作为系统 Miracast 或 Google Cast 接收端，因此 Android 投屏需配合 CastKit 发送端使用。
- AirPlay DRM 加密内容（如部分流媒体版权片源）无法镜像播放。
- 「投视频文件」与「共享视频库」需要发送端与接收端处于同一局域网且路由器未开启 AP 隔离；投送期间请保持发送端前台服务运行。
- Android 10+ 屏幕镜像的系统声音采集受系统策略限制，部分声明禁止录制的 App（如银行或受 DRM 保护应用）可能无法捕获音频。

---

## 仓库结构与文档

| 路径 | 说明 |
|---|---|
| `sender/` | Android 发送端 App（`com.dsh.castkit.sender`，Jetpack Compose + Material 3 + Media3） |
| `receiver/` | Android 接收端 App（`com.dsh.castkit.receiver`，基于 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) / UxPlay） |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 整体架构与数据流说明 |
| [`docs/LANCast-v1.md`](docs/LANCast-v1.md) | LANCast 局域网投屏与遥控协议 |
| [`docs/BUILD.md`](docs/BUILD.md) | 详细构建与离线镜像指南 |
| [`docs/TESTING.md`](docs/TESTING.md) | 测试与验收清单 |
| [`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md) | 第三方开源组件与许可 |
| `tools/`、`patches/` | 自动化构建与原生依赖补丁脚本 |
| `flutter_sender/` | 早期 Flutter 发送端原型归档 |

---

## 版本记录

### v2.0.0
- **前端全面重写**：发送端与接收端统一升级为 CastKit Studio 界面，新增发送端专属投屏遥控台与接收端宽屏 `NavigationRail` 适配。
- **接收端远程选片**：支持投屏时随时退出当前视频（自动回到该视频所在文件夹）继续浏览发送端全部目录并点播；播放页内「选片」侧边栏支持进入子目录与「返回上一级」。
- **屏幕镜像系统声音**：LANCast 协议与编解码管线新增系统音频同步传输（AAC-LC）。
- **播放引擎升级为 Media3 ExoPlayer**：两端统一升级为 ExoPlayer，新增硬件→软件解码自动回落、有声黑屏自检看门狗、`Surface` 实例校验释放与 O(1) HTTP Range 随机定位，彻底修复部分大体积/高规格视频「有声黑屏」问题；包体大幅缩减（发送端 19.2 MB / 接收端 31.4 MB）。

### v1.0.1
- 修复接收端投屏镜像花屏问题（避免解码器复用已销毁的 `Surface`）。
- 发送端支持投送中切集不断流。

---

## 贡献者与许可

- 发起与维护：**[THO48](https://github.com/THO48)**（详见 [`CONTRIBUTORS.md`](CONTRIBUTORS.md)）
- `receiver/` 遵循 **GPL-3.0**（继承自 `android-airplay-server` / UxPlay），详见 `receiver/LICENSE`。
- `sender/` 为本仓库自研代码，依赖 Jetpack Compose 与 AndroidX Media3（Apache-2.0）。
- 第三方组件清单见 [`docs/THIRD_PARTY.md`](docs/THIRD_PARTY.md)。
