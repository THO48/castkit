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
| `ImmersiveScrim` | `#B3000000` | 只给**长按快进**那个居中提示垫底（顶栏/底栏/悬浮钮都已去掉底色，见 §8） |
| `ImmersiveOnScrim` | `#FFFFFF` | 控制图标与主文字 |
| `ImmersiveShadow` | `#000000` | 浮层文字与图标的**投影色**（见 §8「白字 + 投影」） |
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
| `playerPrimaryButtonCompact` | **56dp** | 矮屏（横屏手机）紧凑档的播放/暂停直径 |
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

**顶部栏正下方**：`切到横屏 / 切到竖屏` 悬浮钮（**只有 `ScreenRotation` 24dp 图标，不带文字**；
方向本身看画面朝向就知道，文字会跟标题抢读。无底色，白色 + 投影）。
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

**底部控制区左上方**：`投屏 / 停止投送` 悬浮钮（同上的无底色样式，`Cast` / `Stop`，投送中 `#6FA8F5`；
只有投送中才带「停止」两个字）。

> 两个悬浮钮（切方向、投屏）与底栏在**同一个 `AnimatedVisibility`** 里，所以一起淡入淡出；
> 放在底栏外面是因为它们不属于"播放本身"的动作，挤进同一排会和播放控制抢注意力。
> 两个钮**没有底色**：单独垫一块胶囊黑底反而成了整个播放页最显眼的东西，
> 所以和底栏用同一套「白色 + 投影」来保证压在亮画面上仍可读（见 §8）。

**矮屏（横屏手机）紧凑档**：可用高度 < `COMPACT_HEIGHT_THRESHOLD_DP = 480dp` 时底栏换紧凑排布。

| | 常规档（竖屏 / 平板） | 紧凑档（横屏手机，实测 384dp） |
|---|---|---|
| 时间码 | 独立一行，进度条下方两端 | 与进度条**同一行**的两端，`labelSmall` |
| 进度条触摸行高 | 48dp | 40dp（`COMPACT_SCRUB_HEIGHT`） |
| 播放/暂停直径 | 64dp | 56dp（`CastKitSizes.playerPrimaryButtonCompact`） |
| 纵向内边距 / 行间距 | `space3` / `space2` | `space2` / `space1` |

判据取自 `configuration.screenHeightDp`，实测命中范围：横屏手机 384dp（紧凑）、竖屏手机 853dp（常规）、平板横屏远大于阈值（常规）。
不这么分的话横屏底栏约 172dp、加上悬浮钮与手势条内边距能到 262dp，会和顶部那条（顶栏 + 切方向钮 ≈ 154dp）叠在一起——
真机上表现为两排悬浮钮直接压住。

**顶栏与底栏都是全透明**（`Color.Transparent`，不铺任何 scrim 垫底）。

横屏手机只有 384dp 高，实测各段占位是：系统状态栏 45dp + 标题栏 64dp + 旋转钮 48dp + 投屏钮 48dp
+ 底栏（进度条行 32dp + 控制排 48dp + 内边距）≈ 92dp + 手势条 20dp ——
**加起来约 349dp / 384dp ≈ 91%**。铺 scrim 的话这两条就是压掉九成屏高的两块黑板，
所以改成只留控件本身浮在画面上。高度上能压的只有内边距与进度条触摸行（48→32dp），
再往下就是 48dp 最小点击区，压不动了 —— 真正的解法是去掉背景色而不是继续缩高度。

代价是白字直接压在画面上，亮场景没有对比度，所以**浮层上的文字与图标统一走「白色 + 投影」**：

| 对象 | 做法 |
|---|---|
| 标题、时间码、悬浮钮文字（`停止投送`） | `TextStyle.onOverlay()`：`TextStyle.shadow` 叠一层 `#BF000000`、y+1dp、blur 7dp |
| 悬浮钮图标、底栏五个次级图标 | `ShadowedIcon`：同一矢量图往下偏移 1 / 2 / 3dp 各画一遍，透明度 0.55 / 0.30 / 0.15 |

> 先做过一版 **1.5dp 硬描边**（文字用 `drawStyle = Stroke`，图标往 8 个方向各偏移一遍），
> 实测观感太硬、像 PPT 艺术字，已改回投影。图标没有 blur 能力，用「往下偏几层、逐层变淡」
> 近似 —— 方向与文字投影一致（只往下，不四周围一圈）。

`PlayerIconSlot` 置灰（列表首/末个）时**投影一起降到 38%**，否则"不可用"的图标反而被黑影衬得更显眼。

