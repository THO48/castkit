# CastKit Sender 设计系统 · 第一步（Token + 线框）

> 交付步骤 1/3。本文档经确认后，才进入第 2 步（`Theme.kt` + 公共组件）与第 3 步（三个页面）。
>
> **状态**：待确认。本文档不包含任何已经落地的代码改动。

---

## 0. 前提与边界

| 项 | 决定 |
|---|---|
| 范围 | 只做 `sender`（`com.dsh.castkit.sender`）。`receiver` 不碰 |
| UI 底层 | **Jetpack Compose + Material 3**（`androidx.compose.material3:1.3.1`，随 Compose BOM `2024.12.01`）。**Miuix 依赖彻底移除** |
| 主题 | 浅色 / 深色跟随系统；**开启动态取色**（Android 12+） |
| 基础网格 | **4dp**（8/12/16/24/28/56 均为其整数倍） |
| 页面边距 | 16dp |
| 架构 | 单 Activity（已是），引入 ViewModel 做状态提升；进程级单例（`CastBus`/`VideoLibraryCache`/`VideoThumbnails`/`LanCastDiscovery`）不被搬进 VM |
| 不改动 | 遥控器模式、缩略图三级缓存、`.nomedia` 遍历、排序记忆、断线重连——只换渲染层 |
| 不改动 | UI 层之外的硬编码中文（`Prefs`/`VideoLibrary`/`CaptureSize`/服务层上屏字符串） |

### 0.1 关于"动态取色"与品牌色的关系（重要）

开启了动态取色后，**Android 12+ 上实际颜色由壁纸种子派生，`#6FA8F5` 不会出现**。这不是实现取舍，是 `dynamicLightColorScheme()/dynamicDarkColorScheme()` 的定义。

因此本文档第 1 节的色板是 **fallback 色板**，只在两种情况生效：

1. Android 12 以下（本应用 `minSdk = 26`，覆盖 Android 8/9/10/11 全部机型）；
2. 机型不支持动态取色，或 `dynamicColorScheme` 返回 null。

> 若希望 `#6FA8F5` 参与派生（成为真正的 seed），需要引入 `material-color-utilities` 自行生成 `ColorScheme`。本期**不做**（Q12=A 已定）。

---

## 1. 颜色 Token

### 1.1 品牌色

| 名称 | 值 | 说明 |
|---|---|---|
| BrandSeed | `#6FA8F5` | 品牌基准色，仅作参考锚点与 fallback 深色 primary |
| PrimaryLight | `#2A62B8` | 浅色主题 primary（压深以保证白字过 AA） |
| PrimaryDark | `#6FA8F5` | 深色主题 primary（浅蓝在深底上做强调） |
| OnPrimaryDark | `#0B1220` | 深色主题下 primary 上的文字（近黑，不是纯白） |

### 1.2 浅色 fallback 色板

| 角色 | 值 | 用途 |
|---|---|---|
| `primary` | `#2A62B8` | 主按钮填充、选中态、进度条已播部分 |
| `onPrimary` | `#FFFFFF` | 主按钮文字 |
| `primaryContainer` | `#DCE6F7` | 文件夹图标淡底、选中 Chip 淡底 |
| `onPrimaryContainer` | `#0B2A55` | 上者之上的文字 |
| `background` | `#F7F8FA` | 页面 1 / 页面 2 底色 |
| `onBackground` | `#1A1C1E` | 正文 |
| `surface` | `#FFFFFF` | 卡片、列表行 |
| `onSurface` | `#1A1C1E` | 卡片内正文 |
| `surfaceContainer` | `#F1F3F7` | 输入框底、次级容器 |
| `surfaceContainerHigh` | `#E8ECF2` | 抬升态（菜单、对话框） |
| `surfaceContainerHighest` | `#DDE3EC` | 最高抬升态 |
| `onSurfaceVariant` | `#5A5F6A` | **辅助信息**（12sp 分辨率·大小、说明文字） |
| `outline` | `#79747E` | **未选中 Chip / Switch 关闭态描边** |
| `outlineVariant` | `#C9CDD6` | 纯装饰分隔线（不承担识别职责） |
| `error` | `#B3261E` | 错误文字、错误图标 |
| `onError` | `#FFFFFF` | 错误填充上的文字 |
| `errorContainer` | `#F9DEDC` | 错误提示底色 |
| `onErrorContainer` | `#410E0B` | 上者之上的文字 |
| `success` | `#1E6B3C` | "投屏中"状态点与文字 |
| `onSuccess` | `#FFFFFF` | 成功填充上的文字 |
| `successContainer` | `#CFE9D6` | 成功提示底色 |
| `onSuccessContainer` | `#0B2C16` | 上者之上的文字 |

### 1.3 深色 fallback 色板

