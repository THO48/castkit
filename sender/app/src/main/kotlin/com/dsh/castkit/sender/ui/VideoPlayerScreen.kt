package com.dsh.castkit.sender.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dsh.castkit.sender.PlayerOrientation
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.cast.CastBus
import com.dsh.castkit.sender.cast.CastMode
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.media.PlaybackProgress
import com.dsh.castkit.sender.net.LanCastDiscovery
import com.dsh.castkit.sender.ui.components.CastKitTopBar
import com.dsh.castkit.sender.ui.components.PlayerAdjustIndicator
import com.dsh.castkit.sender.ui.components.PlayerAdjustLayer
import com.dsh.castkit.sender.ui.components.PlayerAdjustTarget
import com.dsh.castkit.sender.ui.components.PlayerSeekIndicator
import com.dsh.castkit.sender.ui.components.SystemBrightness
import com.dsh.castkit.sender.ui.components.SystemVolume
import com.dsh.castkit.sender.ui.theme.CastKitMotion
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing
import com.dsh.castkit.sender.ui.theme.ImmersiveColors
import com.dsh.castkit.sender.ui.theme.PillShape
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 播放页两端的横向留白。 */
private val PlayerEdgePadding = 16.dp

/**
 * 矮屏（横屏手机）判据：可用高度低于这个值就用紧凑底栏。
 * 实测横屏手机 384dp、竖屏手机 853dp、平板横屏远大于它，所以这条只命中"横屏手机"。
 */
private const val COMPACT_HEIGHT_THRESHOLD_DP = 480

/** 紧凑版进度条的触摸行高：比常规的 48dp 矮一点，横屏下省出来的高度很关键。 */
private val COMPACT_SCRUB_HEIGHT = 32.dp

/**
 * 播放页浮层文字的投影。
 *
 * 顶栏/底栏改成**全透明**之后，白字是直接压在画面上的 —— 亮场景里没有底就没有对比度。
 * 用一层黑色投影代替整条 scrim 垫底（字幕的通行做法）：保住可读性，又不会退回成"黑板条"。
 * 阴影本身不占布局，所以不会把那 9 成屏高又吃回去。
 */
private val OverlayTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 1f),
    blurRadius = 7f,
)

/** 浮层文字：套上 [OverlayTextShadow]。 */
private fun TextStyle.onOverlay(): TextStyle = copy(shadow = OverlayTextShadow)

/** 播放页记住的方向 → Android 的方向常量。用 `SENSOR_*` 保留同方向内正反都能翻。 */
private fun PlayerOrientation.toActivityInfo(): Int = when (this) {
    PlayerOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    PlayerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
}

/** 诊断用日志标签（同 `LocalVideoPlayer` 的做法：排查真机问题全靠 logcat）。 */
private const val TAG = "VideoPlayerScreen"

/** 长按快进的倍速。3× 是主流视频 App 的常见值：够快，又不至于完全看不清内容。 */
private const val FAST_RATE = 3f

/** 快进/快退的步长。 */
private const val SEEK_STEP_MS = 10_000L

/** 双击跳转后中央提示停留多久。 */
private const val DOUBLE_TAP_HINT_MS = 700L

/** 双击分区：左 / 中 / 右各 1/3。中间那条留给"播放/暂停"，否则双击暂停就没地方点了。 */
private enum class DoubleTapZone { LEFT, CENTER, RIGHT }

private fun doubleTapZone(x: Float, width: Int): DoubleTapZone = when {
    width <= 0 -> DoubleTapZone.CENTER
    x < width / 3f -> DoubleTapZone.LEFT
    x > width * 2f / 3f -> DoubleTapZone.RIGHT
    else -> DoubleTapZone.CENTER
}

/**
 * 横滑调进度时，**一整屏宽**对应片长的几分之一。
 *
 * 取三分之一，也就是"整段片子 = 三次整屏滑动"，长短片的**手感比例一致**：
 * 短片一次滑一小段，长片同样的滑动距离就跨过相应更大的一段时间。
 *
 * 两版历史都是坑，记下来免得再走回去：
 * 1. 最早是「短片取片长本身」—— 85 秒的片源一屏宽 = 整片，轻滑一下就归零；
 * 2. 之后改成「片长与 90 秒取小」—— 补住了短片，却让**所有超过 4 分半的片子
 *    退化成同一个灵敏度**（一屏宽恒等于 90 秒），用户实测"长视频和短视频同样距离
 *    跳过的时间一样"就是这么来的。所以这次不再设上限，纯按比例。
 */
private const val SEEK_WINDOW_DIVISOR = 3

