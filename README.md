# CastKit —— 安卓投屏接收端 + 配套发送端

把一个 Android 手机变成投屏接收端：**iPhone/iPad/Mac 直接用系统「屏幕镜像」（AirPlay）投过来；
安卓手机装配套发送端 App 投过来**，分辨率 / 码率 / 帧率可自定义，界面适配手机、平板、横竖屏与画中画。

```
iPhone / iPad / Mac ──AirPlay（原生，无需安装 App）──┐
                                                   │
Android + CastKit 发送端 ──LANCast v1（TCP/H.264）──┼─→ 接收端 App ──→ 全屏播放
                                                   │
Android + CastKit 发送端 ──HTTP 原始视频文件────────┘   （「投视频文件」：接收端原生播放器直取原文件）
```

## 目录

| 路径 | 说明 |
|---|---|
| `receiver/` | 接收端 App（`com.dsh.castkit.receiver`），fork 自 [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)（GPL-3.0，UxPlay 内核） |
| `sender/` | 发送端 App（`com.dsh.castkit.sender`），自研，Apache-2.0 风格 |

> 发送端 UI 基于 **[Miuix](https://github.com/compose-miuix-ui/miuix)**（HyperOS 风格的 Compose 组件库，`top.yukonga.miuix.kmp:miuix:0.3.4`）：
> 卡片、列表行、下拉选择器、开关、底部导航、弹窗都用系统观感的实现，配色与字体交给 `MiuixTheme`。
> 选 0.3.4 是因为它对应 Kotlin 2.1.0 + Compose 1.7.3，与本项目工具链匹配（0.8.x 需要 Kotlin 2.3 + Compose 1.10）。
| `docs/BUILD.md` | 构建说明（含本机离线镜像与 aarch64 宿主兼容方案） |
| `docs/LANCast-v1.md` | 自研投屏协议（发送端 → 接收端） |
| `docs/TESTING.md` | 验收/测试矩阵 |
| `docs/THIRD_PARTY.md` | 第三方组件与许可 |
| `tools/` | 环境与构建脚本 |

## 快速开始（本机容器内）

```bash
bash tools/fetch-sources.sh        # 取原生依赖源码（走 gitclone/gitcode 镜像）
bash tools/patch-sources.sh        # 离线化补丁（OpenSSL 本地源码、Gradle 镜像、UxPlay 枚举移植）
bash tools/setup-host-compat.sh    # aarch64 宿主兼容（qemu 跑 aapt2、NDK 工具换本机 clang）
bash tools/build-receiver.sh       # 产出接收端 APK（内部会预构建 OpenSSL + 最小 FFmpeg）
bash tools/build-sender.sh         # 产出发送端 APK
bash tools/install-apk.sh receiver # 导出到 Download/DSHA 供安装
```

首次构建耗时较长（OpenSSL 3.4.4 + 最小 FFmpeg + UxPlay 交叉编译，约 20–45 分钟）；
之后增量构建 1–2 分钟。产物在 `out/`：`castkit-receiver-debug-app-debug.apk`（32 MB）、
`castkit-sender-debug-app-debug.apk`（9.5 MB）。

## 功能

**接收端**

- AirPlay 屏幕镜像（H.264/H.265 硬解）+ 音频（AAC-ELD/AAC-LC/ALAC）
- AirPlay 视频/音乐播放（HLS）、PIN 配对、Android TV 遥控器操作、画中画
- 目标分辨率（自动 / 720p / 1080p / 1440p / 4K / 自定义宽高）与最大帧率、过扫描开关
- 局域网投屏接收（LANCast v1，来自 CastKit 发送端）
- **投视频文件接收**：播放发送端共享的原视频文件（系统原生播放器，独立全屏播放页，
  播放/暂停、进度条、退出，按视频自身比例显示）
- 调试叠加层：实时分辨率/码率/FPS

**发送端**

- MediaProjection 采集 + MediaCodec H.264 编码
- 分辨率（480p/720p/1080p/1440p/跟随本机/自定义）、码率 1–20 Mbps、帧率 15/24/30/60
- **自动搜索接收端**（UDP 广播，无需手输 IP；AP 隔离时可展开「手动输入地址」兜底）
- 断线自动重连（最多 3 次），前台服务 + 通知停止
- **投视频文件**：只把选中的视频通过局域网 HTTP（带 `Range`，端口 8130）交给接收端，
  接收端用系统原生播放器直接播原文件 —— 原始画质、有声音、可拖进度，**不需要录屏授权**
- **底部导航分「投屏」「视频」两页**：投屏页管设备与参数，视频页管内容
- **内置视频库 + 播放器**：点视频**直接在本应用内播放**（不是只选中）；不想给读视频权限时可用
  「系统文件选择器」兜底。浏览器支持：
  - **按目录层级浏览**：默认只列最上级文件夹，进去才看到子文件夹与其中的视频（含子目录统计）
  - **排序**：按时间 / 名称 / 文件大小 / 视频时长，各支持正序与倒序，设置会记住
  - **预览大小**：四档可调，文件夹与视频分别控制每行个数（默认文件夹 3 列、视频 2 列）
  - **.nomedia 目录**：可选显示（这些目录 MediaStore 不索引，需要「所有文件访问」权限）
- 播放页两个专属按钮：
  - **切到横/竖屏**：只改本机方向，接收端画面方向不受影响（镜像投屏会据 `localPlayerActive`
    停止跟随本机旋转）
  - **投屏**：弹出设备列表，**点设备名立即开投**（无需再点一次「开始投屏」）；开投后本机自动暂停，
    避免手机与接收端同时出声
  - **投送中播放页变成遥控器**：进度条/时长/播放状态全部来自接收端（每秒回报），
    拖动进度条即同步 seek，播放/暂停也控制接收端；停止投送后本机进度自动对齐到接收端停下的位置
  - **收尾成对**：退出播放页 = 结束投送；接收端自己退出/播完，发送端 8 秒内也会自动结束投送

## 已知限制

- 普通非 root App **无法**接收 Miracast（系统「无线投屏」）与 Google Cast 镜像：前者需要平台签名权限
  `CONFIGURE_WIFI_DISPLAY`，后者需要 Google 认证的接收设备证书。因此安卓侧必须安装配套发送端 App。
- iOS 端码率由 iPhone 按网络自决，接收端只能指定分辨率/帧率（UxPlay 的 `-s/-fps` 机制）；
  真正可自定义码率的是安卓发送端。
- AirPlay 的 DRM 内容（如 Apple TV App）不支持。
- 仅构建/验证 arm64-v8a（本机与目标设备都是 arm64）。
- 内置视频库需要 `READ_MEDIA_VIDEO`（Android 13+）才能扫描；拒绝时仍可用系统文件选择器。
- 显示 `.nomedia` 目录需要「所有文件访问」权限（`MANAGE_EXTERNAL_STORAGE`）：这些目录 MediaStore
  完全不索引，只能靠文件系统遍历，Android 11+ 上没这个权限读不到。这类条目的时长与缩略图不在媒体库里，
  由 `MediaMetadataRetriever` / `ThumbnailUtils` 直接读文件得到（界面按需补探 + 少量批量预探）。
- **缩略图三级缓存**：内存 LRU → 磁盘缓存（`cacheDir/thumb_cache`，800 张上限、LRU 淘汰）→ 现抽帧；
  同时**限制并发解码为 2**、条目滚出屏幕即中断抽帧（`CancellationSignal`），所以快速拖动不会因
  一秒内冒出十几个抽帧任务而卡顿。
- **打开速度**：媒体库查询（快）与 `.nomedia` 遍历（慢）分两段——先出内容，再在后台补扫描；
  `.nomedia` 结果落磁盘缓存（5 分钟有效，手动「刷新」强制重扫），另有一份进程内缓存供切页复用。
  所以重开应用基本是"秒出"，不会每次都等全盘扫描。
- 「投视频文件」模式要求接收端能直接访问发送端的 HTTP 地址（同一 Wi-Fi、未被 AP 隔离）；
  能播哪些格式取决于**接收端**系统播放器；投送期间发送端要保持运行（前台服务）。
- 镜像模式下两端屏幕比例不同必然留黑边（例如 20:9 手机 → 3:2 平板）：镜像的是"整块屏幕"，
  要么留边、要么裁切。看视频请用「投视频文件」，它按视频自身比例播放。

## 许可

- `receiver/`：GPL-3.0（继承 UxPlay / playfair 逆向实现的许可）。
- `sender/`：本仓库自研代码。
- 详见 `docs/THIRD_PARTY.md`。