| 角色 | 值 | 用途 |
|---|---|---|
| `primary` | `#6FA8F5` | 主按钮填充、选中态、进度条已播部分 |
| `onPrimary` | `#0B1220` | 主按钮文字（**近黑，不是白色**） |
| `primaryContainer` | `#1B3A66` | 文件夹图标淡底、选中 Chip 淡底 |
| `onPrimaryContainer` | `#D3E3FB` | 上者之上的文字 |
| `background` | `#121212` | 页面 1 / 页面 2 底色 |
| `onBackground` | `#E6E6E6` | 正文 |
| `surface` | `#1E1E1E` | 卡片、列表行 |
| `onSurface` | `#E6E6E6` | 卡片内正文 |
| `surfaceContainer` | `#252525` | 输入框底、次级容器 |
| `surfaceContainerHigh` | `#2C2C2C` | 抬升态（菜单、对话框） |
| `surfaceContainerHighest` | `#333333` | 最高抬升态 |
| `onSurfaceVariant` | `#9AA0A8` | **辅助信息** |
| `outline` | `#938F99` | **未选中 Chip / Switch 关闭态描边** |
| `outlineVariant` | `#49454F` | 纯装饰分隔线 |
| `error` | `#FF7A6A` | 错误文字、错误图标 |
| `onError` | `#3B0906` | 错误填充上的文字 |
| `errorContainer` | `#8C1D18` | 错误提示底色 |
| `onErrorContainer` | `#F9DEDC` | 上者之上的文字 |
| `success` | `#6DD58C` | "投屏中"状态点与文字 |
| `onSuccess` | `#0B2C16` | 成功填充上的文字 |
| `successContainer` | `#1F5233` | 成功提示底色 |
| `onSuccessContainer` | `#CFE9D6` | 上者之上的文字 |

### 1.4 沉浸页固定色（不受主题影响）

| 名称 | 值 | 用途 |
|---|---|---|
| `ImmersiveScrim` | `#B3000000` | 播放器顶栏 / 底栏半透明黑底（沿用现有值） |
| `ImmersiveOnScrim` | `#FFFFFF` | 控制图标与主文字 |
| `ImmersiveTextSecondary` | `#B3FFFFFF` | 时间码、次要提示（70% 白） |
| `ScrubTrackInactive` | `#4DFFFFFF` | 进度条未播轨（30% 白） |
| `ScrubThumb` | `#FFFFFF` | 进度条圆形滑块 |
| `DurationBadge` | `#CC000000` | 缩略图右下角时长角标底（80% 黑） |

### 1.5 状态色映射（投屏状态）

| `CastPhase` | 颜色 |
|---|---|
| `IDLE` | `onSurfaceVariant` |
| `CONNECTING` | `primary` |
| `RUNNING` | `success` |
| `RECONNECTING` | `primary` |
| `ERROR` | `error` |

### 1.6 对比度实测矩阵（WCAG 2.1 相对亮度）

> 全部按 WCAG 2.1 公式手算。**AA 正文 = 4.5:1，AA 大字（≥18.66sp 或 ≥14sp Bold）= 3:1，UI 组件边界 = 3:1。**

**浅色**

| 前景 | 背景 | 对比度 | 判定 |
|---|---|---|---|
| `onPrimary` `#FFFFFF` | `primary` `#2A62B8` | **5.94:1** | ✅ AA 正文 |
| `primary` `#2A62B8` | `background` `#F7F8FA` | **5.59:1** | ✅ AA 正文 + 组件边界 |
| `onBackground` `#1A1C1E` | `background` `#F7F8FA` | **16.08:1** | ✅ AAA |
| `onSurface` `#1A1C1E` | `surface` `#FFFFFF` | **17.09:1** | ✅ AAA |
| `onSurfaceVariant` `#5A5F6A` | `background` `#F7F8FA` | **6.03:1** | ✅ AA 正文（12sp 可用） |
| `onSurfaceVariant` `#5A5F6A` | `surface` `#FFFFFF` | **6.40:1** | ✅ AA 正文 |
| `outline` `#79747E` | `background` `#F7F8FA` | **4.29:1** | ✅ 组件边界（需 3:1） |
| `error` `#B3261E` | `background` `#F7F8FA` | **6.15:1** | ✅ AA 正文 |
| `success` `#1E6B3C` | `background` `#F7F8FA` | **6.13:1** | ✅ AA 正文 |
| `onPrimaryContainer` `#0B2A55` | `primaryContainer` `#DCE6F7` | **11.29:1** | ✅ AAA |
| `onErrorContainer` `#410E0B` | `errorContainer` `#F9DEDC` | **12.77:1** | ✅ AAA |
| `onSuccessContainer` `#0B2C16` | `successContainer` `#CFE9D6` | **11.75:1** | ✅ AAA |
| `outlineVariant` `#C9CDD6` | `background` `#F7F8FA` | 1.50:1 | ⚠️ 仅装饰分隔线，不承担识别职责 |

**深色**

| 前景 | 背景 | 对比度 | 判定 |
|---|---|---|---|
| `onPrimary` `#0B1220` | `primary` `#6FA8F5` | **7.66:1** | ✅ AAA |
| `primary` `#6FA8F5` | `background` `#121212` | **7.67:1** | ✅ AA 正文 + 组件边界 |
| `primary` `#6FA8F5` | `surface` `#1E1E1E` | **6.55:1** | ✅ AA 正文 |
| `onBackground` `#E6E6E6` | `background` `#121212` | **15.14:1** | ✅ AAA |
| `onSurface` `#E6E6E6` | `surface` `#1E1E1E` | **13.36:1** | ✅ AAA |
| `onSurfaceVariant` `#9AA0A8` | `background` `#121212` | **7.25:1** | ✅ AA 正文（12sp 可用） |
| `outline` `#938F99` | `background` `#121212` | **5.91:1** | ✅ 组件边界 |
| `error` `#FF7A6A` | `background` `#121212` | **7.35:1** | ✅ AA 正文 |
| `error` `#FF7A6A` | `surface` `#1E1E1E` | **6.55:1** | ✅ AA 正文 |
| `success` `#6DD58C` | `background` `#121212` | **10.30:1** | ✅ AAA |
| `onPrimaryContainer` `#D3E3FB` | `primaryContainer` `#1B3A66` | **8.76:1** | ✅ AAA |
| `onErrorContainer` `#F9DEDC` | `errorContainer` `#8C1D18` | **7.17:1** | ✅ AAA |
| `outlineVariant` `#49454F` | `background` `#121212` | 2.00:1 | ⚠️ 仅装饰分隔线 |

