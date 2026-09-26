package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jqssun.airplay.ui.theme.ImmersiveColors
import io.github.jqssun.airplay.ui.theme.PillShape
import io.github.jqssun.airplay.ui.theme.PlayerSpacing
import io.github.jqssun.airplay.ui.theme.PlayerType
import kotlin.math.roundToInt

/** 竖滑能调的两样东西：左半屏亮度、右半屏音量。 */
enum class PlayerAdjustTarget { BRIGHTNESS, VOLUME }

/**
 * 播放页的竖滑手势层：**屏幕左半边上下滑调亮度、右半边上下滑调音量**。
 *
 * 为什么单独铺一层而不是挂在播放页那个 Box 上：播放页已经用 `clickable` 处理
 * 「单击切控制栏显隐」，两种手势挤在同一个 `pointerInput` 里要自己写一套状态机。
 * 铺一层子节点更省事：子节点先拿到事件，但它**只在超过 touch slop 之后才 consume**，所以
 * - 快速点击 → 子层没消费 → 父层的单击照常触发；
 * - 竖向拖动 → 子层消费掉位移 → 父层判定为取消，不会误触发单击。
 *
 * 控制栏是同一个 Box 里**更靠后**的兄弟节点，命中测试在它们身上优先，
 * 所以在进度条上横向拖动不会被这层抢走。
 *
 * @param onDelta 位移量，单位是**本层高度的比例**（向上为正）。整屏滑一遍 = 1.0，
 *        这样不同分辨率的机器手感一致。
 */
@Composable
fun PlayerAdjustLayer(
    onStart: (PlayerAdjustTarget) -> Unit,
    onDelta: (PlayerAdjustTarget, Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        SideAdjustArea(
            target = PlayerAdjustTarget.BRIGHTNESS,
            onStart = onStart,
            onDelta = onDelta,
            onEnd = onEnd,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        SideAdjustArea(
            target = PlayerAdjustTarget.VOLUME,
            onStart = onStart,
            onDelta = onDelta,
            onEnd = onEnd,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun SideAdjustArea(
    target: PlayerAdjustTarget,
    onStart: (PlayerAdjustTarget) -> Unit,
    onDelta: (PlayerAdjustTarget, Float) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.pointerInput(target) {
            val height = size.height.toFloat().coerceAtLeast(1f)
            detectVerticalDragGestures(
                onDragStart = { onStart(target) },
                onDragEnd = { onEnd() },
                onDragCancel = { onEnd() },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    onDelta(target, -dragAmount / height)
                },
            )
        },
    )
}

/**
 * 调整时的中央提示：图标 + 无级进度条 + 百分比。
 *
 * 进度条按 `value` 连续绘制，**不做任何取整** —— 音量那条要特别注意：
 * 系统音量接口只吃整数档，但显示值来自我们自己维护的浮点数，
 * 所以滑起来是连续的，不会一格一格跳。
 */
@Composable
fun PlayerAdjustIndicator(
    target: PlayerAdjustTarget,
    value: Float,
    modifier: Modifier = Modifier,
) {
    val v = value.coerceIn(0f, 1f)
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(ImmersiveColors.Scrim)
            .padding(horizontal = PlayerSpacing.space4, vertical = PlayerSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlayerSpacing.space3),
    ) {
        Icon(
            imageVector = when (target) {
                PlayerAdjustTarget.BRIGHTNESS -> Icons.Filled.Brightness6
                PlayerAdjustTarget.VOLUME -> Icons.Filled.VolumeUp
            },
            contentDescription = null,
            tint = ImmersiveColors.OnScrim,
            modifier = Modifier.size(24.dp),
        )
        Box(
            modifier = Modifier
                .width(132.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ImmersiveColors.ScrubTrackInactive),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(v)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ImmersiveColors.Accent),
            )
        }
        Text(
            text = "${(v * 100).roundToInt()}%",
            style = PlayerType.labelMedium,
            color = ImmersiveColors.OnScrim,
        )
    }
}

/**
 * 屏幕亮度：**调的就是系统亮度**，退出播放不还原（和音量一样是全局的）。
 *
 * 两条路，优先走第一条：
 * 1. 有 `WRITE_SETTINGS`（特殊权限，要去设置页授权）→ 直接写 `Settings.System.SCREEN_BRIGHTNESS`。
 *    精确、跨 ROM 都生效；
 * 2. 没权限 → 退回窗口级 `Window.screenBrightness`。**实测小米 ROM 会把这个窗口值写进系统设置**，
 *    所以效果同样是"改系统亮度"；只有在窗口级语义严格生效的 ROM 上，才会退化成"仅播放页"。
 *
 * 之前那套"退出播放还原系统亮度"的补偿逻辑（回写原值 + 隔 250ms 交还控制权 + 读回校正）
 * 全部删掉了 —— 需求改成"亮度也是系统级"之后，那些都成了多余动作，而且它们本身就是
 * "最低亮度退出后变成最低+1"那个问题的来源。
 */
object SystemBrightness {

    /** 当前系统亮度，0..1。 */
    fun current(context: Context): Float = currentRaw(context) / 255f

    /** 当前系统亮度的原始档位（0..255）。 */
    fun currentRaw(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128).coerceIn(0, 255)

    /** 有没有直接写系统设置的权限（没有也不影响用，见类注释）。 */
    fun canWrite(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    fun apply(context: Context, activity: Activity?, value: Float) {
        val raw = (value.coerceIn(0f, 1f) * 255).roundToInt()
        if (canWrite(context)) {
            runCatching {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    raw,
                )
            }
        } else {
            val window = activity?.window ?: return
            runCatching {
                val lp = window.attributes
                lp.screenBrightness = raw / 255f
                window.attributes = lp
            }
        }
    }
}

/**
 * 系统媒体音量（`STREAM_MUSIC`）：改的是**系统**音量，退出播放也保持。
 *
 * 「无级」在这里有个平台上限：`setStreamVolume` 只接受整数档。档位多的机器
 * （本机实测媒体音量 100+ 档）手感是连续的；只有 15 档的机器就会一格一格跳 ——
 * 这不是 App 能绕过的，只有把浮点值直接喂给播放器才能真无级，但那样就变成"仅播放界面"了。
 */
object SystemVolume {

    private fun manager(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun current(context: Context): Float {
        val am = manager(context) ?: return 0f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max).coerceIn(0f, 1f)
    }

    fun apply(context: Context, value: Float) {
        val am = manager(context) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = (value.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        // flags = 0：不要系统那套音量条，我们有自己的提示，两个叠一起很乱
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }
}