不参与投影的只有**播放/暂停键**：它本来就是 `#6FA8F5` 实心圆 + 深色图标，自带对比度。
投影不占布局，不会把高度吃回来 —— 这一点很关键，横屏的高度本来就紧。

**切方向不再弹提示**：原来第一次点「切方向」会冒一条「切到横/竖屏只改本机方向，接收端画面不受影响」的
Snackbar，连同 `snackbar_orientation` / `hint_orientation` 两条字符串一起删掉了 —— 这个副作用看一眼画面就知道，
每次进播放页第一次切都弹一下反而烦人。Snackbar 现在只用于投送开始/停止这两件事。

因为底栏不再有背景，历史上那条「`background` 必须排在 `navigationBarsPadding` 之前，
否则手势条那一条盖不住」的约束**已经消失**；`navigationBarsPadding` 保留，
作用是让控制排不被手势条压住（而不是让 scrim 铺满）。

**系统栏跟随控制栏显隐**：系统栏（顶部状态栏 + 底部手势条）的可见性**绑定 `overlayVisible`**——
播放器控制栏出现时系统栏一起显示，控制栏收起时才一起隐藏。

| 时机 | 播放器控制栏 | 系统状态栏 / 手势条 |
|---|---|---|
| 进播放页（控制栏默认显示 3 秒） | 显示 | **显示**（用户要能看到时间/电量/通知） |
| 3 秒无操作自动收起 | 隐藏 | **隐藏**（全屏看片） |
| 点击画面唤出 | 显示 | **显示** |
| 从屏幕边缘往里划 | 不变 | 临时浮出，几秒后自动再收起 |