**沉浸页（恒定）**

| 前景 | 背景 | 对比度 | 判定 |
|---|---|---|---|
| `ImmersiveOnScrim` `#FFFFFF` | scrim 合成后 ≈ `#000000` | **21:1** | ✅ AAA |
| `ImmersiveTextSecondary` `#B3FFFFFF` | scrim 合成后 ≈ `#000000` | **10.00:1** | ✅ AAA |
| `error` `#FF7A6A` | `#000000` | **8.24:1** | ✅ AAA |
| `primary` `#6FA8F5` | `#000000` | **8.60:1** | ✅ AAA |
| `ScrubTrackInactive` `#4DFFFFFF` | `#000000` | 4.61:1 | ✅ 组件边界 |

---

## 2. 字体 Token（Type Scale）

系统默认字体（Android: Roboto / Noto Sans CJK），不引入自定义 `FontFamily`。

| Token | size | weight | lineHeight | 映射 M3 `Typography` 角色 | 用途 |
|---|---|---|---|---|---|
| `screenTitle` | 20sp | W500 | 28sp | `titleLarge` | 页面大标题（"接收端"/"画面参数"/"内部存储"） |
| `sectionTitle` | 18sp | W500 | 26sp | `titleMedium` | 区块标题 |
| `cardTitle` | 16sp | W500 | 22sp | `titleSmall` | 卡片标题、设备名、文件名（列表行） |
| `bodyLarge` | 16sp | W400 | 24sp | `bodyLarge` | 主要正文 |
| `bodyMedium` | 14sp | W400 | 20sp | `bodyMedium` | 次要正文、列表摘要 |
| `supportSmall` | 12sp | W400 | 16sp | `bodySmall` | **辅助信息**（分辨率·大小、说明文字） |
| `label` | 14sp | W500 | 20sp | `labelLarge` | 按钮、Chip 文字 |
| `labelSmall` | 12sp | W500 | 16sp | `labelMedium` | 小标签、状态文字 |
| `microLabel` | 11sp | W500 | 14sp | `labelSmall` | 播放器图标下方极小文字、时长角标 |
| `emphasis` | 20sp | W700 | 28sp | （在 `screenTitle` 上覆写 weight） | 仅用于：投屏状态主文字、错误标题 |

### 2.1 粗细只能用 400 / 500 / 700

**不使用 W600（SemiBold）。** 这不是审美偏好，是字体可用性约束：

- Android 系统字体的 Roboto 字重集合是 **100 / 300 / 400 / 500 / 700 / 900**，**没有 600**。请求 W600 时系统会回退到最近的可用字重（通常是 700）或在部分设备上合成——结果就是"同一个 W600，在不同机器上粗细不一致"。
- 中文字形回退到 Noto Sans CJK，字重集合同样是 400 / 500 / 700（部分版本含 300/900），也没有 600。

因此本设计系统的层次靠 **400（正文）→ 500（标题/强调）→ 700（极少数的强强调）** 三档建立，而不是 400→600。W700 的使用被严格限制在上面 `emphasis` 一行里列出的三个场景。

---

## 3. 形状 Token（M3 `Shapes`）

| Token | 圆角 | 映射 M3 | 使用者 |
|---|---|---|---|
| `extraSmall` | 4dp | `Shapes.extraSmall` | 时长角标 |
| `small` | 8dp | `Shapes.small` | 输入框内小容器 |
| `medium` | **12dp** | `Shapes.medium` | **视频缩略图** |
| `large` | **16dp** | `Shapes.large` | **卡片、对话框、下拉菜单** |
| `extraLarge` | **28dp** | `Shapes.extraLarge` | 吸底主按钮、设备卡片选中态 |
| `full` | 50% | `Shapes.full` | **按钮、FilterChip、Switch 轨道、进度条 thumb** |

> M3 的 `Button` / `TextButton` / `FilterChip` 默认形状就是 `Shapes.full`（胶囊形），因此"禁止方正默认按钮"是**默认满足**的，不需要额外覆写。M3 中唯一圆角为 0 的常见控件是 `TextField` 的 `OutlinedTextField` 边框——本设计系统统一用 **`FilledTextField` 变体**（`TextField` + 自定义 `colors`），其形状取 `Shapes.extraSmall`（4dp）顶部圆角，是 M3 规范形态，不属于"方正默认按钮"。

---

## 4. 间距与尺寸 Token

### 4.1 间距（基础网格 4dp）

