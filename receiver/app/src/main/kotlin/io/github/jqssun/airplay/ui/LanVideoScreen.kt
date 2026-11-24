package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.net.RemoteLibraryState
import io.github.jqssun.airplay.net.RemoteThumbCache
import io.github.jqssun.airplay.net.RemoteVideoItem
import io.github.jqssun.airplay.net.RemoteVideoLibrary
import io.github.jqssun.airplay.renderer.LanVideoState
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class LanGestureKind { NONE, SEEK, VOLUME, BRIGHTNESS }

private val LAN_SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 升级版 CastKit 局域网「投视频文件」影院级播放页：
 * - 支持画中画模式（PiP）
 * - 手势控制：左划亮度、右划音量、横划拖进度、左右双击 ±10s、中双击暂停/播放
 * - 支持倍速调节（与发送端遥控同步）、画面比例切换（适应 / 铺满）、控件锁、屏幕旋转
 */
@Composable
fun LanVideoScreen(
    state: LanVideoState,
    remoteLibrary: RemoteLibraryState,
    isInPip: Boolean,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSelectRemoteVideo: (RemoteVideoItem) -> Unit,
    onExitToLibrary: () -> Unit,
    onPip: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var overlayVisible by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var fillScreen by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showLibraryDrawer by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val position = scrubMs ?: state.positionMs

    // 同目录上下集列表（根据发送端视频库自动推导）
    val currentItem = remember(remoteLibrary.items, remoteLibrary.activeId) {
        remoteLibrary.items.firstOrNull { it.id == remoteLibrary.activeId }
    }
    val siblingVideos = remember(remoteLibrary.items, currentItem) {
        if (currentItem != null) {
            RemoteVideoLibrary.videosIn(remoteLibrary.items, currentItem.folder)
        } else {
            remoteLibrary.items
        }
    }
    val currentIdx = remember(siblingVideos, currentItem) {
        if (currentItem == null) -1 else siblingVideos.indexOfFirst { it.id == currentItem.id }
    }
    val prevVideo = siblingVideos.getOrNull(currentIdx - 1)
    val nextVideo = siblingVideos.getOrNull(currentIdx + 1)

    var gestureKind by remember { mutableStateOf(LanGestureKind.NONE) }
    var gestureSeekTargetMs by remember { mutableLongStateOf(0L) }
    var gestureSeekDeltaMs by remember { mutableLongStateOf(0L) }
    var gesturePercent by remember { mutableIntStateOf(0) }
    var doubleTapHint by remember { mutableStateOf<String?>(null) }
    var doubleTapTick by remember { mutableIntStateOf(0) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var dragAccumX by remember { mutableFloatStateOf(0f) }
    var dragAccumY by remember { mutableFloatStateOf(0f) }
    var dragStartPosMs by remember { mutableLongStateOf(0L) }
    var dragStartVol by remember { mutableFloatStateOf(0f) }
    var dragStartBright by remember { mutableFloatStateOf(0.5f) }

    LaunchedEffect(state.playing, overlayVisible, scrubMs, showSpeedMenu, showLibraryDrawer) {
        if (state.playing && overlayVisible && scrubMs == null && !showSpeedMenu && !showLibraryDrawer) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
        }
    }

    LaunchedEffect(doubleTapTick) {
        if (doubleTapHint != null) {
            delay(850)
            doubleTapHint = null
        }
    }

    val view = LocalView.current
    DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = -1f }
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler {
        when {
            showLibraryDrawer -> showLibraryDrawer = false
            else -> onExitToLibrary()
        }
    }

    if (isInPip) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            VideoSurfaceView(
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
                aspectRatio = if (state.aspect > 0f) state.aspect else 16f / 9f,
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(controlsLocked, state.durationMs) {
                detectTapGestures(
                    onTap = {
                        if (showSpeedMenu) showSpeedMenu = false
                        else overlayVisible = !overlayVisible
                    },
                    onDoubleTap = { offset ->
                        if (controlsLocked) return@detectTapGestures
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        when {
                            offset.x < w * 0.32f -> {
                                onSeekBy(-10_000L)
                                doubleTapHint = "-10 秒"
                                doubleTapTick++
                            }
                            offset.x > w * 0.68f -> {
                                onSeekBy(10_000L)
                                doubleTapHint = "+10 秒"
                                doubleTapTick++
                            }
                            else -> {
                                onToggle()
                                overlayVisible = true
                            }
                        }
                    },
                )
            }
            .pointerInput(controlsLocked, state.durationMs) {
                detectDragGestures(
                    onDragStart = {
                        if (controlsLocked) return@detectDragGestures
                        dragAccumX = 0f
                        dragAccumY = 0f
                        gestureKind = LanGestureKind.NONE
                        dragStartPosMs = state.positionMs
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        dragStartVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                        val curBright = activity?.window?.attributes?.screenBrightness ?: -1f
                        dragStartBright = if (curBright >= 0f) curBright else 0.5f
                    },
                    onDrag = { change, dragAmount ->
                        if (controlsLocked) return@detectDragGestures
                        dragAccumX += dragAmount.x
                        dragAccumY += dragAmount.y
                        if (gestureKind == LanGestureKind.NONE) {
                            if (abs(dragAccumX) > 28f && abs(dragAccumX) > abs(dragAccumY)) {
                                gestureKind = LanGestureKind.SEEK
                            } else if (abs(dragAccumY) > 28f) {
                                gestureKind = if (change.position.x < size.width / 2f) {
                                    LanGestureKind.BRIGHTNESS
                                } else {
                                    LanGestureKind.VOLUME
                                }
                            }
                        }
                        when (gestureKind) {
                            LanGestureKind.SEEK -> {
                                if (state.durationMs > 0) {
                                    val deltaMs = (dragAccumX / size.width.coerceAtLeast(1) * 90_000f).toLong()
                                    gestureSeekDeltaMs = deltaMs
                                    gestureSeekTargetMs = (dragStartPosMs + deltaMs).coerceIn(0L, state.durationMs)
                                }
                            }
                            LanGestureKind.BRIGHTNESS -> {
                                val delta = -dragAccumY / (size.height * 0.65f).coerceAtLeast(1f)
                                val next = (dragStartBright + delta).coerceIn(0.02f, 1f)
                                gesturePercent = (next * 100).toInt()
                                activity?.window?.let { w ->
                                    w.attributes = w.attributes.apply { screenBrightness = next }
                                }
                            }
                            LanGestureKind.VOLUME -> {
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
                            LanGestureKind.NONE -> Unit
                        }
                    },
                    onDragEnd = {
                        if (gestureKind == LanGestureKind.SEEK && state.durationMs > 0) {
                            onSeek(gestureSeekTargetMs)
                        }
                        gestureKind = LanGestureKind.NONE
                    },
                    onDragCancel = { gestureKind = LanGestureKind.NONE },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        VideoSurfaceView(
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = if (state.aspect > 0f) state.aspect else 16f / 9f,
            applyAspectRatio = !fillScreen,
            modifier = if (fillScreen) Modifier.fillMaxSize() else Modifier,
        )

        if (state.buffering) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(52.dp))
        }

        state.error?.let { msg ->
            Surface(color = Color(0xCC1E1E1E), shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(24.dp)) {
                Text(
                    msg,
                    color = Color(0xFFFF6B6B),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }

        state.ended?.let { msg ->
            Surface(color = Color(0xCC000000), shape = RoundedCornerShape(14.dp)) {
                Text(
                    msg,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                )
            }
        }

        // 双击提示 HUD
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
                    Text(doubleTapHint.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // 滑动手势 HUD
        AnimatedVisibility(
            visible = gestureKind != LanGestureKind.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = Color(0xD90B0E14)) {
                when (gestureKind) {
                    LanGestureKind.SEEK -> Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val sign = if (gestureSeekDeltaMs >= 0) "+" else "-"
                        Text(
                            "$sign${formatTime(abs(gestureSeekDeltaMs))}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${formatTime(gestureSeekTargetMs)} / ${formatTime(state.durationMs)}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    LanGestureKind.BRIGHTNESS, LanGestureKind.VOLUME -> Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            if (gestureKind == LanGestureKind.BRIGHTNESS) Icons.Rounded.Brightness6
                            else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                        )
                        Text("$gesturePercent%", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                    LanGestureKind.NONE -> Unit
                }
            }
        }

        // 锁定状态仅显示解锁按钮
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
                Icon(Icons.Rounded.Lock, contentDescription = "解锁控件")
            }
        }

        // 完整控制浮层
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
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onExitToLibrary) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "退出当前视频", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            state.title.ifBlank { "局域网投屏视频" },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.width > 0 && state.height > 0) {
                            Text(
                                "原画直通 · ${state.width}×${state.height}",
                                color = Color.White.copy(alpha = 0.65f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    // 选片列表快捷入口（无需退出播放即可切其他视频）
                    if (remoteLibrary.connected) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable {
                                    showSpeedMenu = false
                                    showLibraryDrawer = true
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Text("选片", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    // 倍速选择胶囊
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color(0x55FFFFFF),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { showSpeedMenu = !showSpeedMenu },
                    ) {
                        Text(
                            formatLanSpeed(state.speed),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onPip) {
                        Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
                    }
                }

                // 倍速选择条
                if (showSpeedMenu) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xEE111622),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 60.dp, end = 16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            LAN_SPEED_OPTIONS.forEach { spd ->
                                val selected = abs(state.speed - spd) < 0.01f
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            onSpeedChange(spd)
                                            showSpeedMenu = false
                                        },
                                ) {
                                    Text(
                                        formatLanSpeed(spd),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // 中央播放按钮组（含上一集 / 下一集）
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (prevVideo != null) {
                        FilledTonalIconButton(
                            onClick = { onSelectRemoteVideo(prevVideo) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0x88000000),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.size(46.dp),
                        ) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = "上一个视频")
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { onSeekBy(-10_000L) },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0x88000000),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Rounded.Replay10, contentDescription = "快退 10 秒")
                    }
                    FilledIconButton(
                        onClick = onToggle,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xD9FFFFFF),
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier.size(68.dp),
                    ) {
                        Icon(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.playing) "暂停" else "播放",
                            modifier = Modifier.size(38.dp),
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onSeekBy(10_000L) },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0x88000000),
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.Rounded.Forward10, contentDescription = "快进 10 秒")
                    }
                    if (nextVideo != null) {
                        FilledTonalIconButton(
                            onClick = { onSelectRemoteVideo(nextVideo) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0x88000000),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.size(46.dp),
                        ) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = "下一个视频")
                        }
                    }
                }

                // 底部进度与工具栏
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = position.toFloat().coerceIn(0f, if (state.durationMs > 0) state.durationMs.toFloat() else 1f),
                            onValueChange = { scrubMs = it.toLong() },
                            onValueChangeFinished = {
                                scrubMs?.let(onSeek)
                                scrubMs = null
                            },
                            valueRange = 0f..(if (state.durationMs > 0) state.durationMs.toFloat() else 1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        )
                        Text(formatTime(state.durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(onClick = { controlsLocked = true }) {
                                Icon(Icons.Rounded.LockOpen, contentDescription = "锁定控件", tint = Color.White)
                            }
                            IconButton(onClick = { fillScreen = !fillScreen }) {
                                Icon(
                                    Icons.Rounded.AspectRatio,
                                    contentDescription = if (fillScreen) "适应比例" else "铺满屏幕",
                                    tint = if (fillScreen) MaterialTheme.colorScheme.primary else Color.White,
                                )
                            }
                        }
                        Text(
                            if (fillScreen) "铺满屏幕" else "原始比例",
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
                            Icon(Icons.Rounded.ScreenRotation, contentDescription = "旋转屏幕", tint = Color.White)
                        }
                    }
                }
            }
        }

        // ---- 播放页内右侧快捷选片抽屉 ----
        AnimatedVisibility(
            visible = showLibraryDrawer && remoteLibrary.connected,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PlayerLibraryDrawer(
                library = remoteLibrary,
                onSelect = { item ->
                    showLibraryDrawer = false
                    onSelectRemoteVideo(item)
                },
                onOpenFullLibrary = {
                    showLibraryDrawer = false
                    onExitToLibrary()
                },
                onClose = { showLibraryDrawer = false },
            )
        }
    }
}

