package io.github.jqssun.airplay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * ============================================================================
 * 接收端播放页设计令牌 —— **镜像自发送端**
 *   sender/.../ui/theme/Color.kt      → ImmersiveColors
 *   sender/.../ui/theme/Spacing.kt    → PlayerSpacing / PlayerSizes / PlayerMotion
 *   sender/.../ui/theme/Type.kt       → PlayerType
 *   sender/.../ui/theme/Shape.kt      → PillShape
 * ============================================================================
 *
 * 为什么是拷贝而不是共享模块：两端是两个独立的 Gradle 工程、各自出包，没有公共
 * 依赖模块；仓库里 `LanCast.kt` 也是同样的处理（镜像拷贝 + 注释说明）。
 * **改发送端播放页样式时，这个文件要一起改**，否则两个 App 的播放页会走样。
 *
 * 这里只放播放页真正用到的那部分：不引入整套 ColorScheme / Typography，
 * 免得顺手把接收端设置页、日志页的观感也改掉（AirPlayTheme 保持原样）。
 *
 * 视频播放场景不存在浅色模式（YouTube / Netflix 同理），所以这些是**固定常量**，
 * 不随系统深浅色变化。
 */

/** 播放页固定色。与发送端 `ImmersiveColors` 逐项对齐。 */
object ImmersiveColors {
    /** 播放页底色，恒纯黑。 */
    val Background = Color(0xFF000000)

    /** 半透明黑底：只给长按/调整这类**居中提示胶囊**垫底（顶栏、底栏、悬浮钮都是全透明）。 */
    val Scrim = Color(0xB3000000)

    /** 主文字与图标。 */
    val OnScrim = Color(0xFFFFFFFF)

    /**
     * 浮层文字/图标的投影色。
     *
     * 顶栏、底栏全透明之后，白字白图标是直接压在画面上的：亮场景（雪景、白墙）里
     * 没有底就糊了，而垫一块黑底又会把画面盖住 —— 所以统一压一层"往下偏一点的柔和黑影"。
     * 发送端试过 1.5dp 硬描边，观感太硬，已改回投影；这里与它保持一致。
     */
    val Shadow = Color(0xFF000000)

    /** 次要信息（时间码）。合成后 ≈10:1。 */
    val TextSecondary = Color(0xB3FFFFFF)

    /** 进度条未播轨道。4.61:1，满足组件边界 3:1。 */
    val ScrubTrackInactive = Color(0x4DFFFFFF)

    /** 进度条圆形滑块。 */
    val ScrubThumb = Color(0xFFFFFFFF)

    /** 错误文字：8.24:1。 */
    val Error = Color(0xFFFF7A6A)

    /** 强调色（进度条已播部分、调整浮层的填充）。8.60:1。 */
    val Accent = Color(0xFF6FA8F5)
}

/** 间距（基础网格 4dp）。与发送端 `CastKitSpacing` 的播放页子集一致。 */
object PlayerSpacing {
    /** 4dp —— 图标与文字。 */
    val space1 = 4.dp

    /** 8dp —— 紧邻元素、紧凑档行距。 */
    val space2 = 8.dp

    /** 12dp —— 同组控件之间。 */
    val space3 = 12.dp

    /** 16dp —— 页面边距、提示胶囊内边距。 */
    val space4 = 16.dp

    /** 24dp —— 居中的错误/加载提示四周留白。 */
    val space6 = 24.dp

    /** 播放页两端的横向留白。 */
    val edge = 16.dp
}

/** 尺寸。与发送端 `CastKitSizes` 的播放页子集一致。 */
object PlayerSizes {
    /** 所有可点击区域的下限，无例外。 */
    val minTouchTarget = 48.dp

    /** 进度条轨高。 */
    val scrubTrackHeight = 3.dp

    /** 进度条圆形滑块直径。 */
    val scrubThumb = 12.dp

    /** 播放/暂停主按钮直径。 */
    val playerPrimaryButton = 64.dp

    /** 矮屏（横屏手机）用的主按钮直径。 */
    val playerPrimaryButtonCompact = 56.dp

    /** 播放/暂停主按钮里的图标本体。 */
    val playerPrimaryGlyph = 32.dp

    /** 次级图标本体（点击区仍为 48dp）。 */
    val playerSecondaryGlyph = 24.dp
}

/** 动效时长。与发送端 `CastKitMotion` 一致。 */
object PlayerMotion {
    /** 控制栏淡入淡出。 */
    const val OVERLAY_FADE_MS = 200

    /** 控制栏显示后无交互即隐藏。 */
    const val OVERLAY_AUTO_HIDE_MS = 3000L
}

/**
 * 播放页文字样式。发送端的页面只用到这三档，取值逐项对齐它的 `CastKitTypography`。
 *
 * 注意 letterSpacing 一律 0：M3 默认给拉丁文调过字距（labelMedium 0.5sp 之类），
 * 中文上只会让每行长度不可预测。
 */
object PlayerType {
    /** 标题（文件名）。 */
    val titleSmall = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    )

    /** 常规档时间码。 */
    val labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    )

    /** 紧凑档时间码、图标下的小字。 */
    val labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    )

    /** 播放失败的提示正文。 */
    val bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.sp,
    )
}

/**
 * 胶囊形（全圆角）。发送端把它单独暴露一个常量，避免各处各写一个
 * `RoundedCornerShape(50)`；这里同样保留这个名字，方便两边对照。
 */
val PillShape = RoundedCornerShape(percent = 50)
