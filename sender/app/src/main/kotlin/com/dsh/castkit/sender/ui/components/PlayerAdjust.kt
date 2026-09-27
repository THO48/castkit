package com.dsh.castkit.sender.ui.components

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dsh.castkit.sender.ui.theme.CastKitSpacing
import com.dsh.castkit.sender.ui.theme.ImmersiveColors
import com.dsh.castkit.sender.ui.theme.PillShape
import kotlin.math.abs
import kotlin.math.roundToInt

/** 竖滑能调的两样东西：左半屏亮度、右半屏音量。 */
enum class PlayerAdjustTarget { BRIGHTNESS, VOLUME }

/*
 * 横滑调进度的「速度手感」：**快划跨得多，慢划跨得少**。
 *
 * 做法不是改映射本身，而是把滑过的位移按速度加个权：
 * 同一段 300px，慢划按 1× 计入、快划最多按 [SEEK_SPEED_MAX_GAIN]× 计入，
 * 于是慢划＝精确微调（和加这个功能之前完全一致），快划＝快速跨越。
 *
 * 三个阈值是**手感值**，想调就动这里：
 * - [SEEK_SPEED_SLOW]：低于它完全不加速（正常"慢慢挪"的手速大约在 200~500 px/s）；
 * - [SEEK_SPEED_FAST]：到它增益拉满（一次痛快的甩动大约 2500~5000 px/s）；
 * - 中间线性过渡，所以不会出现"速度过一点点、幅度突然翻倍"。
 *
 * 速度用 Compose 的 `VelocityTracker` 取：它对最近一小段采样做最小二乘拟合，
 * 既比"逐帧位移 ÷ 帧间隔"平滑得多，又比指数滑动平均响应快 —— EMA 试过，
 * 短促的一甩只产生几个事件，权重还没爬上去手指就抬了。
 */
private const val SEEK_SPEED_SLOW = 600f
private const val SEEK_SPEED_FAST = 3200f
private const val SEEK_SPEED_MAX_GAIN = 4f

/** 速度（px/s）→ 位移权重。慢于 [SEEK_SPEED_SLOW] 恒为 1×，快于 [SEEK_SPEED_FAST] 封顶。 */
private fun seekSpeedGain(speedPxPerSec: Float): Float {
    val t = ((speedPxPerSec - SEEK_SPEED_SLOW) / (SEEK_SPEED_FAST - SEEK_SPEED_SLOW))
        .coerceIn(0f, 1f)
    return 1f + t * (SEEK_SPEED_MAX_GAIN - 1f)
}

/**
 * 播放页的手势层：
 * **左半屏上下滑 = 亮度、右半屏上下滑 = 音量、左右滑 = 调进度（实时跟手）**。
 *
 * 为什么单独铺一层而不是挂在播放页那个 Box 上：播放页已经用 `detectTapGestures` 处理
 * 「单击切控制栏 / 双击播放暂停 / 长按快进」，那些手势挤在同一个 `pointerInput` 里要自己写
 * 一套状态机，很容易写出时序 bug。铺一层子节点更省事：子节点先拿到事件，但它
 * **只在超过 touch slop 之后才 consume**，所以
 * - 快速点击 → 子层没消费 → 父层的 onTap / onDoubleTap 照常触发；
 * - 任意方向拖动 → 子层消费掉位移 → 父层的 waitForUpOrCancellation 判定为取消，不会误触发单击。
 *
 * 横向与纵向是**同一个 Box 上串联的两个 `pointerInput`**（不是左右两个子节点）：
 * 两边各自 `await*TouchSlopOrCancellation`，**谁先越过自己那根轴的 slop 谁 consume，
 * 另一个自动放弃** —— 这就是横竖不打架的做法。换成兄弟节点叠放是不行的：命中测试只把事件
 * 交给最上层那一个。这套结构照搬接收端 AirPlay 播放器里已经跑通的 `VideoPlayerGestures`。
 *
 * 控制栏是同一个 Box 里**更靠后**的兄弟节点，命中测试在它们身上优先，
 * 所以在底栏进度条上拖动不会被这层抢走。
 *
 * @param onAdjustDelta 位移量，单位是**本层高度的比例**（向上为正）。整屏滑一遍 = 1.0，
 *        这样不同分辨率的机器手感一致。
 * @param onSeekDelta 位移量，单位是**本层宽度的比例**（向右为正）。同样整屏滑一遍 = 1.0；
 *        换算成多少毫秒由调用方决定 —— 只有它知道视频多长。
 * @param seekEnabled 视频还没就绪（时长为 0）、或本机正处于投送态（只是个遥控器）时传 false，
 *        横向手势会整个不启动（本次明确不做遥控）。
 */