/**
 * 页面 3 —— 视频播放器（**覆盖层**，不是导航目的地）。
 *
 * 恒纯黑背景，不受浅色/深色模式影响（视频场景不存在浅色模式）。
 *
 * ## 相对改造前的四处结构性改动
 *
 * 1. **删掉重复入口**：改造前「投屏」与「切到横屏」在顶栏和底栏各有一份，
 *    一屏 4 个重复按钮。现在每个动作全页只出现一次——底栏五格承担全部动作，
 *    顶栏只剩「返回 + 文件名」。
 * 2. **进度条重做**：改造前用的是 Miuix `Slider`（轨高 30dp、**完全不画 thumb**、
 *    没有 `onValueChangeFinished`，只能靠"停顿 250ms"猜松手）。现在是自绘的
 *    **3dp 细轨 + 12dp 圆形滑块**，手势直接给出按下/拖动/抬起三个时机，
 *    不再需要防抖猜测。
 * 3. **新增 ±10 秒**。
 * 4. **投送中不再是"死帧"**：改造前开投只调用 `player.pause()`，本机 `SurfaceView`
 *    仍留在视图树里，用户看到的是**本机视频暂停住的最后一帧**叠加遥控控件——
 *    画面是死的，容易被误读成"投屏失败"。现在投送中隐藏本机 Surface，
 *    改渲染沉浸态（设备名 + 每秒回报的进度）。
 *
 * 常驻的底部提示语也删掉了，改成 Snackbar；它原本存在的理由就是解释上面第 4 条
 * 那个设计缺陷，缺陷修好之后它不需要常驻。
 *
 * ## 1:1 保留的逻辑（一行没改）
 *
 * - 遥控器态：进度/时长/播放状态全部取自接收端（`remote*`），拖动即下发 seek；
 * - 时长兜底：接收端回报优先，回报未到时用本机播放器已知时长，避免滑块范围退化成 0..1；
 * - 停止投送后把本机进度对齐到接收端停下的位置，接着看不会跳回开头；
 * - 开投时本机 `player.pause()`（避免手机和接收端同时出声）；
 * - `localPlayerActive` 标志：告诉 `CastService` 本机旋转是"看片用"的，别带动镜像画面；
 * - 退出播放页即结束投送；退出时把方向复位成 `UNSPECIFIED`。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoPlayerScreen(
    uri: Uri,
    title: String,
    vm: PlayerViewModel,
    discovery: LanCastDiscovery,
    onCastTo: (host: String, port: Int, positionMs: Long) -> Unit,
    onStopCast: () -> Unit,
    onRemoteToggle: () -> Unit,
    onRemoteSeek: (Long) -> Unit,
    onClose: () -> Unit,
    /** 上一个 / 下一个视频；列表为空或已在两端时对应回调不会触发（按钮也会置灰）。 */
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * 系统栏（顶部状态栏 + 底部手势条）的控制器。
     *
     * 可见性**跟着播放器控制栏走**：控制栏出现时系统栏一起显示，控制栏收起才一起隐藏
     * （不是进播放页就一律隐藏 —— 那样用户看不到时间/电量/通知）。
     * 提到这里而不是放在某个 effect 里，是因为"跟着 [overlayVisible] 显隐"和"退出时还原"
     * 两处都要用同一个实例。
     */
    val insetsController = remember(activity) {
        activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
    }

    val state by vm.state.collectAsState()
    val castState by CastBus.state.collectAsState()
    val receivers by discovery.receivers.collectAsState()

    // Snackbar 由播放页自己持有并渲染：外壳的 SnackbarHost 在 Scaffold 里，
    // 而播放页是画在 Scaffold **之上**的不透明覆盖层，外壳的 Snackbar 会被它盖住。
    val snackbarHostState = remember { SnackbarHostState() }

    var overlayVisible by remember { mutableStateOf(true) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var showDeviceDialog by remember { mutableStateOf(false) }

    /** 长按快进是否正按着 —— 只用来决定要不要显示那个「3× 快进中」提示。 */
    var speedHeld by remember { mutableStateOf(false) }

    // 竖滑调整：**两个都是系统级的**（亮度也是），退出播放不还原。
    // 值都是 0..1 的浮点，滑动时连续变化；实际落到系统上会被量化成整数档（见 SystemBrightness 注释）。
    var adjusting by remember { mutableStateOf<PlayerAdjustTarget?>(null) }
    var brightness by remember { mutableStateOf(SystemBrightness.current(context, activity)) }
    var volume by remember { mutableStateOf(SystemVolume.current(context)) }

    // 横滑调进度：`seekPreviewMs` 只是给中央提示看的**目标位置**，跳转本身在滑动过程中就实时做了。
    // `seekStartMs` 是按下那一刻的播放位置 —— 目标一律从它算起，**不能**累加当前位置，否则边跳边算会自激。
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var seekStartMs by remember { mutableStateOf(0L) }
    /** 双击跳转的序号：每双击一次 +1，用来重新计时把中央提示收掉（连续双击不会提前消失）。 */
    var doubleTapTick by remember { mutableIntStateOf(0) }

    // 双击跳转的提示 700ms 后自己收掉（横滑那条由 onSeekEnd 负责，双击没有"松手"这一刻）
    LaunchedEffect(doubleTapTick) {
        if (doubleTapTick > 0) {
            delay(DOUBLE_TAP_HINT_MS)
            seekPreviewMs = null
        }
    }

    val casting = castState.mode == CastMode.VIDEO &&
        (castState.phase == CastPhase.RUNNING || castState.phase == CastPhase.CONNECTING)
    val remote = casting

    LaunchedEffect(uri) { vm.play(uri, title) }

    // 进播放页时把上次手动切过的方向贴回来；没切过就什么都不做（跟随系统）。
    // 用 LaunchedEffect 而不是塞进下面 DisposableEffect 的 body：后者是在组合期跑副作用，
    // 而 requestedOrientation 会触发配置变更，放在组合之后更稳。
    LaunchedEffect(Unit) {
        val saved = Prefs.playerOrientation(context)
        Log.d(
            TAG,
            "进播放页：方向偏好=$saved activity=${activity != null} " +
                "screenHeightDp=${configuration.screenHeightDp}",
        )
        saved?.let { activity?.requestedOrientation = it.toActivityInfo() }
    }

    DisposableEffect(Unit) {
        // 告诉 CastService：本机旋转是"看片用"的，别带动镜像画面
        CastBus.update { it.copy(localPlayerActive = true) }
        // 从屏幕边缘往里划可以临时唤出系统栏（看通知/电量），但不改变播放器控制栏的状态
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            // 万一在长按状态里退出（比如被系统收回），变速要复位，不能把 3× 留给下一个片源
            vm.setSpeed(1f)
            // 亮度/音量都是系统级的，**不还原** —— 用户滑到哪就是哪（和系统音量条一个语义）
            vm.release()
            CastBus.update { it.copy(localPlayerActive = false) }
            // 退出时必须显式把系统栏放回去：播放页是叠在 MainActivity 上的覆盖层而不是独立
            // Activity，onDispose 时 Activity 还活着，不会靠窗口重建把它带回来。
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }

    // 投送中这里就是遥控器：进度条要显示**接收端**的进度，而不是本机的
    val position = scrubMs ?: if (remote) castState.remotePositionMs else state.positionMs
    val duration = when {
        remote && castState.remoteDurationMs > 0 -> castState.remoteDurationMs
        state.durationMs > 0 -> state.durationMs
        else -> castState.remoteDurationMs
    }
    val playing = if (remote) castState.remotePlaying else state.playing
    val buffering = if (remote) castState.remoteBuffering else state.buffering

    // 横滑一整屏宽对应多少时长：片长的 1/3，**不设上限**（见常量注释）
    val seekWindowMs = (duration / SEEK_WINDOW_DIVISOR).coerceAtLeast(1L)
    // 投送中不做遥控（见 seekToLive 注释），时长为 0 时也没得跳
    val seekGestureEnabled = duration > 0L && !remote

    // 投送状态切换时给 Snackbar，替代原来那行常驻提示
    var wasCasting by remember { mutableStateOf(false) }
    // 设备名优先：Discovery 列表里还留着这台接收端时用它的友好名（"Android AirPlay"），
    // 匹配不上才退回 IP。改造前直接用 `castState.host`，用户看到的是
    // "正在投送到 10.0.2.15"，而设计文档 §8.3 要求显示设备名。
    // 不需要新增持久化——`CastState` 只带 host/port，但设备名从发现列表里就能配对。
    val receiverName = receivers.firstOrNull { it.host == castState.host }?.name
        ?: castState.host.ifBlank { stringResource(R.string.cast_receiver) }
    val startedText = stringResource(R.string.snackbar_cast_started, receiverName)
    val stoppedText = stringResource(
        R.string.snackbar_cast_stopped,
        formatTimeCode(castState.remotePositionMs),
    )
    // 接收端断开后本机接着播（见下面那段 effect）：提示语要说清"现在在手机上放"
    val takeOverText = stringResource(
        R.string.snackbar_cast_takeover,
        formatTimeCode(castState.remotePositionMs),
    )
    // 打开视频时如果是从上次的进度接着播，提示一次
    val resumeText = stringResource(
        R.string.snackbar_resume_progress,
        formatTimeCode(state.resumedFromMs),
    )

    LaunchedEffect(remote) {
        if (wasCasting && !remote) {
            // 停止投送后把本机进度对齐到接收端停下的位置，接着看不会跳回开头
            val pos = castState.remotePositionMs
            val dur = castState.remoteDurationMs
            // 接收端已经放到（或接近）结尾时不再自动起播：否则手机上会把最后几秒又放一遍
            val takeOver = pos > 0 && (dur <= 0 || pos < dur - PlaybackProgress.nearEndMs(dur))
            if (pos > 0) vm.seekTo(pos)
            if (takeOver) vm.resume()
            snackbarHostState.showSnackbar(if (takeOver) takeOverText else stoppedText)
        } else if (!wasCasting && remote) {
            snackbarHostState.showSnackbar(startedText)
        }
        wasCasting = remote
    }

    // 上次看到一半：进来就接着播了，这里只负责说一声（key 用 uri + 进度，不会重复弹）
    LaunchedEffect(state.uri, state.resumedFromMs) {
        if (state.resumedFromMs > 0) snackbarHostState.showSnackbar(resumeText)
    }

    // 控制栏自动隐藏（设计系统定为 3 秒；拖动进度条时不隐藏）
    LaunchedEffect(playing, overlayVisible, scrubMs) {
        if (overlayVisible && scrubMs == null) {
            delay(CastKitMotion.OVERLAY_AUTO_HIDE_MS)
            overlayVisible = false
        }
    }

    // 系统栏跟着控制栏显隐。控制栏是 AnimatedVisibility 淡入淡出的，系统栏的显隐由 WindowInsets
    // 控制器自己带系统动画，两者时间尺度接近，不需要额外对齐。
    LaunchedEffect(insetsController, overlayVisible) {
        if (overlayVisible) {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler { onClose() }

    fun seekBy(deltaMs: Long) {
        val target = (position + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        if (remote) onRemoteSeek(target) else vm.seekTo(target)
    }

    /**
     * 双击左/右 1/3：跳 ±10 秒，并在画面中央闪一下「目标时间 + 偏移」
     * （复用横滑那个提示 —— 手感和横滑一致，用户不用学第二套反馈）。
     *
     * 提示的收尾：横滑由 `onSeekEnd` 清，双击没有"松手"这一刻，所以靠 [doubleTapTick] 起一个
     * 定时器自己收掉。用计数而不是布尔，是为了连续双击能重新计时。
     */
    fun doubleTapSeek(deltaMs: Long) {
        if (duration <= 0L) return
        val target = (position + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        seekStartMs = position
        seekBy(deltaMs)          // 投送中它控的是接收端，与底栏 ±10 秒键一致
        seekPreviewMs = target
        doubleTapTick++
    }

    /**
     * 横滑调进度：**边滑边跳**（不是松手才跳），而且**只调本机**。
     *
     * 不做遥控是有意的：投送中发送端只是一块遥控面板，那里没有画面可对着滑；
     * 要调接收端进度仍然走底栏进度条和 ±10 秒按钮。所以整个横滑手势在投送态直接不启动
     * （见下面 `seekEnabled`）。
     *
     * 目标一律从**按下那一刻的位置** `seekStartMs` 算起 —— 如果拿当前 `position` 累加，
     * 每跳一次位置就变一次，下一帧又在这个新位置上再加，会自激跑飞。
     */
    fun seekToLive(fraction: Float) {
        if (duration <= 0L) return
        val t = (seekStartMs + (fraction * seekWindowMs).toLong())
            .coerceIn(0L, duration.coerceAtLeast(0L))
        seekPreviewMs = t
        vm.seekTo(t)
    }

    // 长按快进：按下切到 FAST_RATE，松手回 1.0。
    //
    // **只在投送时**才需要它 —— 那时本机才是那块在放的屏。开始投屏之后画面在接收端，
    // 发送端这时只是个遥控器，按需求不做快进（长按不会有任何反应）。
    //
    // 手势拆成两半是因为 `detectTapGestures` 的 API 形状：`onLongPress` 是普通 lambda
    // （拿不到 `awaitRelease`），只有 `onPress` 是 `PressGestureScope` 的挂起 lambda。
    // 所以「开始」在 onLongPress，「松手」在 onPress 里等 `tryAwaitRelease()` 返回后收尾。
    // `speedHeld` 既是收尾的判断依据，也驱动那个「3× 快进中」提示。
    fun fastForward(on: Boolean) {
        if (remote) return
        vm.setSpeed(if (on) FAST_RATE else 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ImmersiveColors.Background)
            // 单击切换控制栏显隐；**双击按位置分区**：左 1/3 = 后退 10 秒、中间 1/3 = 播放/暂停、
            // 右 1/3 = 前进 10 秒（中间那条留着，免得丢了"双击暂停"）；长按 = 快进。
            // 用 detectTapGestures 而不是 clickable + combinedClickable：只有它能拿到
            // "双击"和"长按开始 / 松手"这几个时机，而快进必须成对。
            //
            // 代价说明：一旦传了 onDoubleTap，`onTap` 就要等一个双击超时（约 300ms）才能确定
            // "没有第二下"，所以单击唤出控制栏会比以前慢一点点。这是"单击 / 双击并存"的固有代价，
            // 换任何实现都一样（除非允许第一下就先把控制栏翻出来、第二下再翻回去，那样会闪一下）。
            .pointerInput(remote) {
                detectTapGestures(
                    onTap = { overlayVisible = !overlayVisible },
                    onDoubleTap = { offset ->
                        when (doubleTapZone(offset.x, size.width)) {
                            DoubleTapZone.LEFT -> doubleTapSeek(-SEEK_STEP_MS)
                            DoubleTapZone.RIGHT -> doubleTapSeek(SEEK_STEP_MS)
                            // 投送中本机是遥控器，双击暂停的是**接收端**（和底栏那个播放键同一个动作）
                            DoubleTapZone.CENTER -> if (remote) onRemoteToggle() else vm.toggle()
                        }
                    },
                    onLongPress = {
                        if (!remote && !speedHeld) {
                            speedHeld = true
                            fastForward(true)
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (speedHeld) {
                            speedHeld = false
                            fastForward(false)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!remote) {
            // 本机播放画面：按视频自身比例居中，letterbox 到黑底上。
            // 投送中**不渲染**——那时这块画面是死的，见类注释第 4 条。
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { sv ->
                        sv.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                vm.setSurfaceHolder(holder)
                                vm.setSurface(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) {
                                vm.setSurfaceHolder(holder)
                                vm.setSurface(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) =
                                vm.setSurface(null)
                        })
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.aspect, matchHeightConstraintsFirst = state.aspect < 1f),
            )
        } else {
            CastingImmersivePanel(receiverName = receiverName)
        }

        if (buffering) {
            CircularProgressIndicator(
                color = ImmersiveColors.OnScrim,
                modifier = Modifier.padding(CastKitSpacing.space6),
            )
        }

        state.error?.let { message ->
            if (!remote) {
                Text(
                    text = message,
                    style = CastKitTheme.typography.bodyMedium,
                    color = ImmersiveColors.Error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(CastKitSpacing.space6),
                )
            }
        }

        // 竖滑调整 + 横滑调进度：铺在画面之上、控制栏之下。
        // 放在这里（而不是加在父 Box 的手势里）是为了不跟"单击/双击/长按"那套打架，理由见 PlayerAdjustLayer。
        PlayerAdjustLayer(
            onAdjustStart = { target ->
                adjusting = target
                // 每次开始滑动都以当前实际值起步，避免上次滑到哪就永远从哪开始
                when (target) {
                    PlayerAdjustTarget.BRIGHTNESS -> brightness = SystemBrightness.current(context, activity)
                    PlayerAdjustTarget.VOLUME -> volume = SystemVolume.current(context)
                }
            },
            onAdjustDelta = { target, delta ->
                when (target) {
                    PlayerAdjustTarget.BRIGHTNESS -> {
                        brightness = (brightness + delta).coerceIn(0f, 1f)
                        SystemBrightness.apply(context, activity, brightness)
                    }

                    PlayerAdjustTarget.VOLUME -> {
                        volume = (volume + delta).coerceIn(0f, 1f)
                        SystemVolume.apply(context, volume)
                    }
                }
            },
            onAdjustEnd = { adjusting = null },
            onSeekStart = {
                // 长按快进和横滑同时活着的话（按住不放再横向拖），让 seek 赢：
                // 否则松手时既要回 1× 又要落定进度，两个动效会打架
                if (speedHeld) {
                    speedHeld = false
                    fastForward(false)
                }
                seekStartMs = position
                seekPreviewMs = seekStartMs
            },
            onSeekDelta = { fraction -> seekToLive(fraction) },
            onSeekEnd = { seekPreviewMs = null },
            seekEnabled = seekGestureEnabled,
        )

        // 竖滑时的中央提示（与「3× 快进中」同一个位置，两者不会同时出现）
        adjusting?.let { target ->
            PlayerAdjustIndicator(
                target = target,
                value = when (target) {
                    PlayerAdjustTarget.BRIGHTNESS -> brightness
                    PlayerAdjustTarget.VOLUME -> volume
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 横滑调进度时的中央提示：跟手显示目标时间与偏移量
        seekPreviewMs?.let { target ->
            PlayerSeekIndicator(
                positionMs = target,
                deltaMs = target - seekStartMs,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 长按快进的提示：只在按住期间出现，压在画面正中
        if (speedHeld) {
            Text(
                text = stringResource(R.string.player_speed_indicator, FAST_RATE.toInt()),
                style = CastKitTheme.typography.titleSmall,
                color = ImmersiveColors.OnScrim,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(PillShape)
                    .background(ImmersiveColors.Scrim)
                    .padding(horizontal = CastKitSpacing.space4, vertical = CastKitSpacing.space2),
            )
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                PlayerTopBar(
                    title = state.title.ifBlank { title },
                    onBack = onClose,
                )
                // 切横/竖屏贴在顶栏正下方（原来挤在底栏五格里，与播放控制抢注意力）。
                // 只有图标不带文字：文字会跟标题抢读，方向本身看画面朝向就知道。
                PlayerFloatingAction(
                    icon = Icons.Filled.ScreenRotation,
                    label = stringResource(
                        if (isLandscape) R.string.action_to_portrait else R.string.action_to_landscape,
                    ),
                    onClick = {
                        val target = if (isLandscape) {
                            PlayerOrientation.PORTRAIT
                        } else {
                            PlayerOrientation.LANDSCAPE
                        }
                        activity?.requestedOrientation = target.toActivityInfo()
                        // 记住这次选择：下次进播放页自动应用（退出播放页仍恢复跟随系统）
                        Prefs.setPlayerOrientation(context, target)
                    },
                    modifier = Modifier.padding(
                        start = PlayerEdgePadding,
                        top = CastKitSpacing.space1,
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 投屏贴在底栏上方、靠左下角（Column 默认左对齐，给一个左侧内边距即可）
                PlayerFloatingAction(
                    icon = if (casting) Icons.Filled.Stop else Icons.Filled.Cast,
                    label = stringResource(
                        if (casting) R.string.action_stop_video else R.string.action_cast,
                    ),
                    text = if (casting) stringResource(R.string.player_label_stop) else null,
                    active = casting,
                    onClick = { if (casting) onStopCast() else showDeviceDialog = true },
                    modifier = Modifier.padding(
                        start = PlayerEdgePadding,
                        bottom = CastKitSpacing.space1,
                    ),
                )

                PlayerBottomControls(
                    positionMs = position,
                    durationMs = duration,
                    playing = playing,
                    hasPrev = hasPrev,
                    hasNext = hasNext,
                    onScrub = { scrubMs = it },
                    onScrubFinished = {
                        val target = scrubMs
                        if (target != null) {
                            if (remote) onRemoteSeek(target) else vm.seekTo(target)
                        }
                        scrubMs = null
                    },
                    onPrev = onPrev,
                    onSeekBack = { seekBy(-SEEK_STEP_MS) },
                    onPlayPause = { if (remote) onRemoteToggle() else vm.toggle() },
                    onSeekForward = { seekBy(SEEK_STEP_MS) },
                    onNext = onNext,
                    // 矮屏（横屏手机）用紧凑版，否则底栏 + 悬浮钮会顶到顶栏那一条上去
                    compact = configuration.screenHeightDp < COMPACT_HEIGHT_THRESHOLD_DP,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 220.dp),
        )
    }

    DevicePickerDialog(
        show = showDeviceDialog,
        receivers = receivers,
        onSelect = { device ->
            showDeviceDialog = false
            // 投到平板后本机暂停：否则手机和平板会同时出声
            vm.pause()
            onCastTo(device.host, device.port, state.positionMs)
        },
        onRefresh = { discovery.probeNow() },
        onDismiss = { showDeviceDialog = false },
    )
}

/** 顶栏：只有「返回 + 文件名（跑马灯）」，不再放重复的投屏/切方向入口。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerTopBar(title: String, onBack: () -> Unit) {
    CastKitTopBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
        titleContent = {
            Text(
                text = title,
                style = CastKitTheme.typography.titleSmall.onOverlay(),
                color = ImmersiveColors.OnScrim,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                // 播放器只有一个标题，跑马灯不会像网格那样变成持续动画开销
                modifier = Modifier.basicMarquee(),
            )
        },
        containerColor = Color.Transparent,
        contentColor = ImmersiveColors.OnScrim,
    )
}

/**
 * 底栏：进度条 + 时间码 + 五格控制排。
 *
 * @param compact 矮屏（横屏手机，实测只有 384dp 高）用的紧凑版：
 *        时间码挪到进度条**同一行的两端**、内边距与间距收紧、主按钮缩到 56dp。
 *        不做这个的话底栏约 172dp、加上悬浮钮和手势条内边距能到 262dp，
 *        会跟顶部那条（顶栏 + 切方向钮 ≈ 154dp）叠在一起 —— 实测两排悬浮钮直接压住了。
 */
@Composable
internal fun PlayerBottomControls(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onPrev: () -> Unit,
    onSeekBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 背景**全透明**：整条 scrim 底在横屏下会变成一大块压暗画面的黑板
            // （横屏 384dp 高里，顶栏+底栏+两个悬浮钮能占到 9 成），所以这里不铺底，
            // 只留 navigationBarsPadding 保证控件不被手势条压住。
            .navigationBarsPadding()
            .padding(
                horizontal = PlayerEdgePadding,
                vertical = if (compact) CastKitSpacing.space1 else CastKitSpacing.space2,
            ),
        verticalArrangement = Arrangement.spacedBy(
            if (compact) CastKitSpacing.space1 else CastKitSpacing.space2,
        ),
    ) {
        if (compact) {
            // 紧凑版：时间码与进度条并排一行，省掉整个时间码行的高度
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimeCode(positionMs),
                    style = CastKitTheme.typography.labelSmall.onOverlay(),
                    color = ImmersiveColors.TextSecondary,
                )
                ScrubBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onScrub = onScrub,
                    onScrubFinished = onScrubFinished,
                    modifier = Modifier
                        .weight(1f)
                        .height(COMPACT_SCRUB_HEIGHT)
                        .padding(horizontal = CastKitSpacing.space2),
                )
                Text(
                    text = formatTimeCode(durationMs),
                    style = CastKitTheme.typography.labelSmall.onOverlay(),
                    color = ImmersiveColors.TextSecondary,
                )
            }
        } else {
            ScrubBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimeCode(positionMs),
                    style = CastKitTheme.typography.labelMedium.onOverlay(),
                    color = ImmersiveColors.TextSecondary,
                )
                Text(
                    text = formatTimeCode(durationMs),
                    style = CastKitTheme.typography.labelMedium.onOverlay(),
                    color = ImmersiveColors.TextSecondary,
                )
            }
        }

        // 五格：上一个视频 | 后退10 | 播放暂停 | 前进10 | 下一个视频。
        // 「切方向」在顶栏下方、「投屏」在本栏左上方，都是悬浮钮，不在这排里。
        // 宽度核算（常规档）：48×4 + 64 = 256dp，加两端 16dp 留白 = 288dp，
        // 在 360dp 窄屏上仍有余量，所以不需要缩小图标或收紧间距。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconSlot(
                icon = Icons.Filled.SkipPrevious,
                label = stringResource(R.string.player_prev_video),
                enabled = hasPrev,
                onClick = onPrev,
            )

            // ±10 秒不加文字标签：Replay10 / Forward10 图标本身带 "10"
            PlayerIconSlot(
                icon = Icons.Filled.Replay10,
                label = stringResource(R.string.player_rewind_10),
                onClick = onSeekBack,
            )

            PlayerPlayPauseButton(
                playing = playing,
                onClick = onPlayPause,
                size = if (compact) CastKitSizes.playerPrimaryButtonCompact else CastKitSizes.playerPrimaryButton,
            )

            PlayerIconSlot(
                icon = Icons.Filled.Forward10,
                label = stringResource(R.string.player_forward_10),
                onClick = onSeekForward,
            )

            PlayerIconSlot(
                icon = Icons.Filled.SkipNext,
                label = stringResource(R.string.player_next_video),
                enabled = hasNext,
                onClick = onNext,
            )
        }
    }
}

/**
 * 悬浮操作钮：顶栏下方 / 底栏上方那种「单独一个」的操作。
 *
 * 与底栏里那排 [PlayerIconSlot] 同一套视觉语言（24dp 图标 + 可选小字）。
 * **没有底色**：顶栏、底栏都改成全透明之后，单独给这两个钮垫一块胶囊黑底反而最显眼，
 * 所以图标和文字统一走「白色 + 投影」（[ShadowedIcon] / [onOverlay]），
 * 压在亮画面上也认得出，同时不盖住画面。
 */
@Composable
internal fun PlayerFloatingAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    active: Boolean = false,
) {
    val tint = if (active) ImmersiveColors.Accent else ImmersiveColors.OnScrim
    Row(
        modifier = modifier
            // 留着是为了让水波纹裁成胶囊形；没有底色不会再画出任何东西
            .clip(PillShape)
            .clickable(onClick = onClick)
            .height(CastKitSizes.minTouchTarget)
            .padding(horizontal = CastKitSpacing.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CastKitSpacing.space1),
    ) {
        ShadowedIcon(icon = icon, contentDescription = label, tint = tint)
        if (text != null) {
            Text(
                text = text,
                style = CastKitTheme.typography.labelSmall.onOverlay(),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * 浮层图标的投影分层：从「贴近本体、稍浓」到「越往下越淡」，叠出柔和感。
 *
 * `Icon` 不像 `Text` 那样有 `shadow` 参数，矢量路径也不好直接拿去 blur，
 * 所以用同一个图往下偏几层、逐层降透明度来近似 —— 方向与 [OverlayTextShadow] 一致
 * （只往下偏，不做四周围一圈的描边）。
 */
private val IconShadowLayers = listOf(
    1.dp to 0.55f,
    2.dp to 0.30f,
    3.dp to 0.15f,
)

/**
 * 带投影的浮层图标。
 *
 * @param shadowScale 传 0.38f 用来配合"置灰"态：投影跟着一起淡，否则不可用的图标
 *        反而被黑影衬得更显眼。
 */
@Composable
private fun ShadowedIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = CastKitSizes.playerSecondaryGlyph,
    tint: Color = ImmersiveColors.OnScrim,
    shadowScale: Float = 1f,
) {
    Box(modifier.size(size)) {
        IconShadowLayers.forEach { (dy, alpha) ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ImmersiveColors.Shadow.copy(alpha = alpha * shadowScale),
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = dy),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 投送中的沉浸态，替代原来那块"暂停住的死帧"。
 *
 * 用户在这里需要知道的只有三件事：投到哪了、现在控制的是谁、以及进度（进度条在下面）。
 */
@Composable
internal fun CastingImmersivePanel(receiverName: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = CastKitSpacing.space7),
    ) {
        Icon(
            imageVector = Icons.Filled.WifiTethering,
            contentDescription = null,
            tint = ImmersiveColors.Accent,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(CastKitSpacing.space4))
        Text(
            text = stringResource(R.string.casting_immersive_title),
            style = CastKitTheme.typography.bodyMedium,
            color = ImmersiveColors.TextSecondary,
        )
        Spacer(Modifier.height(CastKitSpacing.space1))
        Text(
            text = receiverName,
            style = CastKitTheme.typography.titleMedium,
            color = ImmersiveColors.OnScrim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(CastKitSpacing.space3))
        Text(
            text = stringResource(R.string.casting_immersive_desc),
            style = CastKitTheme.typography.bodySmall,
            color = ImmersiveColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 自绘进度条：**3dp 细轨 + 12dp 圆形滑块**。
 *
 * 为什么不用 M3 的 `Slider`：
 *  - 设计系统要求轨高 3dp、滑块 12dp，而 M3 的 `Slider` 在 1.3.x 上的默认轨高是 16dp、
 *    滑块 20dp，`thumb`/`track` 插槽的签名在 1.3/1.4 之间还变过，依赖它反而更脆；
 *  - 自绘能用同一段手势直接区分**按下 / 拖动 / 抬起**三个时机，
 *    于是"松手才下发 seek"不再需要改造前那种"停顿 250ms 猜松手"的防抖。
 *
 * 触摸区仍是整行 48dp 高（`minTouchTarget`），视觉上的细轨居中对齐其中。
 */
@Composable
private fun ScrubBar(
    positionMs: Long,
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(1f) }

    val maxMs = durationMs.coerceAtLeast(0L)
    val fraction = if (maxMs > 0L) {
        (positionMs.toFloat() / maxMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val thumbPx = with(density) { CastKitSizes.scrubThumb.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CastKitSizes.minTouchTarget)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(maxMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    fun emit(x: Float) {
                        if (maxMs > 0L) {
                            onScrub((x / widthPx * maxMs).toLong().coerceIn(0L, maxMs))
                        }
                    }

                    emit(down.position.x)
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            emit(change.position.x)
                            change.consume()
                        }
                    }
                    onScrubFinished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CastKitSizes.scrubTrackHeight)
                .clip(CircleShape)
                .background(ImmersiveColors.ScrubTrackInactive),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(CastKitSizes.scrubTrackHeight)
                .clip(CircleShape)
                .background(ImmersiveColors.Accent),
        )
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (fraction * widthPx - thumbPx / 2f).roundToInt(),
                        y = 0,
                    )
                }
                .size(CastKitSizes.scrubThumb)
                .clip(CircleShape)
                .background(ImmersiveColors.ScrubThumb),
        )
    }
}

/**
 * 次级图标槽：固定 48dp 见方的点击区，图标 24dp。
 *
 * @param text 图标下方的 11sp 极小文字；传 null 表示不放（±10 秒用不到，
 *        因为图标本身带 "10"）。
 * @param enabled false 表示这个操作当前没有意义（例如已经是列表第一个，"上一个"），
 *        图标降到 38% 不透明度并且不响应点击 —— 置灰而不是隐藏，
 *        这样控制排的格数和位置不会随播放位置跳来跳去。
 */
@Composable
private fun PlayerIconSlot(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(CastKitSizes.minTouchTarget)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // 和两个悬浮钮用同一套「白图标 + 投影」：底栏也是全透明的，白图标直接压画面
            ShadowedIcon(
                icon = icon,
                contentDescription = label,
                tint = when {
                    !enabled -> ImmersiveColors.OnScrim.copy(alpha = 0.38f)
                    active -> ImmersiveColors.Accent
                    else -> ImmersiveColors.OnScrim
                },
                // 置灰时投影一起淡下去，否则"不可用"的图标反而被黑影衬得更显眼
                shadowScale = if (enabled) 1f else 0.38f,
            )
        }
        if (text != null) {
            Text(
                text = text,
                style = CastKitTheme.typography.labelSmall,
                color = ImmersiveColors.TextSecondary,
                maxLines = 1,
            )
        }
    }
}

/** 播放/暂停：视觉主体，默认 64dp 实心圆 + 32dp 图标（矮屏紧凑版 56dp）。 */
@Composable
private fun PlayerPlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    size: Dp = CastKitSizes.playerPrimaryButton,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(ImmersiveColors.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (playing) R.string.action_pause else R.string.action_play,
            ),
            // 主按钮底色是浅蓝 #6FA8F5，配白字只有 2.44:1；
            // 用近黑得到 7.66:1（与设计系统里 onPrimary 的处理一致）
            tint = Color(0xFF0B1220),
            modifier = Modifier.size(CastKitSizes.playerPrimaryGlyph),
        )
    }
}

/** 时间码：0 也显示 00:00（`--:--` 会让人以为没在计时）。 */
private fun formatTimeCode(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