实现是 `LaunchedEffect(insetsController, overlayVisible)` 里 `show` / `hide(WindowInsetsCompat.Type.systemBars())`，
`systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（边缘划一下能临时看通知，不改变控制栏状态）。
**退出播放页必须显式 `show`**：播放页是叠在 `MainActivity` 上的覆盖层而不是独立 Activity，
`onDispose` 时 Activity 还活着，不会靠窗口重建把系统栏带回来（漏了这一步就是"回到文件列表再也看不到状态栏"）。

系统栏隐藏时 `statusBarsPadding()` / `navigationBarsPadding()` 归零，顶栏与底栏自动贴到屏幕边缘；
显示时又各自让出状态栏/手势条的高度。Material3 `TopAppBar` 自带的 `WindowInsets.systemBars` 也是这个来源，
不需要额外改。因为顶栏/底栏本身就在同一个 `AnimatedVisibility` 里淡入淡出，这个 inset 变化不会造成可见的跳动。

本机挖孔在竖屏是顶部居中；横屏会转到左侧边且垂直居中（实测 y 680–761px / 屏高 1440），
与顶栏、底栏都不重叠，所以没有额外加 `displayCutout` 避让。

**交互**：

- 点击画面任意处切换控制栏显隐（`indication = null`，无涟漪）。
- **双击画面 = 播放/暂停**（`detectTapGestures` 的 `onDoubleTap`）。投送中本机是遥控器，
  双击暂停的是**接收端** —— 与底栏那个播放键同一个动作，不算新入口。
  **代价要说清楚**：一旦传了 `onDoubleTap`，`onTap` 就得等一个双击超时（`ViewConfiguration` 约 300ms）
  才能确定"没有第二下"，所以单击唤出控制栏会比以前慢一点点。这是"单击 / 双击并存"的固有代价，
  换任何实现都一样（除非允许第一下就先把控制栏翻出来、第二下再翻回去，那样会明显闪一下）。
  接收端 `LanVideoScreen` 用的是同一套写法。
- **长按画面 = 3× 快进**：按住期间保持，画面中央显示「3× 快进中」的胶囊提示，松手立刻回 1×。
  用 `detectTapGestures` 的 `onLongPress`（开始）+ `onPress` 里的 `tryAwaitRelease()`（松手）配对实现——
  这个 API 只有 `onPress` 拿得到 `awaitRelease`，`onLongPress` 是普通 lambda，所以必须拆两半。
  **只在投送时生效**：开始投屏后本机只是遥控器，长按不会有任何反应（也不显示提示）。
- **左半屏上下滑 = 亮度、右半屏上下滑 = 系统音量**，无级连续，画面中央显示图标 + 进度条 + 百分比。
  实现方式是**单独铺一层子节点**（`PlayerAdjustLayer`）而不是加进上面那个手势里：子节点先拿到事件，
  但只在超过 touch slop 之后才 consume，所以快速点击仍归父层、竖向拖动不会被误判成单击。
  控制栏是同一个 Box 里更靠后的兄弟节点，命中优先，所以在进度条上横向拖动不会被这层抢走。
- **左右滑 = 调进度，边滑边跳**（每个指针事件就 seek 一次，不是松手才跳），画面中央显示
  方向箭头 + 目标时间 + 偏移量。灵敏度：**一整屏宽 = 片长的 1/3**（整段片子 = 三次整屏滑动），
  **按比例缩放、不设上限**；目标一律从**按下那一刻的位置**算起 —— 拿当前 `position` 累加会自激跑飞。
  两版历史都是坑，记下来免得走回去：
  ①最早「短片取片长本身」→ 85 秒的片源一屏宽 = 整片，轻滑一下就归零；
  ②之后「片长与 90 秒取小」→ 补住了短片，却让**所有超过 4 分半的片子退化成同一个灵敏度**
  （一屏宽恒等于 90 秒），用户实测"长视频和短视频同样距离跳过的时间一样"。所以现在纯按比例。
- **位移按速度加权：快划跨得多，慢划跨得少。** 不是改映射，而是把滑过的位移按速度乘一个权重：
  同样 300px，慢划按 1× 计入、快划最多按 **4×** 计入。所以
  **慢划 = 精确微调（与加速度加权之前完全一致），快划 = 快速跨越**。
  速度区间：`SEEK_SPEED_SLOW = 600 px/s` 以下不加速、`SEEK_SPEED_FAST = 3200 px/s` 增益拉满，
  中间线性过渡（避免"速度过一点点、幅度突然翻倍"）。
  速度取自 Compose 的 `VelocityTracker`（对最近一小段采样做最小二乘），
  比"逐帧位移 ÷ 帧间隔"平滑、比指数滑动平均响应快 —— EMA 试过，
  短促的一甩只产生几个事件，权重还没爬上去手指就抬了。
  **投送中不生效**：那时本机只是一块遥控面板，没有画面可对着滑；要调接收端进度仍走底栏进度条
  与 ±10 秒按钮。横向与纵向是**同一个 Box 上串联的两个 `pointerInput`**，各自
  `await*TouchSlopOrCancellation`，谁先越过自己那根轴的 slop 谁 consume、另一个自动放弃 ——
  换成兄弟节点叠放是不行的（命中测试只把事件交给最上层那一个）。
  探测器用 `GestureDetectors.kt` 里那两个自定义实现而不是 foundation 自带的：自带的会在
  `onDragStart` **之前**先调一次 `on*Drag` 且把同一段 overSlop 算两遍，而这里的 `onDragStart`
  要负责定下"这次调亮度还是调音量"。
  **六个回调都过一层 `rememberUpdatedState` 再交给 `pointerInput`**：block 只在 key 变化时重新挂载，
  它捕获的是当时那一版闭包，而调用方传进来的 lambda 每次重组都是新的、还可能闭包了普通 `val`
  （比如播放页的 `position`）—— 被固定成旧的就会"从进入播放页那一刻的位置算起"。
- **两个都是系统级的，退出播放不还原**（原始需求里亮度本来是"仅播放页"，实测下来用户要的是系统级）。
  亮度优先走 `Settings.System.putInt(SCREEN_BRIGHTNESS)`（需要 `WRITE_SETTINGS` 特殊权限）；
  没权限时退回窗口级 `Window.screenBrightness` —— **实测小米 ROM 会把这个窗口值写进系统设置**，
  所以效果同样是"改系统亮度"，只有在窗口级语义严格生效的 ROM 上才会退化成"仅播放页"。
  **读回必须和写入走同一条路**：`SystemBrightness.current(context, activity)` 先取
  `window.attributes.screenBrightness`（`BRIGHTNESS_OVERRIDE_NONE` 是 `-1f`，天然落在 0..1 之外被排除），
  取不到才退回系统设置。早期只读系统设置，而在没有 `WRITE_SETTINGS` 的机器上写入根本不动系统设置
  （两个 App 的 manifest 都没声明这条权限，`canWrite()` 实测恒为 false，即都走窗口级），
  于是下一次滑动又从那条第 0 档旧值起步 —— 也就是"明明刚调亮、再上滑一次还是从 0 开始涨"。
  小米平板（M367FC，窗口级语义严格生效，最适合照出这个 bug）实测：同样 400px 的左侧上滑连做三次，
  `dumpsys display` 的 `Display Brightness` 0.071 → 0.157 → 0.242 → 0.326，逐次累加；
  反向连滑两次 0.326 → 0.232 → 0.138。手机（MIUI 会把窗口值透写进系统设置）读回取窗口值，与写入一致。
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

> 「点击切方向」原来也会弹一条一次性提示，v1.0.9 已删除（副作用看画面朝向就知道，每次进播放页第一次切都弹反而烦人）。

---

## 8.9 接收端的「投视频文件」播放页（与发送端同一套样式）

接收端的局域网播放页（`receiver/.../ui/LanVideoScreen.kt`）**刻意复用发送端这一套视觉语言**，
不是另起一套：顶栏与底栏全透明、白字白图标带投影、自绘 3dp/12dp 进度条、3 秒自动收起、
系统栏跟着控制栏显隐、矮屏自动换紧凑档、中央竖滑调亮度/音量的浮层逐像素一致，
**点击画面切控制栏、双击画面播放/暂停**的手势也一致（含上面那条"单击会晚约 300ms"的代价）。

**唯一有意的不一致：横滑调进度在两端都实时跟手，但发送端在投送态会整个关掉**（那时它只是遥控面板）；
接收端本身就是播放端，没有"遥控态"，所以恒可用。判断就在一行：
`seekEnabled = duration > 0 && !remote`（发送端）/ `duration > 0`（接收端）。

**接收端：seek 不许改播放/暂停状态。** Media3 的 seek 会让播放器走一遍
`BUFFERING → READY`，所以 `LanVideoPlayer` 的 `Player.Listener.STATE_READY` 分支里
**不能**再写 `playWhenReady = true` —— 那是"就绪即起播"的老写法（对应原来 MediaPlayer 的
`onPrepared -> start()`），配上"每次 seek 都会重新 READY"就变成
"暂停在 00:21，点一下 +10s 跳到 00:31 并且自己播起来"。
起播只由 `prepare()` 之后那一次 `playWhenReady = true` 负责；`STATE_READY` 只更新
`playing = p.isPlaying` 这类状态。副作用是"加载中按暂停"也不会被就绪事件顶掉。
libVLC 兜底路径不用额外处理：实测 ASF 片源上"暂停后 +10s"同样保持暂停（libVLC 的
`time` setter 不会把暂停顶掉）。

令牌放在 `receiver/.../ui/theme/Immersion.kt`，是发送端
`ui/theme/{Color,Spacing,Type,Shape}.kt` 播放页子集的**镜像拷贝**（与 `LanCast.kt` 同样的处理）：
两个 App 是两个独立 Gradle 工程，没有公共依赖模块。**改发送端播放页样式时这个文件要一起改。**

按能力差异省略的（不是样式偷懒）：

| 发送端有、接收端没有 | 原因 |
|---|---|
| 上一个 / 下一个 | 接收端只会收到发送端推来的单个地址，没有播放列表 |
| 投屏钮 | 它自己就是接收端的这一端 |
| 切方向钮 | 发送端那个钮是为了修发送端自己的画面；接收端这一页没有方向偏好 |
| 长按 3× 快进 | `LanVideoPlayer` 没有倍速接口 |
| 底栏的「退出」 | 改由顶栏返回箭头承担（发送端同样只保留一个入口），系统返回键照旧 |

**接收端反而多一个：顶栏右侧的「断开投屏」**（发送端没有，它自己就是发起方，点「停止投送」即可）。
原来接收端只有返回箭头，用户不知道那算不算"断开"，而且按下去之后发送端要等 8 秒才反应，
看着就是"断不干净"。现在返回箭头和这个按钮是**同一个动作**：停本机播放 + 发一条 `EXT_STOP`
让发送端立刻收尾（协议见 `LANCast-v1.md` §8.2）。把这件事写成字摆出来，是为了让它可发现。

底栏因此是**三格**：`↺10` · `播放/暂停` · `↷10`。

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
| 3c | （后续追加）横屏下两个悬浮钮重叠、底栏过高、底部漏一条底色 | 加**矮屏紧凑档**（§8），底栏 `background` 提到 `navigationBarsPadding` 之前 | 横屏手机只有 384dp 高，顶栏 + 切方向钮 ≈ 154dp、底栏 + 悬浮钮 + 手势条内边距能到 262dp，相加超过屏高必然叠上；scrim 顺序反了则手势条那一条盖不住 |
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
