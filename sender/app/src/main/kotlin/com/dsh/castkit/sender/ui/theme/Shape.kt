package com.dsh.castkit.sender.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * ============================================================================
 * CastKit 设计系统 —— 形状 Token（见 docs/DESIGN-SYSTEM.md §3）
 * ============================================================================
 *
 * 注意：M3 的 Button / TextButton / FilterChip 默认形状就是胶囊形
 * （`ButtonDefaults.shape` = `Shapes.full`），所以「禁止方正的默认按钮」
 * 是**默认满足**的，不需要额外覆写。
 *
 * M3 里唯一圆角为 0 的常见控件是 `OutlinedTextField`，本设计系统统一用
 * `FilledTextField` 变体（`TextField` + 自定义 colors），不属于「方正默认按钮」。
 */

internal val CastKitShapes = Shapes(
    /** 时长角标等极小容器。 */
    extraSmall = RoundedCornerShape(4.dp),

    /** 输入框内小容器。 */
    small = RoundedCornerShape(8.dp),

    /** 视频缩略图。 */
    medium = RoundedCornerShape(12.dp),

    /** 卡片、对话框、下拉菜单。 */
    large = RoundedCornerShape(16.dp),

    /** 吸底主按钮、设备卡片选中态。 */
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 胶囊形（全圆角）。`Shapes` 里没有这一档，而设计系统要求 Chip 与按钮
 * 一律胶囊形，所以单独暴露一个常量，避免各处各写一个 `RoundedCornerShape(50)`。
 */
val PillShape = RoundedCornerShape(percent = 50)