@Composable
fun PlayerAdjustLayer(
    onAdjustStart: (PlayerAdjustTarget) -> Unit,
    onAdjustDelta: (PlayerAdjustTarget, Float) -> Unit,
    onAdjustEnd: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekDelta: (Float) -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
    seekEnabled: Boolean = true,
) {
    // 回调一律过一层 rememberUpdatedState 再交给 pointerInput。
    //
    // 必要性：`pointerInput` 的 block 只在 key 变化时才会重新挂载，它捕获的是**当时那一版**
    // 回调闭包。调用方传进来的 lambda 每次重组都是新的，而且可能闭包了普通 val（比如播放页里的
    // `position`）—— 一旦被固定成旧的那一版，滑动就会从"进入播放页那一刻的位置"算起（通常是 0），
    // 而不是"按下那一刻的位置"。套一层 State 之后，block 里读到的永远是最近一次重组传进来的回调。
    val currentOnAdjustStart by rememberUpdatedState(onAdjustStart)
    val currentOnAdjustDelta by rememberUpdatedState(onAdjustDelta)
    val currentOnAdjustEnd by rememberUpdatedState(onAdjustEnd)
    val currentOnSeekStart by rememberUpdatedState(onSeekStart)
    val currentOnSeekDelta by rememberUpdatedState(onSeekDelta)
    val currentOnSeekEnd by rememberUpdatedState(onSeekEnd)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(seekEnabled) {
                if (!seekEnabled) return@pointerInput
                // 横滑报的是**从按下点起的累计位移**，所以这里自己攒。
                // 攒的是**速度加权后**的等效位移：同样 300px，快划跨的时间明显更多。
                var accumulated = 0f
                val velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        accumulated = 0f
                        velocityTracker.resetTracking()
                        currentOnSeekStart()
                    },
                    onDragEnd = { currentOnSeekEnd() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val speed = abs(velocityTracker.calculateVelocity().x)
                        accumulated += dragAmount * seekSpeedGain(speed)
                        // 宽度**现取**：旋转屏幕后这一层会变宽变窄，协程启动时的快照会过期
                        currentOnSeekDelta(accumulated / size.width.toFloat().coerceAtLeast(1f))
                    },
                )
            }
            .pointerInput(Unit) {
                // 哪一半是按**down 点**定的：拖过中线也不改，否则中途会突然从亮度跳到音量
                var target = PlayerAdjustTarget.BRIGHTNESS
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        target = if (offset.x < size.width / 2f) {
                            PlayerAdjustTarget.BRIGHTNESS
                        } else {
                            PlayerAdjustTarget.VOLUME
                        }
                        currentOnAdjustStart(target)
                    },
                    onDragEnd = { currentOnAdjustEnd() },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        currentOnAdjustDelta(
                            target,
                            -dragAmount / size.height.toFloat().coerceAtLeast(1f),
                        )
                    },
                )
            },
    )
}

// ---------------------------------------------------------------------------
// 与接收端 `io.github.jqssun.airplay.ui.gestures.GestureDetectors` 同源的两个探测器。
//
// 为什么不直接用 foundation 自带的 `detect*DragGestures`：自带的那个会在
// `await*TouchSlopOrCancellation` 的回调里**先调一次 `on*Drag`**，之后返回时又调一次
// （同一段 overSlop 被算两遍），而且两次都发生在 `onDragStart` **之前** —— 我们的
// `onDragStart` 要负责定下「这一次到底在调亮度还是调音量」，顺序反了就会先按上一次的目标调一下。
// ---------------------------------------------------------------------------

private suspend fun PointerInputScope.detectHorizontalDragGestures(
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onHorizontalDrag: (change: PointerInputChange, dragAmount: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var overSlop = 0f
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        }
        if (drag != null && currentEvent.changes.count { it.pressed } == 1) {
            onDragStart(drag.position)
            onHorizontalDrag(drag, overSlop)
            horizontalDrag(drag.id) {
                onHorizontalDrag(it, it.positionChange().x)
                it.consume()
            }
            onDragEnd()
        }
    }
}

private suspend fun PointerInputScope.detectVerticalDragGestures(
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onVerticalDrag: (change: PointerInputChange, dragAmount: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var overSlop = 0f
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        }
        if (drag != null && currentEvent.changes.count { it.pressed } == 1) {
            onDragStart(drag.position)
            onVerticalDrag(drag, overSlop)
            verticalDrag(drag.id) {
                onVerticalDrag(it, it.positionChange().y)
                it.consume()
            }
            onDragEnd()
        }
    }
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
            .padding(horizontal = CastKitSpacing.space4, vertical = CastKitSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CastKitSpacing.space3),
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
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = ImmersiveColors.OnScrim,
        )
    }
}