/** 播放页内右侧半透明快捷选片抽屉：支持按目录进出、返回上一级、全部视频与全局搜索。 */
@Composable
private fun PlayerLibraryDrawer(
    library: RemoteLibraryState,
    onSelect: (RemoteVideoItem) -> Unit,
    onOpenFullLibrary: () -> Unit,
    onClose: () -> Unit,
) {
    val activeItem = remember(library.items, library.activeId) {
        library.items.firstOrNull { it.id == library.activeId }
    }
    val activeFolder = activeItem?.folder.orEmpty()
    var browseAllFlat by remember { mutableStateOf(false) }
    var drawerPath by remember(activeFolder) { mutableStateOf(activeFolder) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val trimmedQuery = query.trim()
    val inSearch = trimmedQuery.isNotEmpty()

    val folders = remember(library.items, drawerPath, browseAllFlat, inSearch) {
        if (browseAllFlat || inSearch) emptyList()
        else RemoteVideoLibrary.foldersIn(library.items, drawerPath)
    }
    val videos = remember(library.items, drawerPath, browseAllFlat, trimmedQuery) {
        when {
            trimmedQuery.isNotEmpty() -> library.items.filter {
                it.name.contains(trimmedQuery, ignoreCase = true) ||
                    it.folder.contains(trimmedQuery, ignoreCase = true)
            }
            browseAllFlat -> library.items
            else -> RemoteVideoLibrary.videosIn(library.items, drawerPath)
        }
    }

    LaunchedEffect(drawerPath, browseAllFlat, videos.size, library.activeId) {
        if (!inSearch && library.activeId.isNotBlank()) {
            val idx = videos.indexOfFirst { it.id == library.activeId }
            if (idx >= 0) {
                val upOffset = if (!browseAllFlat && drawerPath.isNotEmpty()) 1 else 0
                listState.scrollToItem((upOffset + folders.size + idx - 1).coerceAtLeast(0))
            } else {
                listState.scrollToItem(0)
            }
        }
    }

    Surface(
        color = Color(0xEC0D111A),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        modifier = Modifier
            .fillMaxHeight()
            .width(380.dp)
            .clickable(enabled = false) {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            inSearch -> "搜索结果"
                            browseAllFlat -> "全部视频"
                            drawerPath.isEmpty() -> "全部目录"
                            else -> drawerPath.substringAfterLast('/')
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            !browseAllFlat && !inSearch && drawerPath.isNotEmpty() -> drawerPath
                            else -> "${library.deviceName.ifBlank { "手机" }} · ${videos.size} 个视频"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onOpenFullLibrary) {
                    Text("全屏浏览")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            // 模式切换 + 一键返回上一级
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (!browseAllFlat) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { browseAllFlat = false },
                ) {
                    Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text("按文件夹", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (browseAllFlat) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { browseAllFlat = true },
                ) {
                    Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                        Text("全部视频 (${library.items.size})", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索发送端所有视频…", color = Color.White.copy(alpha = 0.5f)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "清空", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 上一级目录选项（置顶方便单手点按）
                if (!browseAllFlat && !inSearch && drawerPath.isNotEmpty()) {
                    val parentPath = drawerPath.substringBeforeLast('/', "")
                    val parentLabel = if (parentPath.isEmpty()) "全部目录" else parentPath.substringAfterLast('/')
                    item(key = "__parent__") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { drawerPath = parentPath },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    contentDescription = "返回上一级",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "返回上一级",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "上级目录：$parentLabel",
                                        color = Color.White.copy(alpha = 0.65f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // 当前目录下的子文件夹
                items(folders, key = { "df:" + it.path }) { folder ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { drawerPath = folder.path },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    folder.name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (folder.subFolderCount > 0) "${folder.totalCount} 个视频 · ${folder.subFolderCount} 个子目录"
                                    else "${folder.totalCount} 个视频",
                                    color = Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                // 视频条目
                items(videos, key = { "dv:" + it.id }) { item ->
                    val active = item.id == library.activeId
                    val thumb by produceState<Bitmap?>(initialValue = RemoteThumbCache.cached(item.thumbUrl), item.thumbUrl) {
                        value = RemoteThumbCache.load(item.thumbUrl)
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f),
                        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(item) },
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(86.dp)
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1A202E)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val bmp = thumb
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = item.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        if (active) append("正在播放 · ")
                                        if (item.durationMs > 0) append(RemoteVideoLibrary.formatDuration(item.durationMs))
                                        if ((browseAllFlat || inSearch) && item.folder.isNotEmpty()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(item.folder.substringAfterLast('/'))
                                        }
                                    },
                                    color = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatLanSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private const val OVERLAY_HIDE_MS = 4000L
