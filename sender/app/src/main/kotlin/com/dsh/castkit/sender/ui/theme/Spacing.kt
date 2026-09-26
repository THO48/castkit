package com.dsh.castkit.sender.ui.theme

import androidx.compose.ui.unit.dp

/*
 * ============================================================================
 * CastKit 设计系统 —— 间距与尺寸 Token（见 docs/DESIGN-SYSTEM.md §4）
 * ============================================================================
 *
 * 基础网格 **4dp**。原始需求写的是「8dp 网格 + 12/16dp 间距」，但 12 不是 8 的
 * 倍数，两者无法同时成立，所以基础网格取 4dp，8 / 12 / 16 / 20 / 24 / 28 / 56
 * 全是它的整数倍。
 *
 * 这些是纯 dp 常量、不随主题变化，所以做成 object 而不是 CompositionLocal。
 */

/** 间距。 */
object CastKitSpacing {
    /** 4dp —— 图标与文字、角标内边距。 */
    val space1 = 4.dp

    /** 8dp —— 紧邻元素（网格横纵间距、Chip 间距）。 */
    val space2 = 8.dp

    /** 12dp —— 组件间距（同组内控件之间）。 */
    val space3 = 12.dp

    /** 16dp —— 页面边距、卡片内边距、内容行间距。 */
    val space4 = 16.dp

    /** 20dp —— 卡片之间。 */
    val space5 = 20.dp

    /** 24dp —— 区块间距（"接收端" ↔ "画面参数"）。 */
    val space6 = 24.dp

    /** 32dp —— 大区块留白（空状态上下）。 */
    val space7 = 32.dp

    /** 页面左右边距。 */
    val pageHorizontal = 16.dp

    /** 卡片内边距。 */
    val cardInside = 16.dp

    /** 区块之间的纵向间距。 */
    val sectionGap = 24.dp

    /** 同一组内控件之间。 */
    val componentGap = 12.dp

    /** 内容行之间。 */
    val contentGap = 16.dp
}

/** 尺寸。 */
object CastKitSizes {
    /** 所有可点击区域的下限，无例外。 */
    val minTouchTarget = 48.dp

    /** 顶栏图标点击区（图标本体 24dp）。 */
    val iconButton = 48.dp

    /** 图标本体。 */
    val icon = 24.dp

    /** 吸底主按钮高度。 */
    val primaryButtonHeight = 56.dp

    /** 列表行最小高度。 */
    val listRowMinHeight = 56.dp

    /** 网格横纵间距。 */
    val gridGap = 8.dp

    /** 视频缩略图圆角。 */
    val thumbnailCorner = 12.dp

    /** 时长角标圆角。 */
    val durationBadgeCorner = 4.dp

    /** 文件夹圆形图标直径。 */
    val folderAvatar = 56.dp

    /** 文件夹圆形图标里的图标本体。 */
    val folderGlyph = 28.dp

    /** 空状态图标尺寸。 */
    val emptyIcon = 64.dp

    /** 投屏状态圆点直径。 */
    val statusDot = 10.dp

    // ---- 播放器专属（见 §4.3） ----

    /** 进度条轨高。 */
    val scrubTrackHeight = 3.dp

    /** 进度条圆形滑块直径。 */
    val scrubThumb = 12.dp

    /** 播放/暂停主按钮直径。 */
    val playerPrimaryButton = 64.dp

    /**
     * 矮屏（横屏手机）用的主按钮直径。
     * 横屏可用高度只有 384dp，底栏每省一点都很关键，否则底栏会和顶栏那一条叠上。
     */
    val playerPrimaryButtonCompact = 56.dp

    /** 播放/暂停主按钮里的图标本体。 */
    val playerPrimaryGlyph = 32.dp

    /** 次级图标本体（点击区仍为 48dp）。 */
    val playerSecondaryGlyph = 24.dp
}

/** 动效时长。 */
object CastKitMotion {
    /** 控制栏淡入淡出。 */
    const val OVERLAY_FADE_MS = 200

    /** 控制栏显示后无交互即隐藏。 */
    const val OVERLAY_AUTO_HIDE_MS = 3000L

    /** 状态点颜色过渡。 */
    const val STATE_COLOR_MS = 300
}