| Token | 值 | 用途 |
|---|---|---|
| `space1` | 4dp | 图标与文字、角标内边距 |
| `space2` | 8dp | 紧邻元素（网格横纵间距、Chip 间距） |
| `space3` | 12dp | **组件间距**（同组内控件之间） |
| `space4` | 16dp | **页面边距**、卡片内边距、**内容行间距** |
| `space5` | 20dp | 卡片之间 |
| `space6` | 24dp | **区块间距**（"接收端" ↔ "画面参数"） |
| `space7` | 32dp | 大区块留白（空状态上下） |

### 4.2 尺寸

| Token | 值 | 说明 |
|---|---|---|
| `minTouchTarget` | **48dp** | 所有可点击区域的下限，无例外 |
| `topBarHeight` | 64dp | M3 `TopAppBar` 默认 |
| `bottomBarHeight` | 80dp | M3 `NavigationBar` 默认 |
| `primaryButtonHeight` | **56dp** | 吸底"开始投屏" |
| `iconButtonSize` | 48dp | 顶栏图标点击区（图标本体 24dp） |
| `thumbnailAspect` | 16:9 | 视频缩略图 |
| `gridGap` | 8dp | 网格横纵间距 |
| `listRowMinHeight` | 56dp | 列表行 |

### 4.3 播放器专属尺寸

| Token | 值 | 说明 |
|---|---|---|
| `scrubTrackHeight` | **3dp** | 进度条轨高 |
| `scrubThumbSize` | **12dp** | 进度条圆形滑块 |
| `playerPrimaryButton` | **64dp** | 播放/暂停按钮直径 |
| `playerSecondaryIcon` | 24dp | 次级图标本体（点击区仍 48dp） |
| `playerBottomBarPadding` | 16dp 横 / 12dp 纵 | |
| `overlayAutoHideDelay` | **3000ms** | 控制栏自动隐藏 |
| `overlayFadeDuration` | 200ms | 淡入淡出 |

---

## 5. 层级（Elevation）策略

**浅色**：底色 `#F7F8FA`，卡片 `#FFFFFF` + 阴影区分。

| 层级 | 实现 |
|---|---|
| 页面底 | `background` `#F7F8FA`，无阴影 |
| 卡片 / 列表行 | `surface` `#FFFFFF` + `tonalElevation = 0dp` + `shadowElevation = 1dp` |
| 抬升（下拉菜单、Popover） | `surfaceContainerHigh` `#E8ECF2` + `shadowElevation = 3dp` |
| 对话框 | `surfaceContainerHigh` `#E8ECF2` + `shadowElevation = 6dp` |

**深色**：阴影在深底上不可见，**改用表面提亮阶梯**，阴影一律为 0。

| 层级 | 实现 |
|---|---|
| 页面底 | `background` `#121212` |
| 卡片 / 列表行 | `surface` `#1E1E1E` |
| 抬升（下拉菜单、Popover） | `surfaceContainerHigh` `#2C2C2C` |
| 对话框 | `surfaceContainerHighest` `#333333` |

> 深色下 `#121212` → `#1E1E1E` 的表面差只有 **1.12:1**，人眼可辨但很弱。因此**深色模式下的卡片在需要明确边界时必须补一条 `outlineVariant` `#49454F` 的 1dp 描边**（2.00:1），而不是靠那点提亮。这是"深色用表面提亮"这条规则的执行细则，不是例外。

---

## 6. 动效

| 场景 | 规格 |
|---|---|
| 播放器控制栏显隐 | `AnimatedVisibility` + `fadeIn/fadeOut`，200ms，`LinearOutSlowInEasing` / `FastOutSlowInEasing` |
| 控制栏自动隐藏 | 显示后 **3000ms** 无交互即隐藏；点击画面立即切换 |
| 页面/区块进入 | M3 默认 |
| Chip 选中 | M3 `FilterChip` 默认（无需覆写） |
| 投屏状态点 | 状态变化时颜色 `animateColorAsState`，300ms |
| 加载 | `CircularProgressIndicator`（M3），接收端搜索中使用 |

---

## 7. 图标

- 继续使用 `androidx.compose.material:material-icons-extended:1.7.6`（不引入 Material Symbols 字体图标）。
- 全部走 `Icons.*`（`AutoMirrored` 用于返回箭头）。
- 图标本体 24dp，**点击区一律 48dp**。
- 播放器图标在 scrim 上时统一 `tint = #FFFFFF`，激活态用 `primary` `#6FA8F5`。
- **不放置无实际功能的图标**（这条规则直接来自"移除搜索图标"的决策，同样适用于顶栏的"更多 ⋮"）。

---

## 8. 页面线框

### 8.0 页面结构（先说清楚，避免误读）

**是三个页面，不是一个页面。** 具体归属：

| # | 页面 | 类名 | 归属 |
|---|---|---|---|
| 1 | 文件 / 视频列表页 | `FileListScreen` | 底部导航 **「视频」Tab** |
| 2 | 投屏设置页 | `CastSettingsScreen` | 底部导航 **「投屏」Tab** |
| 3 | 视频播放器 | `VideoPlayerScreen` | **覆盖层**（不是 Tab），从页面 1 点视频进入 |

- 页面 1 与页面 2 是**两个平级的 Tab**，各有独立的顶栏、独立的滚动位置，**互不复用内容**。
- 页面 3 不是导航目的地，而是挂在 `MainScreen` 上的一个覆盖层 `Box` —— 这样页面 1 的
  文件夹层级与滚动位置在播放期间不会被销毁（沿用改造前的行为，属于"1:1 保留"的一部分）。