/**
 * 左右滑调进度时的中央提示：方向箭头 + 目标时间 + 偏移量。
 *
 * 与 [PlayerAdjustIndicator] 同一个位置、同一套胶囊皮，两者互斥（同时只可能有一个手势在跑）。
 *
 * @param positionMs 手指当前指向的**目标**位置（不是正在播的位置）。
 * @param deltaMs 相对开始滑动那一刻的偏移，正数快进、负数后退。
 */
@Composable
fun PlayerSeekIndicator(
    positionMs: Long,
    deltaMs: Long,
    modifier: Modifier = Modifier,
) {
    val forward = deltaMs >= 0
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(ImmersiveColors.Scrim)
            .padding(horizontal = CastKitSpacing.space4, vertical = CastKitSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CastKitSpacing.space3),
    ) {
        Icon(
            imageVector = Icons.Filled.FastForward,
            contentDescription = null,
            tint = ImmersiveColors.OnScrim,
            // 后退就是把同一个箭头转 180°，不必再引一个图标
            modifier = Modifier
                .size(24.dp)
                .rotate(if (forward) 0f else 180f),
        )
        Text(
            text = formatSeekTime(positionMs),
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = ImmersiveColors.OnScrim,
        )
        // 不足 1 秒就滑一下（手指刚按下、位移还没到一个整数秒）时不显示 "+0s" 那种噪声
        if (abs(deltaMs) >= 1000L) {
            Text(
                text = (if (forward) "+" else "-") + formatSeekTime(abs(deltaMs)),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = ImmersiveColors.Accent,
            )
        }
    }
}

/** 提示里的时间：只到秒，超过一小时才带小时位。 */
private fun formatSeekTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 屏幕亮度：有 `WRITE_SETTINGS` 时调的是**系统亮度**，否则退回**窗口亮度**，退出播放不还原。
 *
 * 两条路，优先走第一条：
 * 1. 有 `WRITE_SETTINGS`（特殊权限，要去设置页授权）→ 直接写 `Settings.System.SCREEN_BRIGHTNESS`。
 *    精确、跨 ROM 都生效；
 * 2. 没权限 → 退回窗口级 `Window.screenBrightness`。**小米手机实测会把这个窗口值写进系统设置**，
 *    所以效果同样是"改系统亮度"；而在窗口级语义严格生效的 ROM（实测小米平板就是这样）上，
 *    它是"仅本 App 生效"，退出后由系统接管。
 *
 * [current] 必须与 [apply] 走同一条路（先看窗口覆盖），否则重复滑动会各自从不同基准起步。
 *
 * 之前那套"退出播放还原系统亮度"的补偿逻辑（回写原值 + 隔 250ms 交还控制权 + 读回校正）
 * 全部删掉了 —— 需求改成"亮度也是系统级"之后，那些都成了多余动作，而且它们本身就是
 * "最低亮度退出后变成最低+1"那个问题的来源。
 */
object SystemBrightness {

    /**
     * 当前亮度，0..1。
     *
     * **先看窗口级覆盖，再退回系统设置** —— 这个顺序是关键：
     * 没有 `WRITE_SETTINGS` 的机器上 [apply] 只写 `Window.screenBrightness`（窗口值），
     * 系统设置里那条根本不会动。如果这里仍去读系统设置，下一轮滑动就会从"系统里那个旧值"
     * 重新开始，用户看到的就是"明明刚调亮、再滑一次又从低处往上涨"。
     * 接收端自己的 AirPlay 播放器（`BrightnessState`）一直就是"有覆盖先用覆盖"的写法。
     */
    fun current(context: Context, activity: Activity?): Float {
        val override = activity?.window?.attributes?.screenBrightness
        // BRIGHTNESS_OVERRIDE_NONE 是 -1f，落在 0..1 之外，天然被排除
        if (override != null && override in 0f..1f) return override
        return currentRaw(context) / 255f
    }

    /** 系统设置里那条亮度的原始档位（0..255）。 */
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
 * 系统媒体音量（`STREAM_MUSIC`）：改的是**系统**音量，退出播放也保持，正是需求要的。
 *
 * 「无级」在这里有个平台上限：`setStreamVolume` 只接受整数档。本机实测媒体音量有
 * 100+ 档（MX Player 的日志里 index 能到 110），所以实际手感是连续的；
 * 档位少的机器（有些只有 15 档）就会一格一格跳 —— 这不是 App 能绕过的，
 * 只有把浮点值直接喂给播放器（`ExoPlayer.volume`）才能真无级，但那样就变成"仅播放界面"了。
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
