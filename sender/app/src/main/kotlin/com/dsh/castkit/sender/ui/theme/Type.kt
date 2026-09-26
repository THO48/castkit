package com.dsh.castkit.sender.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * ============================================================================
 * CastKit 设计系统 —— 字体 Token（见 docs/DESIGN-SYSTEM.md §2）
 * ============================================================================
 *
 * 字体族：系统默认（Android 上是 Roboto，中文回退 Noto Sans CJK），
 * 不引入自定义 FontFamily、不打包字体文件。
 *
 * ★ 粗细只用 400 / 500 / 700，**不用 W600**。
 *   这不是审美偏好，是字体可用性约束：Roboto 的字重集合是
 *   100 / 300 / 400 / 500 / 700 / 900，**没有 600**；中文回退的
 *   Noto Sans CJK 同样是 400 / 500 / 700。请求 W600 时系统会回退到
 *   最近的可用字重或现场合成，结果是「同一个 W600 在不同机器上粗细
 *   不一致」——那正是本次重构要消灭的拼凑感。
 *
 * letterSpacing 一律 0：中文字形是等宽方块，M3 默认给拉丁文调的
 * 字距（labelLarge 0.1sp 等）在中文上只会让每行长度不可预测。
 */

internal val CastKitTypography = Typography(
    // 未在本设计系统使用，但显式定义以免落到 M3 默认值上造成不一致。
    displayLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.sp,
    ),
    displayMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.sp,
    ),

    // headlineSmall 即设计文档里的 `emphasis`：全应用仅用于
    // 「投屏状态主文字」与「错误标题」两处强强调。
    headlineLarge = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = 0.sp,
    ),

    /** `screenTitle` —— 页面大标题（"接收端" / "画面参数" / "内部存储"）。 */
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    ),

    /** `sectionTitle` —— 区块标题（"文件夹" / "视频"）。 */
    titleMedium = TextStyle(
        fontSize = 18.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    ),

    /** `cardTitle` —— 卡片标题、设备名、视频文件名。 */
    titleSmall = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    ),

    /** `bodyLarge` —— 主要正文。 */
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.sp,
    ),

    /** `bodyMedium` —— 次要正文、列表摘要。 */
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.sp,
    ),

    /** `supportSmall` —— 辅助信息（分辨率 · 大小、说明文字）。AA 依赖 onSurfaceVariant。 */
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 0.sp,
    ),

    /** `label` —— 按钮与 FilterChip 文字。 */
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    ),

    /** `labelSmall` —— 小标签、状态文字。 */
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    ),

    /** `microLabel` —— 播放器图标下方极小文字、时长角标。 */
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.W500,
        letterSpacing = 0.sp,
    ),
)
