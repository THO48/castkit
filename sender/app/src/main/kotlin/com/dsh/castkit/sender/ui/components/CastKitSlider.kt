package com.dsh.castkit.sender.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dsh.castkit.sender.ui.CastKitTheme
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 设计系统统一的滑动条。
 *
 * ## 交互样式来源
 *
 * 参考了 nifty-slider（`github.com/litao0621/nifty-slider`）里两种做法的**思路**，
 * 但**没有引入那个库**：它是自定义 `View`，套进纯 Compose 的界面要经过 `AndroidView`，
 * 会绕过整套设计系统（拿不到 M3 配色、深浅色、insets、涟漪），也违背本项目
 * "不再保留 View 体系 UI 组件"的边界。这里用 Compose 原生手势重写，效果等价。
 *
 *  - **按压生长**（抖音样式）：按下时滑轨 4dp → 8dp、滑块 16dp → 22dp，
 *    松手缩回，160ms `FastOutSlowInEasing`。给出"抓住了"的即时反馈。
 *  - **跟手数值气泡**（微信读书把数值放进滑块的等价做法）：拖动时在滑块上方
 *    浮出当前值，手指不用离开滑块就能读数。用气泡而不是把字压进滑块，
 *    是因为 "20 Mbps" 这档文字比滑块还宽。
 *  - **刻度**：步长足够小（≤10 档）时自动画刻度点；码率是 20 档，画出来会变成一条虚线，
 *    所以自动抑制。
 *
 * ## 手势与父级滚动的关系（重要）
 *
 * 这个滑动条常放在**可纵向滚动**的页面里。因此手势先看方向：
 * 位移超过 touchSlop 时比较横纵分量，**只有横向占优才接管**并 consume；
 * 纵向占优就完全不消费，交还给父级滚动。不这样做的话，用户在滑动条上起手
 * 上下滑就会划不动页面。轻点（未超过 touchSlop）则直接跳到该位置。
 *
 * ## 两个必须守住的实现细节
 *
 * 1. `pointerInput` 的 key **只能是稳定的原始值**（布尔 / 整数 / 浮点）。
 *    早期版本把 `valueRange`（`1f..20f` 这个区间对象）当 key：区间对象在每次重组时
 *    都是新建的，页面一滚动就触发重组 → key 变化 → **手势协程被取消**。
 * 2. 手势取消（被父级滚动抢走、或上面那种 key 变化）时，协程直接结束，
 *    循环里的收尾代码**不会执行**。所以 `dragging` 的复位必须放在 `finally` 里，
 *    否则数值气泡会永久挂在屏幕上。
 *
 * @param steps M3 语义：中间刻度点的个数。`0` = 连续；`18` = 1..20 共 19 段的整数值。
 * @param valueLabel 拖动时气泡里的文案；传 null 不显示气泡。
 */
