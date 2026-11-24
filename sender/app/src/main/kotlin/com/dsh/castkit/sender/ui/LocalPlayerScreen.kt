package com.dsh.castkit.sender.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.cast.CastBus
import com.dsh.castkit.sender.cast.CastMode
import com.dsh.castkit.sender.cast.CastPhase
import com.dsh.castkit.sender.media.LocalVideoPlayer
import com.dsh.castkit.sender.media.VideoItem
import com.dsh.castkit.sender.media.VideoThumbnails
import com.dsh.castkit.sender.net.LanCastDiscovery
import com.dsh.castkit.sender.net.LanCastFileServer
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class GestureKind { NONE, SEEK, VOLUME, BRIGHTNESS }

private val SPEED_STEPS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 影院级应用内播放器 + 投屏遥控台：
 * - 沉浸式全屏（自动隐藏状态栏/导航栏）
 * - 完整手势控制：左划亮度、右划音量、横划拖进度、左右双击 ±10s、中双击暂停/播放、长按 2x 倍速
 * - 播放列表连播（上一集 / 下一集），投屏中切集会自动无缝投送到接收端
 * - 投屏时自动切换为专属遥控台（含远端音量 ±、远端倍速、切回本机无缝续播、最小化后台遥控）
 * - 自动记忆与恢复每个视频的播放进度
 */