- **下面的线框里，页面 1 与页面 2 都画了同一条底部导航栏**（两者都是 Tab）；页面 3 没有。
- 第 6 节提到的 `ComponentGallery`（debug 源集）是把组件摊在一起的**目录**，与页面结构无关 ——
  它把两个页面的零件画在同一屏，只是为了方便一次看完 9 个组件的渲染效果，它不进 release 包。

### 8.1 页面 1 —— 文件 / 视频列表页

```
┌──────────────────────────────────────────────────┐
│ ←  内部存储（或当前文件夹名）        [排序] [≡]  │  TopAppBar 64dp
├──────────────────────────────────────────────────┤  右侧仅 2 个图标：排序直连，其余进溢出
│  ⓘ 仅可访问部分视频  [授予全部访问]              │  条件显示（部分授权）
│  ⚠ .nomedia 目录未索引的提示                     │  条件显示
├──────────────────────────────────────────────────┤
│  文件夹                                          │  sectionTitle 18/500
│  ◯    ◯    ◯                                     │  等高网格，默认 3 列
│ 相机  下载  影片                                  │  圆形图标 + primaryContainer 淡底
│ 12个  8个   31个                                  │  12sp onSurfaceVariant
├──────────────────────────────────────────────────┤
│  视频                                            │  sectionTitle 18/500
│  ┌────────┐ ┌────────┐                           │  等高网格，默认 2 列，8dp 间距
│  │        │ │        │                           │  缩略图 16:9，圆角 12dp
│  │    ▮04:12│ │        │                           │  时长角标：4dp 圆角，
│  └────────┘ └────────┘                           │   #CC000000 底 + 白字 11sp，右下角
│  青海湖环湖…  海边日落…                           │  cardTitle 16/500，单行截断
│  1080p·245MB  720p·88MB                          │  supportSmall 12sp，onSurfaceVariant
├──────────────────────────────────────────────────┤
│      [ 投屏 ]              [ ▣ 视频 ]            │  M3 NavigationBar（本页是「视频」Tab）
└──────────────────────────────────────────────────┘
```

**空状态**（文件夹与视频都为空）：

```
┌──────────────────────────────────────────────────┐
│                                                  │
│                  ┌─────────┐                     │
│                  │  🎬     │                     │  64dp 图标，onSurfaceVariant
│                  └─────────┘                     │
│                   暂无视频                        │  cardTitle 16/500
│         把视频放进手机，或换个文件夹看看          │  supportSmall 12sp
│                                                  │
└──────────────────────────────────────────────────┘
```

**顶栏溢出菜单（≡）内容**

| 项 | 控件 | 说明 |
|---|---|---|
| 排序：时间 / 名称 / 大小 / 时长 | 单选列表 + 升降序开关 | 从顶栏直连图标也能打开同一弹窗 |
| 预览大小 | 四档（最小 5/3、小 4/3、中 3/2、大 2/1） | **功能 1:1 保留**，本地态搬进 VM |
| 显示 .nomedia 目录 | Switch | 功能 1:1 保留 |
| 刷新 | 菜单项 | 强制重扫 |

> **搜索功能已移除**（Q21）。顶栏不放搜索图标，不引入全库遍历、路径显示与补扫状态问题。

**文件夹导航**：点文件夹进入 → 顶栏左侧出现返回箭头，标题变为当前文件夹名 → `BackHandler` 逐级返回（沿用现有行为）。

---

### 8.2 页面 2 —— 投屏设置页

```
┌──────────────────────────────────────────────────┐
│  投屏                                            │  TopAppBar（底栏 Tab 之一）
├──────────────────────────────────────────────────┤
│                                                  │
│  接收端                                          │  screenTitle 20/500
│  ┌────────────────────────────────────────────┐  │  卡片 large 16dp
│  │ ● 投屏中 · 192.168.1.23:8123               │  │  状态行（原 CastStatusCard 合并至此）
│  │   1920×1080 @30fps 8Mbps · 实测 7.9Mbps    │  │  supportSmall 12sp
│  ├────────────────────────────────────────────┤  │
│  │ ┌────────────────────────────────────────┐ │  │  选中态：primary 1.5dp 描边
│  │ │ 客厅电视                  192.168.1.23 │✓│  │  + primaryContainer 淡底
│  │ └────────────────────────────────────────┘ │  │
│  │   卧室平板                192.168.1.31     │  │  未选中：无边框，仅 surface
│  │   ⟳ 正在搜索…                              │  │  搜索中：M3 转圈 + labelSmall
│  │   手动输入地址                              │  │  TextButton（无边框）
│  │   [ 192.168.1.23 ]  [ 8123 ]               │  │  展开后才是两个输入框
│  └────────────────────────────────────────────┘  │
│                                                  │  space6 24dp
│  画面参数                                        │  screenTitle 20/500
│  ┌────────────────────────────────────────────┐  │
│  │ 分辨率                                     │  │  label 14/500
│  │ (720p)(1080p)(1440p)(跟随本机) →           │  │  FilterChip 组，横向滚动
│  │ 有效尺寸 1920×1080                         │  │  supportSmall 12sp（实时）
│  │ 已限制为屏幕尺寸                            │  │  条件显示，error 色
│  │                                            │  │
│  │ 保持屏幕比例                        ( ●──) │  │  M3 Switch
│  │ 开启：按屏幕比例推导长边，不变形            │  │  supportSmall 12sp
│  │                                            │  │
│  │ 帧率                                       │  │
│  │ (15fps)(24fps)(30fps)(60fps)               │  │  FilterChip 组
│  │                                            │  │
│  │ 码率                            8 Mbps      │  │
│  │ ─────────●──────────────────────           │  │  M3 Slider
│  └────────────────────────────────────────────┘  │
│                                                  │
├──────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────┐  │  吸底，56dp，圆角 28dp
│  │              开始投屏                       │  │  primary 底 + onPrimary 文字
│  └────────────────────────────────────────────┘  │
│   [投屏]                    [视频]               │  M3 NavigationBar
└──────────────────────────────────────────────────┘
```