@Composable
fun CastKitSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueLabel: ((Float) -> String)? = null,
    colors: CastKitSliderColors = CastKitSliderDefaults.colors(),
) {
    val density = LocalDensity.current

    // ★ 把区间拆成两个 Float，专供 pointerInput 当 key（见类注释第 1 条）
    val rangeStart = valueRange.start
    val rangeEnd = valueRange.endInclusive
    val range = rangeEnd - rangeStart

    var widthPx by remember { mutableFloatStateOf(1f) }
    var bubbleWidthPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(value) }

    val current = if (dragging) dragValue else value
    val fraction = if (range > 0f) {
        ((current - rangeStart) / range).coerceIn(0f, 1f)
    } else {
        0f
    }

    // 步长对齐：M3 的 steps 是"中间点个数"，所以总段数是 steps + 1
    fun snap(raw: Float): Float = if (steps > 0 && range > 0f) {
        val stepSize = range / (steps + 1)
        val snapped = rangeStart + ((raw - rangeStart) / stepSize).roundToInt() * stepSize
        snapped.coerceIn(rangeStart, rangeEnd)
    } else {
        raw.coerceIn(rangeStart, rangeEnd)
    }

    val trackHeight by animateDpAsState(
        targetValue = if (dragging) 8.dp else 4.dp,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "trackHeight",
    )
    val thumbSize by animateDpAsState(
        targetValue = if (dragging) 22.dp else 16.dp,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "thumbSize",
    )

    val thumbPx = with(density) { thumbSize.toPx() }
    val bubbleGapPx = with(density) { 34.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CastKitSizes.minTouchTarget)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, steps, rangeStart, rangeEnd) {
                if (!enabled) return@pointerInput
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        var decided = false
                        var horizontal = false
                        var accX = 0f
                        var accY = 0f

                        fun emitAt(x: Float) {
                            if (range <= 0f) return
                            val next = snap(rangeStart + (x / widthPx) * range)
                            dragValue = next
                            onValueChange(next)
                        }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                            accX += change.position.x - change.previousPosition.x
                            accY += change.position.y - change.previousPosition.y

                            if (!decided) {
                                val slop = viewConfiguration.touchSlop
                                if (abs(accX) > slop || abs(accY) > slop) {
                                    decided = true
                                    horizontal = abs(accX) > abs(accY)
                                    if (horizontal) dragging = true
                                }
                            }

                            if (horizontal && change.positionChanged()) {
                                emitAt(change.position.x)
                                change.consume()
                            }

                            if (!change.pressed) {
                                // 轻点（从未确定方向）：直接跳到点击位置
                                if (!decided) {
                                    dragging = true
                                    emitAt(down.position.x)
                                }
                                break
                            }
                        }

                        if (horizontal || !decided) {
                            onValueChangeFinished?.invoke()
                        }

                        // ★ 正常结束（手指抬起）也必须复位。只写在 finally 里是不够的：
                        // awaitEachGesture 在每次手势结束后会继续等下一次手势，
                        // 正常路径根本不会让 finally 执行。
                        dragging = false
                    }
                } finally {
                    // ★ 手势被取消（父级滚动抢走 / key 变化导致协程取消）时的兜底
                    dragging = false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // ---- 未播轨 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(colors.inactiveTrack),
        )

        // ---- 已播轨 ----
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(trackHeight)
                .clip(CircleShape)
                .background(if (enabled) colors.activeTrack else colors.disabledTrack),
        )

        // ---- 刻度（档位少时才画）----
        if (steps in 1..10 && widthPx > 1f && range > 0f) {
            val stepSize = range / (steps + 1)
            for (i in 1..steps) {
                val f = (i * stepSize) / range
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (f * widthPx - with(density) { 1.5.dp.toPx() }).roundToInt(),
                                y = 0,
                            )
                        }
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(if (f <= fraction) colors.activeTick else colors.inactiveTick),
                )
            }
        }

        // ---- 滑块 ----
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (fraction * widthPx - thumbPx / 2f).roundToInt(),
                        y = 0,
                    )
                }
                .size(thumbSize)
                .clip(CircleShape)
                .background(if (enabled) colors.thumb else colors.disabledTrack),
        )

        // ---- 跟手数值气泡 ----
        val label = valueLabel
        AnimatedVisibility(
            visible = dragging && label != null,
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.85f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f),
            modifier = Modifier.offset {
                val rawX = fraction * widthPx - bubbleWidthPx / 2f
                val maxX = (widthPx - bubbleWidthPx).coerceAtLeast(0f)
                IntOffset(
                    x = rawX.coerceIn(0f, maxX).roundToInt(),
                    y = (-bubbleGapPx).roundToInt(),
                )
            },
        ) {
            Surface(
                shape = CircleShape,
                color = CastKitTheme.colorScheme.inverseSurface,
                contentColor = CastKitTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.onSizeChanged { bubbleWidthPx = it.width.toFloat() },
            ) {
                Text(
                    text = label?.invoke(current).orEmpty(),
                    style = CastKitTheme.typography.labelMedium,
                    modifier = Modifier.padding(
                        horizontal = CastKitSpacing.space3,
                        vertical = CastKitSpacing.space1,
                    ),
                )
            }
        }
    }
}

/** 滑动条配色。默认取主色 + 描边色，深浅色与动态取色都自动跟随。 */
@Immutable
data class CastKitSliderColors(
    val activeTrack: Color,
    val inactiveTrack: Color,
    val thumb: Color,
    val activeTick: Color,
    val inactiveTick: Color,
    val disabledTrack: Color,
)

object CastKitSliderDefaults {
    @Composable
    fun colors(
        activeTrack: Color = CastKitTheme.colorScheme.primary,
        inactiveTrack: Color = CastKitTheme.colorScheme.outlineVariant,
        thumb: Color = CastKitTheme.colorScheme.primary,
        activeTick: Color = CastKitTheme.colorScheme.onPrimary,
        inactiveTick: Color = CastKitTheme.colorScheme.outline,
        disabledTrack: Color = CastKitTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    ): CastKitSliderColors = CastKitSliderColors(
        activeTrack = activeTrack,
        inactiveTrack = inactiveTrack,
        thumb = thumb,
        activeTick = activeTick,
        inactiveTick = inactiveTick,
        disabledTrack = disabledTrack,
    )
}