@Composable
fun LocalPlayerScreen(
    uri: Uri,
    title: String,
    playlist: List<VideoItem>,
    openPickerOnStart: Boolean,
    discovery: LanCastDiscovery,
    onSelectVideo: (Uri, String) -> Unit,
    onCastTo: (uri: Uri, host: String, port: Int, name: String, positionMs: Long) -> Unit,
    onStopCast: () -> Unit,
    onRemoteToggle: () -> Unit,
    onRemoteSeek: (Long) -> Unit,
    onRemoteSeekBy: (Long) -> Unit,
    onRemoteSpeed: (Float) -> Unit,
    onRemoteVolumeDelta: (Boolean) -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val player = remember { LocalVideoPlayer(context) }
    val state by player.state.collectAsState()
    val castState by CastBus.state.collectAsState()
    val receivers by discovery.receivers.collectAsState()

    val currentIndex = remember(playlist, uri) {
        playlist.indexOfFirst { it.uri == uri }
    }
    val hasPrev = currentIndex > 0
    val hasNext = currentIndex in 0 until playlist.lastIndex

    var overlayVisible by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var fillScreen by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    val showDeviceDialog = remember { mutableStateOf(false) }

    val casting = castState.mode == CastMode.VIDEO &&
        (castState.phase == CastPhase.RUNNING || castState.phase == CastPhase.CONNECTING)

    var scrubMs by remember { mutableStateOf<Long?>(null) }

    // 手势 HUD 状态
    var gestureKind by remember { mutableStateOf(GestureKind.NONE) }
    var gestureSeekTargetMs by remember { mutableLongStateOf(0L) }
    var gestureSeekDeltaMs by remember { mutableLongStateOf(0L) }
    var gesturePercent by remember { mutableIntStateOf(0) }
    var doubleTapHint by remember { mutableStateOf<String?>(null) }
    var doubleTapTick by remember { mutableIntStateOf(0) }
    var speedBoostActive by remember { mutableStateOf(false) }
    var savedSpeedBeforeBoost by remember { mutableFloatStateOf(1f) }

    // 沉浸式全屏状态栏隐藏
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        controller?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            // 恢复窗口亮度覆盖
            window?.attributes = window?.attributes?.apply { screenBrightness = -1f }
        }
    }

    // 加载视频并恢复断点（如果当前已经在投送这个视频，本机不自动起播以免重音）
    LaunchedEffect(uri) {
        val resumeMs = Prefs.getVideoProgress(context, uri.toString())
        val isAlreadyCastingThis = CastBus.state.value.let {
            it.mode == CastMode.VIDEO && it.isActive && it.videoUri == uri.toString()
        }
        player.play(uri, title, startPositionMs = resumeMs)
        if (isAlreadyCastingThis) {
            delay(150)
            player.pause()
        }
    }

    // 入口为「一键投屏」时自动弹出设备选择（若仅发现 1 台在线设备也可直接选）
    LaunchedEffect(openPickerOnStart) {
        if (openPickerOnStart && !casting) {
            showDeviceDialog.value = true
        }
    }

    DisposableEffect(Unit) {
        CastBus.update { it.copy(localPlayerActive = true) }
        onDispose {
            val st = player.state.value
            val cs = CastBus.state.value
            val pos = if (cs.mode == CastMode.VIDEO && cs.isActive) cs.remotePositionMs else st.positionMs
            val dur = maxOf(st.durationMs, cs.remoteDurationMs)
            Prefs.saveVideoProgress(context, uri.toString(), pos, dur)
            player.release()
            CastBus.update { it.copy(localPlayerActive = false) }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    DisposableEffect(state.playing, casting) {
        view.keepScreenOn = state.playing || casting
        onDispose { view.keepScreenOn = false }
    }

    val remote = casting
    val position = scrubMs ?: if (remote) castState.remotePositionMs else state.positionMs
    val duration = when {
        remote && castState.remoteDurationMs > 0 -> castState.remoteDurationMs
        state.durationMs > 0 -> state.durationMs
        else -> castState.remoteDurationMs
    }
    val playing = if (remote) castState.remotePlaying else state.playing
    val buffering = if (remote) castState.remoteBuffering else state.buffering
    val currentSpeed = if (remote) castState.remoteSpeed else state.speed

    // 接收端在平板上主动切换了另一个视频时，同步更新本机遥控台显示的封面与标题
    LaunchedEffect(castState.videoUri, castState.videoTitle, remote) {
        if (remote && castState.videoUri.isNotBlank() && castState.videoUri != uri.toString()) {
            onSelectVideo(Uri.parse(castState.videoUri), castState.videoTitle)
        }
    }

    // 停止投送后把本机进度对齐到接收端停下的位置
    var wasCasting by remember { mutableStateOf(remote) }
    LaunchedEffect(remote) {
        if (wasCasting && !remote) {
            val pos = castState.remotePositionMs
            if (pos > 0) player.seekTo(pos)
        }
        wasCasting = remote
    }

    // 双击提示自动消失
    LaunchedEffect(doubleTapTick) {
        if (doubleTapHint != null) {
            delay(850)
            doubleTapHint = null
        }
    }

    // 播放中自动隐藏覆盖层
    LaunchedEffect(playing, overlayVisible, scrubMs, showSpeedMenu, remote) {
        if (!remote && playing && overlayVisible && scrubMs == null && !showSpeedMenu) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
        }
    }

    fun switchToIndex(targetIdx: Int) {
        val next = playlist.getOrNull(targetIdx) ?: return
        Prefs.saveVideoProgress(context, uri.toString(), position, duration)
        val nextTitle = runCatching { LanCastFileServer.describe(context, next.uri).name }.getOrDefault(next.name)
        onSelectVideo(next.uri, nextTitle)
        if (remote && castState.host.isNotBlank()) {
            val resumeMs = Prefs.getVideoProgress(context, next.uri.toString())
            onCastTo(next.uri, castState.host, castState.port, castState.targetName, resumeMs)
        }
    }

    BackHandler {
        if (remote) onMinimize() else onClose()
    }

    if (remote) {
        // ==================== 专属投屏遥控台模式 ====================
        RemoteCastConsole(
            uri = uri,
            title = if (castState.remoteBrowsing) "接收端正在浏览本机视频库…" else state.title.ifBlank { title },
            receiverName = castState.targetName.ifBlank { castState.host.ifBlank { "接收端" } },
            receiverAddress = if (castState.remoteBrowsing) "平板随时可自由点播其他视频" else if (castState.host.isNotBlank()) "${castState.host}:${castState.port}" else "",
            positionMs = position,
            durationMs = duration,
            playing = playing,
            buffering = buffering,
            speed = currentSpeed,
            hasPrev = hasPrev,
            hasNext = hasNext,
            onScrubChange = { scrubMs = it },
            onScrubFinish = {
                scrubMs?.let(onRemoteSeek)
                scrubMs = null
            },
            onTogglePlay = onRemoteToggle,
            onSeekBy = onRemoteSeekBy,
            onPrev = { switchToIndex(currentIndex - 1) },
            onNext = { switchToIndex(currentIndex + 1) },
            onSpeed = onRemoteSpeed,
            onVolumeUp = { onRemoteVolumeDelta(true) },
            onVolumeDown = { onRemoteVolumeDelta(false) },
            onSwitchDevice = { showDeviceDialog.value = true },
            onMinimize = onMinimize,
            onStopCastToLocal = {
                if (castState.remotePositionMs > 0) {
                    player.seekTo(castState.remotePositionMs)
                }
                onStopCast()
            },
            onCloseAndStop = onClose,
        )
    } else {
        // ==================== 影院级本地播放模式 ====================
        val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        var dragAccumX by remember { mutableFloatStateOf(0f) }
        var dragAccumY by remember { mutableFloatStateOf(0f) }
        var dragStartPosMs by remember { mutableLongStateOf(0L) }
        var dragStartVol by remember { mutableFloatStateOf(0f) }
        var dragStartBright by remember { mutableFloatStateOf(0.5f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(controlsLocked, duration) {
                    detectTapGestures(
                        onTap = {
                            if (showSpeedMenu) {
                                showSpeedMenu = false
                            } else {
                                overlayVisible = !overlayVisible
                            }
                        },
                        onDoubleTap = { offset ->
                            if (controlsLocked) return@detectTapGestures
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            when {
                                offset.x < w * 0.32f -> {
                                    player.seekBy(-10_000L)
                                    doubleTapHint = "-10 秒"
                                    doubleTapTick++
                                }
                                offset.x > w * 0.68f -> {
                                    player.seekBy(10_000L)
                                    doubleTapHint = "+10 秒"
                                    doubleTapTick++
                                }
                                else -> {
                                    player.toggle()
                                    overlayVisible = true
                                }
                            }
                        },
                        onLongPress = {
                            if (!controlsLocked && state.playing) {
                                savedSpeedBeforeBoost = state.speed
                                speedBoostActive = true
                                player.setSpeed(2.0f)
                            }
                        },
                        onPress = {
                            try {
                                awaitRelease()
                            } finally {
                                if (speedBoostActive) {
                                    speedBoostActive = false
                                    player.setSpeed(savedSpeedBeforeBoost)
                                }
                            }
                        },
                    )
                }
                .pointerInput(controlsLocked, duration) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            if (controlsLocked) return@detectDragGestures
                            dragAccumX = 0f
                            dragAccumY = 0f
                            gestureKind = GestureKind.NONE
                            dragStartPosMs = player.state.value.positionMs
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            dragStartVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                            val curBright = activity?.window?.attributes?.screenBrightness ?: -1f
                            dragStartBright = if (curBright >= 0f) curBright else 0.5f
                        },
                        onDrag = { change, dragAmount ->
                            if (controlsLocked) return@detectDragGestures
                            dragAccumX += dragAmount.x
                            dragAccumY += dragAmount.y
                            if (gestureKind == GestureKind.NONE) {
                                if (abs(dragAccumX) > 28f && abs(dragAccumX) > abs(dragAccumY)) {
                                    gestureKind = GestureKind.SEEK
                                } else if (abs(dragAccumY) > 28f) {
                                    gestureKind = if (change.position.x < size.width / 2f) {
                                        GestureKind.BRIGHTNESS
                                    } else {
                                        GestureKind.VOLUME
                                    }
                                }
                            }
                            when (gestureKind) {
                                GestureKind.SEEK -> {
                                    if (duration > 0) {
                                        val deltaMs = (dragAccumX / size.width.coerceAtLeast(1) * 90_000f).toLong()
                                        gestureSeekDeltaMs = deltaMs
                                        gestureSeekTargetMs = (dragStartPosMs + deltaMs).coerceIn(0L, duration)
                                    }
                                }
                                GestureKind.BRIGHTNESS -> {
                                    val delta = -dragAccumY / (size.height * 0.65f).coerceAtLeast(1f)
                                    val next = (dragStartBright + delta).coerceIn(0.02f, 1f)
                                    gesturePercent = (next * 100).toInt()
                                    activity?.window?.let { w ->
                                        w.attributes = w.attributes.apply { screenBrightness = next }
                                    }
                                }
                                GestureKind.VOLUME -> {
                                    val delta = -dragAccumY / (size.height * 0.65f).coerceAtLeast(1f)
                                    val next = (dragStartVol + delta).coerceIn(0f, 1f)
                                    gesturePercent = (next * 100).toInt()
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        (next * maxVol).toInt().coerceIn(0, maxVol),
                                        0,
                                    )
                                }
                                GestureKind.NONE -> Unit
                            }
                        },
                        onDragEnd = {
                            if (gestureKind == GestureKind.SEEK && duration > 0) {
                                player.seekTo(gestureSeekTargetMs)
                            }
                            gestureKind = GestureKind.NONE
                        },
                        onDragCancel = {
                            gestureKind = GestureKind.NONE
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // 视频渲染面（按容器宽高精准适配任意纵横比，且切换铺满/适应不销毁 SurfaceView）
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val safeAspect = if (state.aspect.isFinite() && state.aspect > 0.05f) state.aspect else 16f / 9f
                val videoModifier = if (fillScreen || maxWidth.value <= 0f || maxHeight.value <= 0f) {
                    Modifier.fillMaxSize()
                } else {
                    val containerAspect = maxWidth.value / maxHeight.value
                    if (containerAspect > safeAspect) {
                        Modifier.size(width = maxHeight * safeAspect, height = maxHeight)
                    } else {
                        Modifier.size(width = maxWidth, height = maxWidth / safeAspect)
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).also { sv ->
                            sv.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = player.setSurface(holder.surface)
                                override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                                    if (w > 0 && h > 0) player.setSurface(holder.surface)
                                }
                                override fun surfaceDestroyed(holder: SurfaceHolder) =
                                    player.clearSurface(holder.surface)
                            })
                        }
                    },
                    modifier = videoModifier,
                )
            }

            // 缓冲中
            if (buffering) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            }

            // 错误提示
            state.error?.let { msg ->
                Surface(
                    color = Color(0xCC1E1E1E),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        msg,
                        color = CastColors.ErrorRedDark,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }

            // 长按 2.0x 倍速 HUD
            AnimatedVisibility(
                visible = speedBoostActive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color(0xCC000000),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("2.0x 倍速播放中", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // 双击快进/快退 HUD
            AnimatedVisibility(
                visible = doubleTapHint != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xCC000000)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (doubleTapHint?.startsWith("-") == true) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
                            contentDescription = null,
                            tint = Color.White,
                        )
                        Text(
                            doubleTapHint.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // 滑动手势 HUD（进度 / 亮度 / 音量）
            AnimatedVisibility(
                visible = gestureKind != GestureKind.NONE,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xD90B0E14)) {
                    when (gestureKind) {
                        GestureKind.SEEK -> Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val sign = if (gestureSeekDeltaMs >= 0) "+" else "-"
                            Text(
                                "$sign${formatTimeCode(abs(gestureSeekDeltaMs))}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${formatTimeCode(gestureSeekTargetMs)} / ${formatTimeCode(duration)}",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        GestureKind.BRIGHTNESS, GestureKind.VOLUME -> Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (gestureKind == GestureKind.BRIGHTNESS) Icons.Rounded.Brightness6
                                else Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = null,
                                tint = Color.White,
                            )
                            Text(
                                "$gesturePercent%",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        GestureKind.NONE -> Unit
                    }
                }
            }

            // 锁屏状态下只显示解锁按钮
            AnimatedVisibility(
                visible = overlayVisible && controlsLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp),
            ) {
                FilledTonalIconButton(
                    onClick = { controlsLocked = false },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color(0xBB000000),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = "解锁控制栏")
                }
            }

            // 顶部 & 中央 & 底部完整控制栏
            AnimatedVisibility(
                visible = overlayVisible && !controlsLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 顶部栏
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
                        }
                        Text(
                            text = state.title.ifBlank { title },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // 倍速按钮
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0x66FFFFFF),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { showSpeedMenu = !showSpeedMenu },
                        ) {
                            Text(
                                text = formatSpeed(currentSpeed),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // 投屏主入口
                        FilledTonalButton(
                            onClick = { showDeviceDialog.value = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("投屏", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    // 倍速选择浮层
                    if (showSpeedMenu) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xEE111622),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 56.dp, end = 16.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SPEED_STEPS.forEach { spd ->
                                    val selected = abs(currentSpeed - spd) < 0.01f
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable {
                                                player.setSpeed(spd)
                                                showSpeedMenu = false
                                            },
                                    ) {
                                        Text(
                                            formatSpeed(spd),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 中央播放控制组
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (hasPrev) {
                            FilledTonalIconButton(
                                onClick = { switchToIndex(currentIndex - 1) },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0x88000000),
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一个视频")
                            }
                        }
                        FilledTonalIconButton(
                            onClick = { player.seekBy(-10_000L) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0x88000000),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Rounded.Replay10, contentDescription = "快退 10 秒")
                        }
                        FilledIconButton(
                            onClick = { player.toggle() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xCCFFFFFF),
                                contentColor = Color.Black,
                            ),
                            modifier = Modifier.size(64.dp),
                        ) {
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "暂停" else "播放",
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { player.seekBy(10_000L) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0x88000000),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Rounded.Forward10, contentDescription = "快进 10 秒")
                        }
                        if (hasNext) {
                            FilledTonalIconButton(
                                onClick = { switchToIndex(currentIndex + 1) },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0x88000000),
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(Icons.Rounded.SkipNext, contentDescription = "下一个视频")
                            }
                        }
                    }

                    // 底部进度条与工具栏
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                formatTimeCode(position),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = position.toFloat().coerceIn(0f, if (duration > 0) duration.toFloat() else 1f),
                                onValueChange = { scrubMs = it.toLong() },
                                onValueChangeFinished = {
                                    scrubMs?.let { player.seekTo(it) }
                                    scrubMs = null
                                },
                                valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                            )
                            Text(
                                formatTimeCode(duration),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = { controlsLocked = true }) {
                                    Icon(Icons.Rounded.LockOpen, contentDescription = "锁定控件", tint = Color.White)
                                }
                                IconButton(onClick = { fillScreen = !fillScreen }) {
                                    Icon(
                                        Icons.Rounded.AspectRatio,
                                        contentDescription = if (fillScreen) "适应屏幕" else "铺满屏幕",
                                        tint = if (fillScreen) MaterialTheme.colorScheme.primary else Color.White,
                                    )
                                }
                            }
                            Text(
                                text = if (fillScreen) "铺满模式" else "自适应比例 · 长按屏幕可 2x 加速",
                                color = Color.White.copy(alpha = 0.65f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            IconButton(
                                onClick = {
                                    activity?.requestedOrientation = if (isLandscape) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.ScreenRotation,
                                    contentDescription = if (isLandscape) "切回竖屏" else "切到横屏",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    DevicePickerDialog(
        show = showDeviceDialog,
        receivers = receivers,
        onSelect = { device ->
            showDeviceDialog.value = false
            player.pause()
            onCastTo(uri, device.host, device.port, device.name, position)
        },
        onRefresh = { discovery.probeNow() },
    )
}

/** 投屏中的专属遥控台视图（清晰直观地控制接收端播放、进度、倍速、音量与切集）。 */
@Composable
private fun RemoteCastConsole(
    uri: Uri,
    title: String,
    receiverName: String,
    receiverAddress: String,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    buffering: Boolean,
    speed: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    onScrubChange: (Long) -> Unit,
    onScrubFinish: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Float) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onSwitchDevice: () -> Unit,
    onMinimize: () -> Unit,
    onStopCastToLocal: () -> Unit,
    onCloseAndStop: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(initialValue = VideoThumbnails.cached(uri), uri) {
        value = VideoThumbnails.load(context, uri)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CastColors.DarkBg),
    ) {
        // 氛围封面背景
        thumb?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.22f)
                    .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC0A0D13), Color(0xE60A0D13), Color(0xFA0A0D13)),
                        ),
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 顶部栏：最小化（后台遥控） / 设备状态 / 切换设备
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "最小化到后台遥控", tint = Color.White)
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = liveGreenColor().copy(alpha = 0.16f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onSwitchDevice),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusPulseDot(color = liveGreenColor(), pulsing = true, size = 7.dp)
                        Text(
                            text = "正在投屏 · $receiverName",
                            color = liveGreenColor(),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = "切换设备", tint = liveGreenColor(), modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = onCloseAndStop) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = "停止投屏并退出", tint = CastColors.ErrorRedDark)
                }
            }

            Spacer(Modifier.weight(0.6f))

            // 视频预览卡片
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF151A25),
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .aspectRatio(16f / 9f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val bmp = thumb
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.CastConnected,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    if (buffering) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x77000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (receiverAddress.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "接收端原画质直播 · $receiverAddress",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.weight(0.7f))

            // 远端进度条
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatTimeCode(positionMs), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, if (durationMs > 0) durationMs.toFloat() else 1f),
                    onValueChange = { onScrubChange(it.toLong()) },
                    onValueChangeFinished = onScrubFinish,
                    valueRange = 0f..(if (durationMs > 0) durationMs.toFloat() else 1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = CastColors.DarkPrimary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                )
                Text(formatTimeCode(durationMs), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(14.dp))

            // 主播放控制行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(onClick = onPrev, enabled = hasPrev) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "上一个视频",
                        tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(30.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = { onSeekBy(-10_000L) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.size(50.dp),
                ) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "快退 10 秒")
                }
                FilledIconButton(
                    onClick = onTogglePlay,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = CastColors.DarkPrimary,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.size(68.dp),
                ) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        modifier = Modifier.size(36.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = { onSeekBy(10_000L) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.size(50.dp),
                ) {
                    Icon(Icons.Rounded.Forward10, contentDescription = "快进 10 秒")
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "下一个视频",
                        tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 远端音量 + 倍速卡片
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                            Text("倍速", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SPEED_STEPS.forEach { spd ->
                                val active = abs(speed - spd) < 0.01f
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (active) CastColors.DarkPrimary else Color.White.copy(alpha = 0.08f),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSpeed(spd) },
                                ) {
                                    Text(
                                        formatSpeed(spd),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("接收端音量", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(
                                onClick = onVolumeDown,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.12f),
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = "音量减", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("音量 -")
                            }
                            FilledTonalButton(
                                onClick = onVolumeUp,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.12f),
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "音量加", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("音量 +")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 底部动作：最小化 / 切回本机播放
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onMinimize,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) {
                    Text("后台继续投", color = Color.White)
                }
                Button(
                    onClick = onStopCastToLocal,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CastColors.ErrorRedLight,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) {
                    Text("停止并切回本机")
                }
            }
        }
    }
}

private const val OVERLAY_HIDE_MS = 4000L

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

private fun formatTimeCode(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