**关键变化**

1. **原 `CastStatusCard` 不再独立**，合并进"接收端"卡片顶部（Q: 已同意）。
2. **"手动输入地址"从带边框的整行改成 `TextButton`**（无边框文字按钮）。
3. **分辨率与帧率都是横向可滚动的 `FilterChip` 组**（Q20 = Chip 组）。因为可以滚动，**"跟随本机"这四个字不需要缩短**，保留原文案。
4. **`保持屏幕比例` 的说明文字必须写清楚它对预设也生效**："开启：数字表示短边，长边按本机屏幕比例推导；关闭：按标准 16:9 投出（画面会被拉伸）"。
5. 吸底按钮 `56dp` 高、圆角 `28dp`、`primary` 填充 + `onPrimary` 文字。**浅色下是白字，深色下是近黑字**（见 §1.6）。

> **CUSTOM 已移除**（Q20 原话"不需要自定义"）。这是本次重构**唯一**的功能削减，详见 §10。

---

### 8.3 页面 3 —— 视频播放器

**恒定纯黑 `#000000`，不受浅色/深色模式影响。**

#### 本机播放态

```
┌──────────────────────────────────────────────────┐
│ ←  青海湖环湖骑行 4K.mp4                          │  透明顶栏 + scrim
│                                          ▒▒▒▒▒▒  │  statusBarsPadding
├──────────────────────────────────────────────────┤
│                                                  │
│                                                  │
│                  [ 视频画面 ]                    │  SurfaceView，按视频比例居中
│                                                  │  16:9 或 9:16，letterbox
│                                                  │
│                                                  │
├──────────────────────────────────────────────────┤
│  ●───────────────────────────────────────        │  进度条：轨 3dp，thumb 12dp
│  04:12                              12:34        │  12sp，左已播 / 右总时长
│                                                  │
│   ⟳    ⏪10    ( ▶ )    ⏩10    ⬆                │  底栏控制区
│  切到   后退    播放     前进    投屏             │  次级点击区 48dp / 图标 24dp
│  横屏   10秒            10秒                     │  主按钮 64dp
│                                                  │  图标下方 11sp microLabel
└──────────────────────────────────────────────────┘
```

**顶部栏**：`返回` + 文件名（**marquee 跑马灯**，单行）。**不放"更多 ⋮"**——理由见 §10 偏差 2。

**顶部栏正下方**：`切到横屏 / 切到竖屏` 悬浮钮（胶囊形 scrim 底 + `ScreenRotation` 24dp + 文字）。
手动切过的方向会**持久记住**（`Prefs.player_orientation`，两态、没有"跟随系统"档也没有清除入口）：
进播放页时贴回来，退出播放页（`onDispose`）恢复 `SCREEN_ORIENTATION_UNSPECIFIED` 交还系统。
用 `SENSOR_LANDSCAPE / SENSOR_PORTRAIT` 而不是锁死 `LANDSCAPE / PORTRAIT`，保留同方向内正反都能翻。

**底部控制区**（进度条 + 时间码 + 五格控制排，每个动作全页只出现一次）：

| 位置 | 控件 | 点击区 | 图标 |
|---|---|---|---|
| 左 | 上一个视频 | 48dp | `SkipPrevious`；已在列表首个时降到 38% 不透明度且不响应点击 |
| 中左 | 后退 10 秒 | 48dp | `Replay10` |
| 中 | **播放 / 暂停** | **64dp** | `PlayArrow` / `Pause`（32dp），`#6FA8F5` 实心圆 + `#0B1220` 图标 |
| 中右 | 前进 10 秒 | 48dp | `Forward10` |
| 右 | 下一个视频 | 48dp | `SkipNext`；已在列表末个时同样置灰 |

**底部控制区左上方**：`投屏 / 停止投送` 悬浮钮（同上胶囊样式，`Cast` / `Stop`，投送中 `#6FA8F5`）。

> 两个悬浮钮（切方向、投屏）与底栏在**同一个 `AnimatedVisibility`** 里，所以一起淡入淡出；
> 放在底栏外面是因为它们不属于"播放本身"的动作，挤进同一排会和播放控制抢注意力。
> 悬浮钮自带 scrim 背景（底栏有整条 scrim 垫底，它们没有，压在亮画面上会看不清）。

**交互**：

- 点击画面任意处切换控制栏显隐（`indication = null`，无涟漪）。
- **长按画面 = 3× 快进**：按住期间保持，画面中央显示「3× 快进中」的胶囊提示，松手立刻回 1×。
  用 `detectTapGestures` 的 `onLongPress`（开始）+ `onPress` 里的 `tryAwaitRelease()`（松手）配对实现——
  这个 API 只有 `onPress` 拿得到 `awaitRelease`，`onLongPress` 是普通 lambda，所以必须拆两半。
  **只在投送时生效**：开始投屏后本机只是遥控器，长按不会有任何反应（也不显示提示）。
