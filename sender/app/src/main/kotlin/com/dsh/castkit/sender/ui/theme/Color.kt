package com.dsh.castkit.sender.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * ============================================================================
 * CastKit 设计系统 —— 颜色 Token（见 docs/DESIGN-SYSTEM.md §1）
 * ============================================================================
 *
 * 这里是 **fallback 色板**：只在两种情况下生效——
 *   1. Android 12 以下（本应用 minSdk = 26，覆盖 Android 8/9/10/11 全部机型）；
 *   2. 机型不支持动态取色，或 dynamic*ColorScheme 不可用。
 * Android 12+ 上实际颜色由壁纸种子派生，`#6FA8F5` 不会出现。这是
 * dynamicLightColorScheme()/dynamicDarkColorScheme() 的定义，不是实现取舍。
 *
 * 每个承担「识别职责」的前景/背景组合都实测过 WCAG 2.1 对比度，
 * 全部达到 AA（正文 ≥4.5:1 / 大字与组件边界 ≥3:1）。数据见文档 §1.6。
 */

// ---------------------------------------------------------------------------
// 品牌色
// ---------------------------------------------------------------------------

/** 品牌基准色。仅作参考锚点与深色主题 primary；不是可派生的 seed。 */
val BrandSeed = Color(0xFF6FA8F5)

/** 浅色主题 primary：压深以保证白字过 AA（5.94:1）。 */
val BrandPrimaryLight = Color(0xFF2A62B8)

/** 深色主题 primary：浅蓝在深底上做强调（7.67:1）。 */
val BrandPrimaryDark = Color(0xFF6FA8F5)

/** 深色主题下 primary 之上的文字：近黑，**不是纯白**（7.66:1）。 */
val BrandOnPrimaryDark = Color(0xFF0B1220)

// ---------------------------------------------------------------------------
// 浅色 fallback 色板
// ---------------------------------------------------------------------------

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF2A62B8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6F7),
    onPrimaryContainer = Color(0xFF0B2A55),
    inversePrimary = Color(0xFF6FA8F5),

    secondary = Color(0xFF5A5F6A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8ECF2),
    onSecondaryContainer = Color(0xFF1A1C1E),

    tertiary = Color(0xFF1E6B3C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCFE9D6),
    onTertiaryContainer = Color(0xFF0B2C16),

    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFF1F3F7),
    onSurfaceVariant = Color(0xFF5A5F6A),
    surfaceTint = Color(0xFF2A62B8),

    inverseSurface = Color(0xFF2C2C2C),
    inverseOnSurface = Color(0xFFE6E6E6),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFC9CDD6),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8ECF2),
    surfaceContainer = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFE8ECF2),
    surfaceContainerHighest = Color(0xFFDDE3EC),
    surfaceContainerLow = Color(0xFFF4F6F9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

// ---------------------------------------------------------------------------
// 深色 fallback 色板
// ---------------------------------------------------------------------------

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF6FA8F5),
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = Color(0xFFD3E3FB),
    inversePrimary = Color(0xFF2A62B8),

    secondary = Color(0xFF9AA0A8),
    onSecondary = Color(0xFF0B1220),
    secondaryContainer = Color(0xFF252525),
    onSecondaryContainer = Color(0xFFE6E6E6),

    tertiary = Color(0xFF6DD58C),
    onTertiary = Color(0xFF0B2C16),
    tertiaryContainer = Color(0xFF1F5233),
    onTertiaryContainer = Color(0xFFCFE9D6),

    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFF9AA0A8),
    surfaceTint = Color(0xFF6FA8F5),

    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1E1E1E),

    error = Color(0xFFFF7A6A),
    onError = Color(0xFF3B0906),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFF333333),
    surfaceDim = Color(0xFF121212),
    surfaceContainer = Color(0xFF252525),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainerLowest = Color(0xFF121212),
)

// ---------------------------------------------------------------------------
// 语义色
// ---------------------------------------------------------------------------

/**
 * Miuix 的 `Colors` 里**没有** error / success 这类语义色。
 * M3 的 `ColorScheme` 有 `error` 系列但**同样没有 success**，所以自己补一组。
 *
 * 注意：动态取色下 error 由系统给出，但 `success` **始终**用这里的固定值——
 * 状态色的语义一致性比配色统一更重要。
 */
@Immutable
data class CastKitSemanticColors(
    /** "投屏中"状态点与文字。浅色 6.13:1 / 深色 10.30:1。 */
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

internal val LightSemanticColors = CastKitSemanticColors(
    success = Color(0xFF1E6B3C),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFCFE9D6),
    onSuccessContainer = Color(0xFF0B2C16),
)

internal val DarkSemanticColors = CastKitSemanticColors(
    success = Color(0xFF6DD58C),
    onSuccess = Color(0xFF0B2C16),
    successContainer = Color(0xFF1F5233),
    onSuccessContainer = Color(0xFFCFE9D6),
)

// ---------------------------------------------------------------------------
// 沉浸页固定色（页面 3 视频播放器：恒纯黑，不受浅色/深色模式影响）
// ---------------------------------------------------------------------------

/**
 * 播放器专属颜色。这些值**不随主题变化**——视频播放场景不存在浅色模式
 * （YouTube / Netflix 同理），所以不走 `ColorScheme`，直接取常量。
 */
object ImmersiveColors {
    /** 播放页底色，恒纯黑。 */
    val Background = Color(0xFF000000)

    /** 顶栏 / 底栏半透明黑底（沿用改造前的实测值）。 */
    val Scrim = Color(0xB3000000)

    /** scrim 上的主文字与图标。 */
    val OnScrim = Color(0xFFFFFFFF)

    /** scrim 上的次要信息（时间码、提示语）。合成后 ≈10:1。 */
    val TextSecondary = Color(0xB3FFFFFF)

    /** 进度条未播轨道。4.61:1，满足组件边界 3:1。 */
    val ScrubTrackInactive = Color(0x4DFFFFFF)

    /** 进度条圆形滑块。 */
    val ScrubThumb = Color(0xFFFFFFFF)

    /** 缩略图右下角时长角标底。 */
    val DurationBadge = Color(0xCC000000)

    /** 时长角标文字。 */
    val OnDurationBadge = Color(0xFFFFFFFF)

    /** 播放页错误文字：8.24:1。 */
    val Error = Color(0xFFFF7A6A)

    /** 播放页激活态强调色（切方向激活、投送中）：8.60:1。 */
    val Accent = Color(0xFF6FA8F5)
}
