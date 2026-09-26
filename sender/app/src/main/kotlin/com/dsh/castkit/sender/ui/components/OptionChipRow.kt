package com.dsh.castkit.sender.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSpacing
import com.dsh.castkit.sender.ui.theme.PillShape

/** 边缘渐隐遮罩宽度。32dp 约等于一个 Chip 的圆头，足以让人看出"右边还有东西"。 */
private val EdgeFadeWidth = 32.dp

/**
 * 单行单选控件（分辨率 / 帧率）。
 *
 * 为什么是 Chip 组而不是 `SingleChoiceSegmentedButtonRow`：分段控制器不会滚动，
 * 在 360dp 屏上每段只有约 74dp，而"跟随本机"四个汉字就要 56dp，加上 M3
 * 默认的左右内边距（24dp）直接溢出。Chip 组可以横向滚动，没有这个上限，
 * 因此也不需要为了塞进分段控制器而缩短文案。
 *
 * 选中态：`primary` 实心填充 + `onPrimary` 文字。
 * 未选中：透明底 + 1dp `outline` 描边（4.29:1，满足 WCAG 1.4.11 组件边界 3:1；
 * 更淡的灰会掉到 1.5:1 而违规）。
 *
 * ★ 滚动提示：内容超出时在边缘叠一层「底色 → 透明」的渐变遮罩，露出的
 * 半个 Chip 配合渐隐，让用户知道右边还有内容。遮罩画在 [Box] 自己身上
 * （`drawWithContent` 之后绘制），**不额外放一个覆盖层**，所以完全不吃触摸事件，
 * 不会影响滚动与点击。两端各自独立判断，滚到头就不画。
 *
 * @param fadeColor 遮罩的"消隐色"，必须是本控件所在容器的底色（默认卡片 surface）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OptionChipRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    fadeColor: Color = CastKitTheme.colorScheme.surface,
) {
    val scrollState = rememberScrollState()

    // 用 derivedStateOf 只把「是否已经滚到头」这个布尔量暴露给重组，
    // 否则每一帧滚动都会让整个 Chip 行重组一次。
    val showStartFade by remember {
        derivedStateOf { scrollState.value > 0 }
    }
    val showEndFade by remember {
        derivedStateOf { scrollState.value < scrollState.maxValue }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()

                val fadePx = EdgeFadeWidth.toPx()
                val w = size.width
                val h = size.height
                if (fadePx <= 0f || w <= 0f) return@drawWithContent

                if (showStartFade) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(fadeColor, Color.Transparent),
                            startX = 0f,
                            endX = fadePx,
                        ),
                        topLeft = Offset.Zero,
                        size = Size(fadePx, h),
                    )
                }
                if (showEndFade) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, fadeColor),
                            startX = w - fadePx,
                            endX = w,
                        ),
                        topLeft = Offset(w - fadePx, 0f),
                        size = Size(fadePx, h),
                    )
                }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = CastKitSpacing.pageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(CastKitSpacing.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            text = label(option),
                            style = CastKitTheme.typography.labelLarge,
                        )
                    },
                    shape = PillShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = CastKitTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = CastKitTheme.colorScheme.primary,
                        selectedLabelColor = CastKitTheme.colorScheme.onPrimary,
                    ),
                    // M3 芯片的触摸目标由 minimumInteractiveComponentSize 保证 48dp，
                    // 视觉高度仍是默认值，所以不需要也不应该显式设 height。
                    border = if (isSelected) {
                        null
                    } else {
                        BorderStroke(1.dp, CastKitTheme.colorScheme.outline)
                    },
                )
            }
        }
    }
}