- **左半屏上下滑 = 亮度、右半屏上下滑 = 系统音量**，无级连续，画面中央显示图标 + 进度条 + 百分比。
  实现方式是**单独铺一层子节点**（`PlayerAdjustLayer`）而不是加进上面那个手势里：子节点先拿到事件，
  但只在超过 touch slop 之后才 consume，所以快速点击仍归父层、竖向拖动不会被误判成单击。
  控制栏是同一个 Box 里更靠后的兄弟节点，命中优先，所以在进度条上横向拖动不会被这层抢走。
- **两个都是系统级的，退出播放不还原**（原始需求里亮度本来是"仅播放页"，实测下来用户要的是系统级）。
  亮度优先走 `Settings.System.putInt(SCREEN_BRIGHTNESS)`（需要 `WRITE_SETTINGS` 特殊权限）；
  没权限时退回窗口级 `Window.screenBrightness` —— **实测小米 ROM 会把这个窗口值写进系统设置**，
  所以效果同样是"改系统亮度"，只有在窗口级语义严格生效的 ROM 上才会退化成"仅播放页"。
  这里刻意**不做**任何退出还原：早期版本为了"亮度仅本页"写过一套补偿（回写原值 + 隔 250ms
  交还控制权 + 读回校正），既复杂又会在最低档产生"退出后 +1"的跳变，需求改成系统级之后全部删除。
- **音量**走 `AudioManager.setStreamVolume(STREAM_MUSIC)`。
  「无级」有平台上限：该接口只吃整数档，本机实测媒体音量 151 档（`cmd media_session volume
  --stream 3 --get` → `[0..150]`），手感连续；只有 15 档的机器会一格一格跳。
  亮度的系统亮度设置是 0..255 档，同理。
- 控制栏显示后 **3 秒**无操作自动隐藏（`AnimatedVisibility` 淡入淡出 200ms）。
- 拖动进度条时**不**自动隐藏；松手后重新计时。
- 「上一个 / 下一个」按**打开播放页时那个文件夹的显示顺序**（含当时的排序）走；
  切换前会先收掉正在进行的投送，否则接收端还在放上一个文件。
- 常驻提示语删除，改为 **Snackbar**（见下）。

#### 投送中（遥控器态）—— 本次重做

```
┌──────────────────────────────────────────────────┐
│ ←  青海湖环湖骑行 4K.mp4                          │
│                                          ▒▒▒▒▒▒  │
├──────────────────────────────────────────────────┤
│                                                  │
│                     ┌──────┐                     │  72dp 图标，primary
│                     │  📺  │                     │
│                     └──────┘                     │
│                  正在投送到                       │  supportSmall 12sp
│                   客厅电视                        │  sectionTitle 18/500
│              控制的是接收端的播放                 │  supportSmall 12sp
│                                                  │
│                                                  │
├──────────────────────────────────────────────────┤
│  ●───────────────────────────────────────        │  进度/时长/播放状态
│  04:12                              12:34        │  全部来自接收端（每秒回报）
│                                                  │
│   ⟳    ⏪10    ( ⏸ )    ⏩10    ⏹                │  播放键变暂停键
│  切到   后退    暂停     前进    停止             │  投屏键变停止投送
│  横屏   10秒            10秒                     │
└──────────────────────────────────────────────────┘
```

**关键变化**：进入投送态时**隐藏本机 `SurfaceView`**，改渲染上面这块沉浸态。原因（现有实现的缺陷）：当前实现只调用 `player.pause()`，本机 `SurfaceView` 仍留在视图树里，用户看到的是**本机视频暂停住的最后一帧**，叠加控制接收端的遥控控件——画面是死的，且容易被误读成"投屏失败"。

**Snackbar 替代常驻提示**：

| 时机 | 文案 |
|---|---|
| 进入投送态 | "已开始投送到 客厅电视，本机已暂停" |
| 退出投送态 | "已停止投送，进度已对齐到 04:12" |
| 点击切方向 | 仅第一次："切到横屏只改本机方向，接收端画面不受影响" |

---

## 9. WCAG AA 自检结论

### 9.1 本设计系统

第 1.6 节所有承担**识别职责**的前景/背景组合均 ≥ 4.5:1（正文）或 ≥ 3:1（组件边界/大字）。唯一的两个低于 3:1 的值是 `outlineVariant`，它们在浅色/深色下都**只用于装饰性分隔线**，不承担"标识某个组件或状态"的职责，因此不受 WCAG 1.4.11 约束。

### 9.2 顺带发现的**现有代码违规**（不在本次改动范围，仅记录）

| 位置 | 问题 | 实测 |
|---|---|---|
| `ui/Theme.kt:31` `CastKitErrorLight = #D94838` | 错误文字用在 `#F7F7F7` 底上 | **4.00:1** ❌ 低于 AA 正文 4.5:1 |
| Miuix 浅色 `primary = #3482FF` + 白字 | 主按钮文字 | **3.63:1** ❌ |
| Miuix 深色 `primary = #277AF7` + 白字 | 主按钮文字 | **4.03:1** ❌ |
| 播放器未播轨（Miuix `Slider` 默认） | 组件边界 | 未达 3:1 |

