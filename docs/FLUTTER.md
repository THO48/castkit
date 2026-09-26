# Flutter 版发送端骨架

> 目标：**界面交给你用 Flutter 写，能力继续用已经跑通的 Kotlin 引擎**。
> 这个目录不是可编译工程，而是"接到任意 Flutter 工程上就能用"的一整套文件 + 一键接线脚本。

## 为什么不是"全 Flutter"

发送端的核心能力全是 Android 原生 API，Flutter 里没有对应实现：

| 必须留在 Kotlin（**已经写好、已在真机跑通**） | 交给 Flutter |
|---|---|
| MediaProjection 录屏、MediaCodec H.264 编码、VirtualDisplay | 所有界面、动效、主题 |
| LANCast 协议（TCP 建连/推帧/遥控/心跳）、局域网 HTTP 文件服务 | 设备列表、状态展示 |
| MediaPlayer 播放控制、MediaStore/.nomedia 扫描、缩略图/时长 | 视频库浏览、播放器界面 |
| 录屏授权、读视频权限、所有文件访问、本机方向控制 | 权限引导流程的 UI |

所以结构是：**Flutter 画界面 ↔ MethodChannel/EventChannel ↔ Kotlin 引擎**。

## 目录

```
flutter_sender/
├── lib/
│   ├── castkit.dart           ← Dart 侧全部接口（你要用的就是它）
│   └── main.dart              ← 参考界面（把接口都用了一遍，可整份替换）
├── android/
│   ├── MainActivity.kt        ← FlutterActivity 宿主：录屏授权/权限/选文件
│   ├── CastKitBridge.kt       ← 通道层：方法 + 事件流 + 序列化
│   ├── engine/                ← Kotlin 引擎（原样搬过来，包名不变）
│   ├── engine_strings.xml     ← 引擎用到的 12 个字符串
│   └── manifest_patch.xml     ← 需要并入 AndroidManifest 的权限与服务
└── tools/setup-project.sh     ← 一键接线（见下）
```

## 三步接起来

```bash
# 1) 用你自己的 Flutter 生成工程（保证 Gradle/插件版本和你的 Flutter 匹配）
flutter create --org com.dsh --project-name castkit_sender --platforms android ~/castkit_sender

# 2) 一键接线：拷 Dart 接口 / 引擎 / 通道层，补权限与服务，加依赖
bash castkit/flutter_sender/tools/setup-project.sh ~/castkit_sender
#    国内网络可加 USE_ALIYUN=1 换成阿里云 Maven 镜像

# 3) 构建
cd ~/castkit_sender
flutter pub get
flutter build apk --debug
```

## 通道 API

全部封装在 `lib/castkit.dart` 的 `CastKit` 类里，只有两个事件通道 + 一个方法通道。

### 事件流（Kotlin → Dart）

| Dart | 内容 |
|---|---|
| `CastKit.stateStream` | `CastState`：阶段(idle/connecting/running/reconnecting/error)、模式(mirror/video)、目标地址、宽高/帧率/码率、**实测速率**、**接收端回报的播放进度** |
| `CastKit.receiverStream` | `List<CastReceiver>`：局域网自动发现的接收端（名称/IP/端口，15 秒无心跳摘除） |

### 方法（Dart → Kotlin）

| 方法 | 说明 |
|---|---|
| `getConfig()` | 读配置：预设/自定义尺寸/帧率/码率/目标地址，以及**已算好的"实际投出尺寸"** |
| `saveConfig(...)` | 存镜像参数 |
| `setTarget(host, port)` | 只改目标地址（快捷投屏用） |
| `startMirror()` | 开始镜像投屏，**内部拉起系统录屏授权**；返回是否同意 |
| `stopMirror()` | 停止镜像 |
| `pickVideo()` | 系统文件选择器选视频，返回 uri |
| `describeVideo(uri)` | 文件名/大小/MIME |
| `startVideoCast(uri, host, port, startPositionMs)` | 投视频文件；带上本机进度 → 接收端从同一位置接着播 |
| `stopVideoCast()` | 停止投送 |
| `videoControl(action, value)` | 遥控接收端：`actionPlay/actionPause/actionToggle/actionSeek` |
| `discoveryStart()/Stop()/Probe()` | 设备发现的生命周期与手动重搜 |
| `listVideos(folderPath, includeNoMedia, sortBy, ascending)` | 视频列表（按目录/排序） |
| `listFolders(folderPath, ...)` | 子文件夹（含数量/封面/聚合大小与时长）→ 层级浏览用 |
| `loadThumbnail(uri)` | 缩略图 JPEG 字节 → `Image.memory(...)`（内存+磁盘缓存、并发限流都在引擎里） |
| `loadDuration(uri)` | 时长（`.nomedia` 条目按需解析并缓存） |
| `hasMediaPermission()/requestMediaPermission()` | 读视频权限 |
| `canReadAllFiles()/openAllFilesSettings()` | 「所有文件访问」（显示 `.nomedia` 目录需要） |

## 引擎里已经替你做掉的事

这些是踩过坑攒下来的，换界面时**不要重复实现**：

- **旋转**：镜像投屏时屏幕旋转会重建编码器并 `VirtualDisplay.resize()+setSurface()`，失败会回滚、绝不中断投屏；编码器带"代次"，旧代次的迟到帧会被丢弃。
- **心跳**：无数据 5 秒发 ping，避免接收端 15 秒空闲超时断连。
- **收尾顺序**：结束投送时先发 `TYPE_STOP` 并留 350ms 送达时间，再关连接/停 HTTP 服务（直接 close 会 RST 丢包，接收端收不到停止）。
- **投视频文件**：单文件 HTTP 服务（带 Range，可拖进度），接收端用系统播放器播原文件，**不需要录屏授权**。
- **遥控与状态回报**：接收端每秒回报播放进度，发送端抖动 250ms 提交一次 seek（Miuix/Material 的 Slider 都没有 onValueChangeFinished）。
- **视频库**：两段式加载（媒体库先出内容、`.nomedia` 后台扫描 + 磁盘缓存 + TTL）、缩略图三级缓存与并发限流。

## ⚠️ 未验证部分（重要）

- **这套 Flutter 骨架没有在本机编译过**：本机是 aarch64，而 Flutter 官方只发布 **linux-x64** 的 Linux SDK；Dart VM 能在 qemu 下跑，但 `flutter` 工具的快照在 qemu 下直接退出 255，因此 `flutter create/pub get/build` 在本容器内都跑不了。
  → 请在**你自己的电脑**（或任何 x86_64 Linux/macOS/Windows）上执行上面的三步；`setup-project.sh` 的补丁逻辑是纯文本替换，不依赖本机。
- **Kotlin 引擎本身是验证过的**：它就是原生发送端现在用的那份代码（真机跑通），搬运时只做了一处改动 —— 通知里的"打开应用"从写死 `MainActivity` 改成 `packageManager.getLaunchIntentForPackage(...)`，这样引擎不再依赖宿主 Activity 类名。
- `lib/main.dart` 只是参考界面，不保证像素级好看；`lib/castkit.dart` 的接口与引擎逐条核对过签名。

## 和原生发送端的关系

`castkit/sender/` 那份原生（Kotlin + Compose + Miuix）**仍然是可用版本**，你可以：

1. 继续用它，等 Flutter 版做起来再切；
2. 或者把它当"行为参照"—— 任何交互不确定时，看它怎么做的（引擎逻辑两者完全一样）。

接收端不建议改 Flutter：它的核心是 native UxPlay（C++），UI 还有 TV 方向键适配，改过去收益很低。
