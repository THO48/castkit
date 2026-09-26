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
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xCC000000))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = when (target) {
                PlayerAdjustTarget.BRIGHTNESS -> Icons.Rounded.Brightness6
                PlayerAdjustTarget.VOLUME -> Icons.Rounded.VolumeUp
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
        Box(
            modifier = Modifier
                .width(132.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x66FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(v)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Text(
            text = "${(v * 100).roundToInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 播放期亮度：**只改当前窗口**，不动系统亮度设置。
 *
 * `WindowManager.LayoutParams.screenBrightness` 的语义是「这个窗口盖住屏幕时的亮度」，
 * 窗口销毁后系统亮度自动回来。但**实测小米 ROM 会把这个窗口值直接写进系统设置**：
 * 在播放页里改亮度，`settings get system screen_brightness` 跟着变；窗口复位（NONE）之后
 * 系统设置**仍然停在最后那个值**上，不会自己回去。所以必须在退出时显式回写原值 ——
 * 见 [restore]。
 */
object PlayerBrightness {

    /** 起始值取系统亮度，这样第一下滑动是从"现在看着的样子"开始，不会先跳一下。 */
    fun current(context: Context): Float {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (raw / 255f).coerceIn(0.02f, 1f)
    }

    fun apply(activity: Activity?, value: Float) {
        val window = activity?.window ?: return
        runCatching {
            val lp = window.attributes
            lp.screenBrightness = value.coerceIn(0.01f, 1f)
            window.attributes = lp
        }
    }

    /**
     * 退出播放时恢复系统亮度。
     *
     * 先把窗口亮度**设回进页时记下的系统值**，再交还控制权（`BRIGHTNESS_OVERRIDE_NONE`）：
     * - 对会把窗口值写进系统的 ROM，第一步就是把系统设置改回去，第二步只是停止覆盖；
     * - 对行为正确的 ROM，第一步只影响本窗口（反正马上要复位），系统设置从头到尾没被动过。
     *
     * 不直接用 `Settings.System.putInt` 是因为那需要 `WRITE_SETTINGS` 特殊权限（要用户去设置页授权），
     * 而上面这条路不需要任何权限。
     */
    fun restore(activity: Activity?, savedSystemValue: Float) {
        val window = activity?.window ?: return
        apply(activity, savedSystemValue)
        // 两次 setAttributes 紧挨着调用会被 WindowManager 合并成一次，中间那个值根本轮不到生效
        // （实测：直接连写，系统亮度停在改动后的值没回去）。所以隔一拍再交还控制权。
        window.decorView.postDelayed({
            runCatching {
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }, RESTORE_SETTLE_MS)
    }

    /** 回写原值之后隔多久交还控制权。 */
    private const val RESTORE_SETTLE_MS = 250L
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
