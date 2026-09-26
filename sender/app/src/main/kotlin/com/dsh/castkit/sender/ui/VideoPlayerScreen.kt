package com.dsh.castkit.sender.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dsh.castkit.sender.R
import com.dsh.castkit.sender.cast.CastBus
import com.dsh.castkit.sender.cast.CastMode
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.net.LanCastDiscovery
import com.dsh.castkit.sender.ui.components.CastKitTopBar
import com.dsh.castkit.sender.ui.theme.CastKitMotion
import com.dsh.castkit.sender.ui.theme.CastKitSizes
import com.dsh.castkit.sender.ui.theme.CastKitSpacing
import com.dsh.castkit.sender.ui.theme.ImmersiveColors
import com.dsh.castkit.sender.ui.theme.PillShape
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 播放页两端的横向留白。 */
private val PlayerEdgePadding = 16.dp

/** 长按快进的倍速。3× 是主流视频 App 的常见值：够快，又不至于完全看不清内容。 */
private const val FAST_RATE = 3f

/** 快进/快退的步长。 */
private const val SEEK_STEP_MS = 10_000L

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

    val state by vm.state.collectAsState()
    val castState by CastBus.state.collectAsState()
    val receivers by discovery.receivers.collectAsState()

    // Snackbar 由播放页自己持有并渲染：外壳的 SnackbarHost 在 Scaffold 里，
    // 而播放页是画在 Scaffold **之上**的不透明覆盖层，外壳的 Snackbar 会被它盖住。
    val snackbarHostState = remember { SnackbarHostState() }

    var overlayVisible by remember { mutableStateOf(true) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var orientationHintShown by remember { mutableStateOf(false) }

    /** 长按快进是否正按着 —— 只用来决定要不要显示那个「3× 快进中」提示。 */
    var speedHeld by remember { mutableStateOf(false) }

    val casting = castState.mode == CastMode.VIDEO &&
        (castState.phase == CastPhase.RUNNING || castState.phase == CastPhase.CONNECTING)
    val remote = casting

    LaunchedEffect(uri) { vm.play(uri, title) }

    DisposableEffect(Unit) {
        // 告诉 CastService：本机旋转是"看片用"的，别带动镜像画面
        CastBus.update { it.copy(localPlayerActive = true) }
        onDispose {
            // 万一在长按状态里退出（比如被系统收回），变速要复位，不能把 3× 留给下一个片源
            vm.setSpeed(1f)
            vm.release()
            CastBus.update { it.copy(localPlayerActive = false) }
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
    LaunchedEffect(remote) {
        if (wasCasting && !remote) {
            // 停止投送后把本机进度对齐到接收端停下的位置，接着看不会跳回开头
            val pos = castState.remotePositionMs
            if (pos > 0) vm.seekTo(pos)
            snackbarHostState.showSnackbar(stoppedText)
        } else if (!wasCasting && remote) {
            snackbarHostState.showSnackbar(startedText)
        }
        wasCasting = remote
    }

    // 控制栏自动隐藏（设计系统定为 3 秒；拖动进度条时不隐藏）
    LaunchedEffect(playing, overlayVisible, scrubMs) {
        if (overlayVisible && scrubMs == null) {
            delay(CastKitMotion.OVERLAY_AUTO_HIDE_MS)
            overlayVisible = false
        }
    }

    BackHandler { onClose() }

    fun seekBy(deltaMs: Long) {
        val target = (position + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        if (remote) onRemoteSeek(target) else vm.seekTo(target)
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
            // 单击切换控制栏显隐；长按 = 快进（按住期间持续，松手恢复）。
            // 用 detectTapGestures 而不是 clickable + combinedClickable：只有它能拿到
            // "长按开始 / 松手"这两个时机，而快进必须成对。
            .pointerInput(remote) {
                detectTapGestures(
                    onTap = { overlayVisible = !overlayVisible },
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
                // 切横/竖屏贴在顶栏正下方（原来挤在底栏五格里，与播放控制抢注意力）
                PlayerFloatingAction(
                    icon = Icons.Filled.ScreenRotation,
                    label = stringResource(
                        if (isLandscape) R.string.action_to_portrait else R.string.action_to_landscape,
                    ),
                    text = stringResource(
                        if (isLandscape) R.string.player_label_portrait else R.string.player_label_landscape,
                    ),
                    onClick = {
                        activity?.requestedOrientation = if (isLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                        // 只在第一次提示一次，之后不再打扰
                        if (!orientationHintShown) orientationHintShown = true
                    },
                    modifier = Modifier.padding(
                        start = PlayerEdgePadding,
                        top = CastKitSpacing.space2,
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
                        bottom = CastKitSpacing.space2,
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
                )
            }
        }

        // 首次切方向时的说明（原本是常驻的一行字，现在只在需要时出现一次）
        val orientationHint = stringResource(R.string.snackbar_orientation)
        LaunchedEffect(orientationHintShown) {
            if (orientationHintShown) snackbarHostState.showSnackbar(orientationHint)
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
                style = CastKitTheme.typography.titleSmall,
                color = ImmersiveColors.OnScrim,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                // 播放器只有一个标题，跑马灯不会像网格那样变成持续动画开销
                modifier = Modifier.basicMarquee(),
            )
        },
        containerColor = ImmersiveColors.Scrim,
        contentColor = ImmersiveColors.OnScrim,
    )
}

/** 底栏：进度条 + 时间码 + 五格控制排。 */
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(ImmersiveColors.Scrim)
            .padding(horizontal = PlayerEdgePadding, vertical = CastKitSpacing.space3),
        verticalArrangement = Arrangement.spacedBy(CastKitSpacing.space2),
    ) {
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
                style = CastKitTheme.typography.labelMedium,
                color = ImmersiveColors.TextSecondary,
            )
            Text(
                text = formatTimeCode(durationMs),
                style = CastKitTheme.typography.labelMedium,
                color = ImmersiveColors.TextSecondary,
            )
        }

        // 五格：上一个视频 | 后退10 | 播放暂停(64dp) | 前进10 | 下一个视频。
        // 「切方向」在顶栏下方、「投屏」在本栏左上方，都是悬浮钮，不在这排里。
        // 宽度核算：48×4 + 64 = 256dp，加两端 16dp 留白 = 288dp，
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

            PlayerPlayPauseButton(playing = playing, onClick = onPlayPause)

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
 * 与底栏里那排 [PlayerIconSlot] 同一套视觉语言（24dp 图标 + 可选小字），
 * 区别是没有整条 scrim 垫底，所以自带一个胶囊形 scrim 背景 —— 否则压在亮画面上看不清。
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
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(ImmersiveColors.Scrim)
            .clickable(onClick = onClick)
            .height(CastKitSizes.minTouchTarget)
            .padding(horizontal = CastKitSpacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CastKitSpacing.space2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) ImmersiveColors.Accent else ImmersiveColors.OnScrim,
            modifier = Modifier.size(CastKitSizes.playerSecondaryGlyph),
        )
        if (text != null) {
            Text(
                text = text,
                style = CastKitTheme.typography.labelSmall,
                color = if (active) ImmersiveColors.Accent else ImmersiveColors.OnScrim,
                maxLines = 1,
            )
        }
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
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    !enabled -> ImmersiveColors.OnScrim.copy(alpha = 0.38f)
                    active -> ImmersiveColors.Accent
                    else -> ImmersiveColors.OnScrim
                },
                modifier = Modifier.size(CastKitSizes.playerSecondaryGlyph),
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

/** 播放/暂停：视觉主体，64dp 实心圆 + 32dp 图标。 */
@Composable
private fun PlayerPlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(CastKitSizes.playerPrimaryButton)
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
