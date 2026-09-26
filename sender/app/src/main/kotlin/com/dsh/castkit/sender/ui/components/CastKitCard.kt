package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.ui.CastKitTheme

/**
 * 设计系统统一的卡片容器，落实 §5「层级策略」。
 *
 * 为什么必须收口成一个组件：**浅色用阴影、深色用表面提亮**这条规则如果散落在每个页面里，
 * 迟早会出现"某一页深色下还是画阴影"（阴影在深底上根本看不见，等于没有层级）。
 *
 * 深色下额外补一条 1dp `outlineVariant` 描边——因为 `#121212` → `#1E1E1E` 的表面差
 * 只有 **1.12:1**，人眼可辨但很弱，需要描边兜底才不至于糊成一片。
 *
 * 深浅判断用背景色的**相对亮度**而不是 `isSystemInDarkTheme()`：开启动态取色时，
 * 实际配色由壁纸派生，系统深浅标志与手上这套 ColorScheme 可能不同步。
 */
@Composable
fun CastKitCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = CastKitTheme.colorScheme.background.luminance() < 0.5f

    Card(
        modifier = modifier,
        shape = CastKitTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = CastKitTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 1.dp),
        border = if (isDark) {
            BorderStroke(1.dp, CastKitTheme.colorScheme.outlineVariant)
        } else {
            null
        },
        content = content,
    )
}

/** 供非卡片的浮层（菜单、对话框）复用同一套深浅判断。 */
@Composable
fun castKitIsDarkSurface(): Boolean = CastKitTheme.colorScheme.background.luminance() < 0.5f

/** 未使用，保留给预览调试：暴露系统深浅标志以便对照。 */
@Composable
internal fun systemDarkFlag(): Boolean = isSystemInDarkTheme()