本设计系统通过把浅色 `primary` 压深到 `#2A62B8`（5.94:1）并把深色 `onPrimary` 改成近黑来解决第 2、3 条；通过自定义 `scrubTrackInactive` 解决第 4 条；错误色换成 `error` token 解决第 1 条。

### 9.3 提醒：对比度不随动态取色自动变好

Android 12+ 由壁纸派生的 `ColorScheme` 由系统保证其自身的 `on*` 配对满足对比度要求，**但 `error`/`success` 这两个语义色在动态取色下也由系统给出**（`success` 在 M3 中根本不存在，仍由我们固定提供 `#1E6B3C` / `#6DD58C`）。所以深色/浅色的 `success` 在动态取色下**不会**跟着壁纸变，这是有意的：状态色的语义一致性比配色统一更重要。

---

## 10. 与原始需求的偏差清单（需你确认）

| # | 原始需求 | 实际决定 | 原因 |
|---|---|---|---|
| 1 | 主色沿用"淡蓝色 `#6FA8F5`" | 浅色 primary = `#2A62B8`，深色 primary = `#6FA8F5` | `#6FA8F5` + 白字只有 **2.44:1**，连 3:1 都不到。Q6 已定 |
| 2 | 播放器顶栏右侧放"投屏"和"更多（⋮）" | 顶栏只留**返回 + 文件名**；投屏放在底部控制区**左上方**的悬浮钮 | 全应用只有"投屏"这一个次要动作，⋮ 里没有任何真内容——放一个空菜单正是"装饰性图标"，与你移除搜索图标的判断标准一致。若以后有真内容（如字幕/音轨）再加 |
| 3 | 播放器底部"切方向"和"投屏" | 各出现**一次**（当前实现里顶栏和底栏各有一份，一屏 4 个重复入口） | 去冗余 |
| 3b | （后续追加）播放器要能切上一个 / 下一个视频 | 底栏扩成五格：`上一个` · `↺10` · `暂停` · `↷10` · `下一个`；两个悬浮钮位置不变 | 五格宽度 48×4 + 64 = 256dp，加两端 16dp = 288dp，360dp 窄屏仍有余量（与原五格排布同一笔账）。置灰而不是隐藏，控制排的格数不随播放位置跳 |
| 4 | 分辨率"改成分段控制器" | 改成**横向可滚动的 `FilterChip` 组** | 4 档分段控制器在 360dp 屏上每段仅 74dp，"跟随本机"（56dp）+ 内边距 = 80dp **溢出**；Chip 可滚动，无此限制。Q20 已定 |
| 5 | 文件页顶栏"搜索 + 排序"图标 | **不放搜索**；右侧 = 排序直连 + 溢出菜单（≡） | Q21 已定 |
| 6 | 视频列表"两列瀑布流" | **等高网格**，默认视频 2 列 / 文件夹 3 列，四档列数设置 1:1 保留 | 缩略图固定 16:9，瀑布流高度全等，只有测量开销没有视觉收益。Q16 已定 |
| 7 | 分辨率档位含"自定义" | **移除 CUSTOM** | ⚠️ **这是本次唯一的功能削减。** Q20 原话"不需要自定义"。代价：用户无法再投非预设尺寸（如 900p）。`README.md` 需同步修改；存量用户若曾选过 CUSTOM，`runCatching` 会自动回落到 720p，不会崩。**如果你改主意，改回 Chip 组加第 5 个"自定义" Chip 几乎零成本**——Chip 组本来就要横向滚动 |
| 8 | 底部导航 | 用 M3 `NavigationBar` 重写（当前 Miuix `NavigationBar` 的颜色全部硬编码、无法重制） | Q3 已定 |
| 9 | 各页"深色模式" | 页面 1/2 跟随主题；页面 3 恒纯黑 | Q5 已定 |
| 10 | 标题 20/18、正文 16/14、辅助 12/11 | 完全满足，见 §2 | — |
| 11 | "8dp 网格 + 12/16dp 间距" | 基础网格 **4dp**，8/12/16/24/28/56 全为整数倍 | 12 不是 8 的倍数，两者无法同时成立 |
| 12 | "无边框 + 阴影区分层级" | 浅色用阴影；深色改用表面提亮 + 必要时 1dp `outlineVariant` 描边 | 深色底上阴影不可见 |
| 13 | 强调粗细对比 | 只用 **400 / 500 / 700**，不用 600 | 系统字体（Roboto / Noto Sans CJK）没有 W600，请求它会得到不一致的渲染 |

---

## 11. 下一步

确认本文档后：

- **第 2 步**：`Theme.kt`（`ColorScheme` + `Typography` + `Shapes`，含动态取色与 fallback 分支）+ 公共组件（`CastKitScaffold` / `SectionHeader` / `OptionChipRow` / `CastStatusRow` / `VideoTile` / `FolderTile` / `EmptyState` / `SettingRow` / `PrimaryBottomButton`），并移除 Miuix 依赖、把 `UiPreview.kt` 按新约定重写。
- **第 3 步**：`FileListScreen` / `CastSettingsScreen` / `VideoPlayerScreen` 三个页面的完整代码，每页就地附浅色 + 深色 `@Preview`，跨状态/尺寸的组合预览放 `src/debug`。
