package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.ui.compose.material3.MiniController
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.net.LanCastStatus
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.gestures.BrightnessState
import io.github.jqssun.airplay.ui.gestures.DoubleTapIndicator
import io.github.jqssun.airplay.ui.gestures.GestureInfoText
import io.github.jqssun.airplay.ui.gestures.SeekGestureState
import io.github.jqssun.airplay.ui.gestures.TapGestureState
import io.github.jqssun.airplay.ui.gestures.VerticalGesture
import io.github.jqssun.airplay.ui.gestures.VerticalProgressIndicator
import io.github.jqssun.airplay.ui.gestures.VideoPlayerGestures
import io.github.jqssun.airplay.ui.gestures.VolumeAndBrightnessGestureState
import io.github.jqssun.airplay.ui.gestures.VolumeState
import io.github.jqssun.airplay.ui.gestures.ZoomState
import io.github.jqssun.airplay.ui.theme.CastCard
import io.github.jqssun.airplay.ui.theme.CastColors
import io.github.jqssun.airplay.ui.theme.StatusPill
import io.github.jqssun.airplay.ui.theme.StatusPulseDot
import io.github.jqssun.airplay.ui.theme.liveGreenColor
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    OVERVIEW(R.string.tab_overview, Icons.Default.Cast),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Filled.Article),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings),
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    isInPip: Boolean = false,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onPip: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var fullscreen by remember { mutableStateOf(false) }
    var mirrorFillScreen by remember { mutableStateOf(false) }

    // CastKit: 局域网「投视频文件」与「发送端视频库」状态
    val lanVideo by viewModel.lanVideo.collectAsState()
    val remoteLibrary by viewModel.remoteLibrary.collectAsState()
    val remoteLibraryVisible by viewModel.remoteLibraryVisible.collectAsState()
    val remoteLibraryPath by viewModel.remoteLibraryPath.collectAsState()

    if (lanVideo.active) {
        val lanActivity = LocalContext.current as? Activity
        DisposableEffect(lanActivity) {
            val window = lanActivity?.window
            val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
            controller?.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        LanVideoScreen(
            state = lanVideo,
            remoteLibrary = remoteLibrary,
            isInPip = isInPip,
            onSurfaceAvailable = viewModel::onLanVideoSurfaceAvailable,
            onSurfaceDestroyed = viewModel::onLanVideoSurfaceDestroyed,
            onToggle = viewModel::toggleLanVideo,
            onSeek = viewModel::seekLanVideo,
            onSeekBy = viewModel::seekLanVideoBy,
            onSpeedChange = viewModel::setLanVideoSpeed,
            onSelectRemoteVideo = viewModel::playRemoteVideo,
            onExitToLibrary = viewModel::exitLanVideoToLibrary,
            onPip = onPip,
            onStop = viewModel::stopLanVideo,
        )
        return
    }

    // 接收端退出当前视频播放（或发送端共享视频库）时：展示发送端视频库浏览选片页（自动定位到该视频所在文件夹）
    if (remoteLibrary.connected && remoteLibraryVisible && !isInPip) {
        RemoteLibraryScreen(
            library = remoteLibrary,
            currentPath = remoteLibraryPath,
            onPathChange = viewModel::setRemoteLibraryPath,
            onSelectVideo = viewModel::playRemoteVideo,
            onRefresh = { viewModel.refreshRemoteLibrary(force = true) },
            onMinimizeToHub = viewModel::hideRemoteLibrary,
            onDisconnect = viewModel::closeRemoteLibrarySession,
        )
        return
    }

    val pin by viewModel.pinCode.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val videoSessionPending by viewModel.videoSessionPending.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val autoFullscreen by viewModel.autoFullscreen.collectAsState()
    val lanStatus by viewModel.lanStatus.collectAsState()
    // 只有握手尺寸 > 0 才是屏幕镜像流；投视频/浏览视频库模式 width=0，不占镜像 Surface
    val lanActive = lanStatus.client != null && lanStatus.width > 0

    val video: @Composable () -> Unit = {
        val airplayAspect by viewModel.videoAspect.collectAsState()
        val lanAspect = lanStatus.aspect
        val aspect = if (lanActive && lanAspect > 0f) lanAspect else airplayAspect
        VideoSurfaceView(
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = aspect,
            applyAspectRatio = !mirrorFillScreen,
            modifier = if (mirrorFillScreen) Modifier.fillMaxSize() else Modifier,
        )
    }

    var prevMirroringActive by remember { mutableStateOf(false) }
    LaunchedEffect(mirroringActive, audioOnly, videoPlaybackActive, pin) {
        val justStarted = !prevMirroringActive && mirroringActive
        prevMirroringActive = mirroringActive
        if (justStarted && !audioOnly && !videoPlaybackActive && autoFullscreen && pin == null) {
            fullscreen = true
        }
    }

    // CastKit: 局域网镜像接入时按设置自动全屏
    var prevLanActive by remember { mutableStateOf(false) }
    LaunchedEffect(lanActive) {
        val justStarted = !prevLanActive && lanActive
        prevLanActive = lanActive
        if (justStarted && autoFullscreen) fullscreen = true
        if (!lanActive && connections == 0) {
            fullscreen = false
            mirrorFillScreen = false
        }
    }

    LaunchedEffect(connections, lanActive) {
        if (connections == 0 && !lanActive) {
            fullscreen = false
            mirrorFillScreen = false
        }
    }

    LaunchedEffect(videoPlaybackActive) {
        if (videoPlaybackActive) fullscreen = false
    }

    val activity = LocalContext.current as? Activity
    val videoScreen = videoPlaybackActive || videoSessionPending
    LaunchedEffect(fullscreen, videoScreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen || videoScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- AirPlay 视频播放页（保留完整上游手势/遥控/下载能力） ----
    if (videoScreen) {
        val videoPlaybackAspect by viewModel.videoPlaybackAspect.collectAsState()
        val playback: @Composable () -> Unit = {
            VideoSurfaceView(
                onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                aspectRatio = videoPlaybackAspect,
            )
        }
        if (isInPip) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                playback()
            }
            return
        }
        val overlayTick by viewModel.videoOverlayTick.collectAsState()
        val playing by viewModel.videoPlaying.collectAsState()
        val positionMs by viewModel.videoPositionMs.collectAsState()
        val durationMs by viewModel.videoDurationMs.collectAsState()
        val scrubPositionMs by viewModel.videoScrubPositionMs.collectAsState()
        val downloadProgress by viewModel.videoDownloadProgress.collectAsState()
        val scrubbing = scrubPositionMs != null
        val downloading = downloadProgress != null
        var overlayVisible by remember { mutableStateOf(false) }
        LaunchedEffect(overlayTick, playing, scrubbing, downloading, videoPlaybackActive) {
            if (!videoPlaybackActive) {
                overlayVisible = true
            } else if (overlayTick == 0L) {
                overlayVisible = false
            } else {
                overlayVisible = true
                if (playing && !scrubbing && !downloading) {
                    delay(VIDEO_OVERLAY_HIDE_MS)
                    overlayVisible = false
                }
            }
        }
        val gestureScope = rememberCoroutineScope()
        val tapGestureState = remember(viewModel) { TapGestureState(viewModel, gestureScope) }
        val seekGestureState = remember(viewModel) { SeekGestureState(viewModel) }
        val context = LocalContext.current
        val volumeState = remember { VolumeState(context) }
        val brightnessState = remember(activity) { activity?.window?.let { BrightnessState(it) } }
        val volumeAndBrightnessGestureState = remember(volumeState, brightnessState) {
            VolumeAndBrightnessGestureState(volumeState, brightnessState, gestureScope)
        }
        val zoomState = remember(viewModel) { ZoomState(viewModel, gestureScope) }
        var controlsLocked by remember { mutableStateOf(false) }
        DisposableEffect(volumeState) { volumeState.handleLifecycle(this) }
        LaunchedEffect(overlayTick) {
            if (overlayTick == 0L) {
                zoomState.reset()
                controlsLocked = false
            }
        }
        DisposableEffect(brightnessState) { onDispose { brightnessState?.clearOverride() } }
        LaunchedEffect(videoPlaybackAspect) {
            activity?.requestedOrientation = if (videoPlaybackAspect < 1f) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        DisposableEffect(activity) {
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        val videoPlaybackSize by viewModel.videoPlaybackSize.collectAsState()
        val buffering by viewModel.videoBuffering.collectAsState()
        val videoTitle by viewModel.videoTitle.collectAsState()
        val videoLocation by viewModel.videoLocation.collectAsState()
        val speed by viewModel.videoSpeed.collectAsState()
        val isPipSupported = remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }
        var showSpeedSelector by remember { mutableStateOf(false) }
        BackHandler { viewModel.stopVideoPlayback() }

        val rootFocusRequester = remember { FocusRequester() }
        val playPauseFocusRequester = remember { FocusRequester() }
        val unlockFocusRequester = remember { FocusRequester() }
        var isPlayPauseFocused by remember { mutableStateOf(false) }
        var isUnlockFocused by remember { mutableStateOf(false) }
        LaunchedEffect(overlayVisible, controlsLocked, showSpeedSelector) {
            if (showSpeedSelector) return@LaunchedEffect
            if (!overlayVisible) {
                runCatching { rootFocusRequester.requestFocus() }
                return@LaunchedEffect
            }
            val locked = controlsLocked
            val target = if (locked) unlockFocusRequester else playPauseFocusRequester
            target.requestFocusUntilLanded(attempts = 20) { if (locked) isUnlockFocused else isPlayPauseFocused }
        }

        var dpadSeekOffsetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekTargetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekActive by remember { mutableStateOf(false) }
        var dpadSeekTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(dpadSeekTick) {
            if (!dpadSeekActive) return@LaunchedEffect
            delay(1000)
            dpadSeekActive = false
        }
        val showDpadSeekFeedback: (Long) -> Unit = { deltaMs ->
            if (!dpadSeekActive) dpadSeekOffsetMs = 0L
            dpadSeekOffsetMs += deltaMs
            dpadSeekTargetMs = (dpadSeekTargetMs.takeIf { dpadSeekActive } ?: positionMs).plus(deltaMs)
                .coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
            dpadSeekActive = true
            dpadSeekTick++
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (showSpeedSelector) false
                    else handlePlayerKeyEvent(
                        keyEvent = keyEvent,
                        controlsVisible = overlayVisible,
                        controlsLocked = controlsLocked,
                        isPlayPauseFocused = isPlayPauseFocused,
                        seekIncrementMs = TapGestureState.SEEK_INCREMENT_MS,
                        viewModel = viewModel,
                        showControls = { viewModel.showVideoOverlay() },
                        unlockControls = {
                            controlsLocked = false
                            viewModel.showVideoOverlay()
                        },
                        onDpadSeek = showDpadSeekFeedback,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            VideoContentFrame(
                aspect = videoPlaybackAspect,
                videoSizePx = videoPlaybackSize,
                contentScale = zoomState.contentScale,
                zoom = zoomState.zoom,
            ) { sizeModifier ->
                VideoSurfaceView(
                    onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                    applyAspectRatio = false,
                    modifier = sizeModifier,
                )
            }
            VideoPlayerGestures(
                enabled = videoPlaybackActive,
                locked = controlsLocked,
                onTap = {
                    if (!videoPlaybackActive) return@VideoPlayerGestures
                    if (overlayVisible) overlayVisible = false else viewModel.showVideoOverlay()
                },
                tapGestureState = tapGestureState,
                seekGestureState = seekGestureState,
                volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                zoomState = zoomState,
            )
            AnimatedVisibility(visible = overlayVisible && videoPlaybackActive && !controlsLocked, enter = fadeIn(), exit = fadeOut()) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            }
            if (buffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(72.dp))
            }
            DoubleTapIndicator(tapGestureState = tapGestureState)
            val seekAmountMs = seekGestureState.seekAmountMs
            when {
                seekAmountMs != null -> GestureInfoText(
                    info = "${if (seekAmountMs < 0) "-" else "+"}${formatVideoTime(abs(seekAmountMs))}\n[${formatVideoTime(seekGestureState.targetPositionMs ?: 0)}]",
                    modifier = Modifier.align(Alignment.Center),
                )
                zoomState.isZooming -> GestureInfoText(
                    info = "${(zoomState.zoom * 100).toInt()}%",
                    modifier = Modifier.align(Alignment.Center),
                )
                zoomState.showContentScaleIndicator -> GestureInfoText(
                    info = stringResource(zoomState.contentScale.nameRes()),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> AnimatedVisibility(
                    visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    PlayPauseButton(
                        playing = playing,
                        onClick = { viewModel.toggleVideoPlayPause() },
                        modifier = Modifier
                            .focusRequester(playPauseFocusRequester)
                            .onFocusChanged { isPlayPauseFocused = it.hasFocus },
                    )
                }
            }
            DpadSeekIndicator(visible = dpadSeekActive && dpadSeekOffsetMs != 0L, offsetMs = dpadSeekOffsetMs, positionMs = dpadSeekTargetMs)
            AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.VOLUME,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(24.dp),
            ) {
                VerticalProgressIndicator(value = volumeState.percentage, icon = Icons.AutoMirrored.Rounded.VolumeUp)
            }
            AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.BRIGHTNESS,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp),
            ) {
                VerticalProgressIndicator(value = brightnessState?.percentage ?: 0, icon = Icons.Rounded.BrightnessHigh)
            }
            if (controlsLocked) {
                if (overlayVisible) {
                    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 24.dp)) {
                        UnlockButton(
                            onClick = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            modifier = Modifier
                                .focusRequester(unlockFocusRequester)
                                .onFocusChanged { isUnlockFocused = it.hasFocus },
                        )
                    }
                }
            } else {
                AnimatedVisibility(visible = overlayVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                    VideoControlsTop(
                        title = videoTitle,
                        videoUrl = videoLocation,
                        downloadProgress = downloadProgress,
                        showDownload = durationMs > 0,
                        onBackClick = { viewModel.stopVideoPlayback() },
                        onSpeedClick = { overlayVisible = false; showSpeedSelector = true },
                        onDownloadClick = { viewModel.toggleVideoDownload() },
                    )
                }
                AnimatedVisibility(visible = overlayVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                    VideoControlsBottom(
                        positionMs = scrubPositionMs ?: positionMs,
                        durationMs = durationMs,
                        contentScale = zoomState.contentScale,
                        isPipSupported = isPipSupported,
                        onRotateClick = {
                            activity?.let {
                                it.requestedOrientation =
                                    if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                            }
                        },
                        onLockClick = { viewModel.showVideoOverlay(); controlsLocked = true },
                        onContentScaleClick = { viewModel.showVideoOverlay(); zoomState.switchToNextContentScale() },
                        onPipClick = onPip,
                        onSeek = { seekGestureState.onSeek(it) },
                        onSeekEnd = { seekGestureState.onSeekEnd() },
                        seekBarModifier = Modifier
                            .focusProperties { up = playPauseFocusRequester }
                            .dpadAdjust(
                                onLeft = { viewModel.seekVideoBy(-TapGestureState.SEEK_INCREMENT_MS); viewModel.showVideoOverlay() },
                                onRight = { viewModel.seekVideoBy(TapGestureState.SEEK_INCREMENT_MS); viewModel.showVideoOverlay() },
                            ),
                    )
                }
            }
            val skipSilence by viewModel.videoSkipSilence.collectAsState()
            PlaybackSpeedSelector(
                show = showSpeedSelector,
                speed = speed,
                skipSilence = skipSilence,
                onSpeedChange = { viewModel.setVideoSpeed(it) },
                onSkipSilenceChange = { viewModel.setVideoSkipSilence(it) },
                onDismiss = { showSpeedSelector = false },
            )
        }
        return
    }

    if (isInPip) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            video()
        }
        return
    }

    LaunchedEffect(isInPip) {
        if (!isInPip && !fullscreen) {
            val window = activity?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(pin) {
        if (pin != null) fullscreen = false
    }

    if (fullscreen) {
        BackHandler { fullscreen = false }
        FullscreenVideo(
            viewModel = viewModel,
            video = video,
            fillScreen = mirrorFillScreen,
            onToggleFillScreen = { mirrorFillScreen = !mirrorFillScreen },
            onDisconnectLan = { viewModel.disconnectLanCast() },
            onExitFullscreen = { fullscreen = false },
            onPip = onPip,
        )
    } else {
        // ---- 自适应主壳：平板/宽屏用左侧 NavigationRail，手机用底部 NavigationBar ----
        val config = LocalConfiguration.current
        val useRail = config.screenWidthDp >= 600
        val serverState by viewModel.serverState.collectAsState()

        if (useRail) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .statusBarsPadding()
                                .padding(top = 12.dp, bottom = 16.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(MaterialTheme.colorScheme.primary, CastColors.CyanAccent),
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Cast, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("CastKit", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    },
                ) {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = stringResource(t.labelRes)) },
                            label = { Text(stringResource(t.labelRes)) },
                            modifier = Modifier.dpadFocus(),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(modifier = Modifier.padding(bottom = 18.dp)) {
                        StatusPulseDot(
                            color = if (serverState == ServerState.RUNNING || lanStatus.listening) liveGreenColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            pulsing = serverState == ServerState.RUNNING || lanStatus.listening,
                            size = 8.dp,
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(modifier = Modifier.weight(1f)) {
                        TabContent(
                            tab = tab,
                            viewModel = viewModel,
                            video = video,
                            onFullscreen = { fullscreen = true },
                            onPip = onPip,
                            onOpenSettings = { tab = Tab.SETTINGS },
                            showAudioMode = audioOnly,
                        )
                    }
                    AudioMiniController(
                        viewModel,
                        visible = audioOnly && connections > 0 && tab != Tab.OVERVIEW,
                        onClick = { tab = Tab.OVERVIEW },
                    )
                }
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                        AudioMiniController(
                            viewModel,
                            visible = audioOnly && connections > 0 && tab != Tab.OVERVIEW,
                            onClick = { tab = Tab.OVERVIEW },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(t.icon, null) },
                                    label = { Text(stringResource(t.labelRes)) },
                                    modifier = Modifier.dpadFocus(),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    TabContent(
                        tab = tab,
                        viewModel = viewModel,
                        video = video,
                        onFullscreen = { fullscreen = true },
                        onPip = onPip,
                        onOpenSettings = { tab = Tab.SETTINGS },
                        showAudioMode = audioOnly,
                    )
                }
            }
        }
    }

    if (pin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPin() },
            title = { Text(stringResource(R.string.dialog_pin_title)) },
            text = {
                Text(
                    text = pin!!,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPin() }) { Text(stringResource(R.string.btn_ok)) }
            },
        )
    }
}

@Composable
private fun TabContent(
    tab: Tab,
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onOpenSettings: () -> Unit,
    showAudioMode: Boolean,
) {
    when (tab) {
        Tab.OVERVIEW -> OverviewContent(
            viewModel = viewModel,
            video = video,
            onFullscreen = onFullscreen,
            onPip = onPip,
            onOpenSettings = onOpenSettings,
            showAudioMode = showAudioMode,
        )
        Tab.LOGS -> LogsScreen(viewModel)
        Tab.SETTINGS -> SettingsScreen(viewModel)
    }
}

@Composable
private fun OverviewContent(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onOpenSettings: () -> Unit,
    showAudioMode: Boolean = false,
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val isWide = config.screenWidthDp >= 720

    val state by viewModel.serverState.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val requirePin by viewModel.requirePin.collectAsState()
    val videoResolution by viewModel.videoResolution.collectAsState()
    val idlePreview by viewModel.idlePreview.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val lanEnabled by viewModel.lanEnabled.collectAsState()
    val lanPort by viewModel.lanPort.collectAsState()
    val lanStatus by viewModel.lanStatus.collectAsState()
    val remoteLibrary by viewModel.remoteLibrary.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    val lanActive = lanStatus.client != null && lanStatus.width > 0
    val hasActiveVisualStream = lanActive || (state == ServerState.RUNNING && (mirroringActive || idlePreview))
    val isAirPlayAudio = showAudioMode && state == ServerState.RUNNING && connections > 0

    val tv = isTv()
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (tv) startFocus.requestFocus() }

    if (isAirPlayAudio || hasActiveVisualStream) {
        // ==================== 正在投屏 / 预览模式 ====================
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (isAirPlayAudio) {
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        NowPlayingContent(viewModel)
                    }
                } else {
                    video()

                    // 顶部悬浮控制按钮：AirPlay 与 LANCast 均支持画中画 / 全屏 / 断开
                    if (lanActive || mirroringActive) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalIconButton(
                                onClick = onPip,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0x99000000),
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.dpadFocus(),
                            ) {
                                Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = stringResource(R.string.cd_pip))
                            }
                            FilledTonalIconButton(
                                onClick = onFullscreen,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0x99000000),
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.dpadFocus(),
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.cd_fullscreen))
                            }
                            if (lanActive) {
                                FilledTonalIconButton(
                                    onClick = { viewModel.disconnectLanCast() },
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = CastColors.ErrorRedLight.copy(alpha = 0.85f),
                                        contentColor = Color.White,
                                    ),
                                    modifier = Modifier.dpadFocus(),
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "断开当前投屏")
                                }
                            }
                        }
                    }

                    if (debugEnabled && (connections > 0 || lanActive)) {
                        DebugOverlay(
                            info = debugInfo,
                            lanStatus = if (lanActive) lanStatus else null,
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        )
                    }

                    val resLabel = if (lanActive && lanStatus.width > 0) {
                        "${lanStatus.width}×${lanStatus.height} · ${"%.1f".format(lanStatus.measuredKbps / 1000f)} Mbps"
                    } else videoResolution
                    if (resLabel.isNotEmpty() && (lanActive || connections > 0)) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0x99000000),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        ) {
                            Text(
                                text = resLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.88f),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            // 底部实时会话栏
            CastCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusPulseDot(color = liveGreenColor(), pulsing = true, size = 9.dp)
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (lanActive) "LANCast 投屏中" else serverName,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (lanActive && lanStatus.hasAudio) {
                                    StatusPill(text = "声音同步", color = liveGreenColor())
                                }
                            }
                            Text(
                                text = if (lanActive) {
                                    "来源 ${lanStatus.client} · ${lanStatus.width}×${lanStatus.height} · ${"%.1f".format(lanStatus.measuredKbps / 1000f)} Mbps"
                                } else {
                                    stringResource(R.string.connected_count, connections)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (lanActive) {
                            FilledTonalButton(onClick = onFullscreen, shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("全屏")
                            }
                            Button(
                                onClick = { viewModel.disconnectLanCast() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("断开")
                            }
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    if (state == ServerState.RUNNING) viewModel.stopServer() else viewModel.startServer()
                                },
                                modifier = Modifier.dpadFocus().focusRequester(startFocus),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(
                                    if (state == ServerState.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (state == ServerState.RUNNING) stringResource(R.string.btn_stop) else stringResource(R.string.btn_start))
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // ==================== 待机接收中心（Cast Ready Studio Hub） ====================
    val anyReady = state == ServerState.RUNNING || lanStatus.listening
    val lanAddress = "${localIp ?: "获取中…"}:$lanPort"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isWide) 28.dp else 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 已连接发送端视频库时在顶部展示快捷返回入口
        if (remoteLibrary.connected) {
            CastCard(
                borderColor = liveGreenColor().copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(liveGreenColor().copy(alpha = 0.1f))
                        .clickable { viewModel.openRemoteLibrary() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        StatusPulseDot(color = liveGreenColor(), pulsing = true, size = 9.dp)
                        Column {
                            Text(
                                "已连接 ${remoteLibrary.deviceName.ifBlank { "发送端" }} 视频库",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "共 ${remoteLibrary.items.size} 个视频 · 点击进入自由选片播放",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.openRemoteLibrary() },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("浏览视频库")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.closeRemoteLibrarySession() },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("断开")
                        }
                    }
                }
            }
        }

        // 1. 顶部待机 Hero 卡片
        CastCard(
            borderColor = if (anyReady) liveGreenColor().copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                (if (anyReady) liveGreenColor() else MaterialTheme.colorScheme.primary).copy(alpha = 0.14f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .padding(horizontal = 24.dp, vertical = 22.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    (if (anyReady) liveGreenColor() else MaterialTheme.colorScheme.surfaceVariant).copy(alpha = 0.16f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.WifiTethering,
                                contentDescription = null,
                                tint = if (anyReady) liveGreenColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(serverName, style = MaterialTheme.typography.headlineSmall)
                                StatusPill(
                                    text = if (anyReady) "等待投屏连接" else "服务已停止",
                                    color = if (anyReady) liveGreenColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                    leadingDot = true,
                                )
                            }
                            Text(
                                text = "同一 Wi-Fi 下支持 iPhone/iPad/Mac 原生 AirPlay 与 Android CastKit 一键投屏",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Button(
                        onClick = {
                            if (state == ServerState.RUNNING) viewModel.stopServer()
                            else viewModel.startServer()
                        },
                        colors = if (state == ServerState.RUNNING) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White,
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .height(46.dp)
                            .dpadFocus()
                            .focusRequester(startFocus),
                    ) {
                        Icon(
                            if (state == ServerState.RUNNING) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (state == ServerState.RUNNING) "停止 AirPlay" else "启动 AirPlay")
                    }
                }
            }
        }

        // 2. 双引擎状态卡片（平板横屏左右双列，手机竖排）
        val lanCard: @Composable (Modifier) -> Unit = { mod ->
            CastCard(modifier = mod) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(liveGreenColor().copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = liveGreenColor())
                            }
                            Column {
                                Text("Android · LANCast", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (lanStatus.listening) "UDP 自动发现已就绪" else "已关闭接收",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (lanStatus.listening) liveGreenColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(checked = lanEnabled, onCheckedChange = { viewModel.setLanEnabled(it) })
                    }

                    // 显著展示本机局域网地址 + 一键复制
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("CastKit Address", lanAddress))
                                Toast.makeText(context, "已复制 $lanAddress", Toast.LENGTH_SHORT).show()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("局域网直连地址", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    lanAddress,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "复制", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Text(
                        "在安卓手机打开 CastKit 发送端，会自动发现「$serverName」，支持高帧率屏幕镜像（含系统声音）与原画质视频文件直投。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val airplayCard: @Composable (Modifier) -> Unit = { mod ->
            CastCard(modifier = mod) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.PhoneIphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Column {
                                Text("Apple · AirPlay", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when (state) {
                                        ServerState.RUNNING -> "mDNS 广播中 · 端口 $serverPort"
                                        ServerState.ERROR -> "启动失败，请检查端口或日志"
                                        ServerState.STOPPED -> "未运行"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (state) {
                                        ServerState.RUNNING -> MaterialTheme.colorScheme.primary
                                        ServerState.ERROR -> MaterialTheme.colorScheme.error
                                        ServerState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                        StatusPill(
                            text = if (requirePin) "PIN 保护" else "免密直连",
                            color = if (state == ServerState.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("隔空播放设备名", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(2.dp))
                                Text(serverName, style = MaterialTheme.typography.titleMedium)
                            }
                            TextButton(onClick = onOpenSettings) {
                                Text("修改设置")
                            }
                        }
                    }

                    Text(
                        "在 iPhone / iPad / Mac 下拉控制中心，点击「屏幕镜像」并选择「$serverName」即可原生投屏，无需安装任何第三方 App。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (isWide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                lanCard(Modifier.weight(1f))
                airplayCard(Modifier.weight(1f))
            }
        } else {
            lanCard(Modifier.fillMaxWidth())
            airplayCard(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FullscreenVideo(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    fillScreen: Boolean,
    onToggleFillScreen: () -> Unit,
    onDisconnectLan: () -> Unit,
    onExitFullscreen: () -> Unit,
    onPip: () -> Unit,
) {
    val videoResolution by viewModel.videoResolution.collectAsState()
    val debugEnabled by viewModel.debugEnabled.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val lanStatus by viewModel.lanStatus.collectAsState()
    val lanActive = lanStatus.client != null && lanStatus.width > 0

    var controlsVisible by remember { mutableStateOf(true) }
    var tapTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tapTick) {
        controlsVisible = true
        delay(5000)
        controlsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { tapTick++ } },
        contentAlignment = Alignment.Center,
    ) {
        video()

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xBB000000), Color.Transparent)))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPulseDot(color = liveGreenColor(), pulsing = true, size = 7.dp)
                    Text(
                        text = if (lanActive) {
                            "LANCast · ${lanStatus.client} · ${lanStatus.width}×${lanStatus.height}" +
                                if (lanStatus.hasAudio) " · 声音同步" else ""
                        } else {
                            "AirPlay 镜像 · $videoResolution"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onToggleFillScreen, modifier = Modifier.dpadFocus()) {
                        Icon(
                            Icons.Rounded.AspectRatio,
                            contentDescription = if (fillScreen) "适应比例" else "铺满屏幕",
                            tint = if (fillScreen) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                        )
                    }
                    IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                        Icon(
                            painterResource(R.drawable.ic_pip),
                            contentDescription = stringResource(R.string.cd_pip),
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    IconButton(onClick = onExitFullscreen, modifier = Modifier.dpadFocus()) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            contentDescription = stringResource(R.string.cd_exit_fullscreen),
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                    if (lanActive) {
                        IconButton(onClick = onDisconnectLan, modifier = Modifier.dpadFocus()) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "断开连接",
                                tint = CastColors.ErrorRedDark,
                            )
                        }
                    }
                }
            }
        }

        if (debugEnabled) {
            DebugOverlay(
                info = debugInfo,
                lanStatus = if (lanActive) lanStatus else null,
                modifier = Modifier.align(Alignment.TopStart).padding(top = 56.dp, start = 12.dp),
            )
        }
    }
}

@Composable
private fun HoldScanButton(
    icon: ImageVector,
    contentDescription: String,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onBegin()
                    try {
                        awaitRelease()
                    } finally {
                        onEnd()
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription)
    }
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun AudioMiniController(viewModel: MainViewModel, visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    val player = viewModel.dacpPlayer ?: return
    val context = LocalContext.current
    MiniController(
        player,
        bitmapLoader = remember { DataSourceBitmapLoader(context) },
        onClick = onClick,
    )
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun NowPlayingContent(viewModel: MainViewModel) {
    val track by viewModel.trackInfo.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (track.coverArt != null) {
                Image(
                    bitmap = track.coverArt!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_cover_art),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = track.title.ifEmpty { stringResource(R.string.unknown_track) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (track.artist.isNotEmpty()) {
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.album.isNotEmpty()) {
            Text(
                text = track.album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(16.dp))

        val player = viewModel.dacpPlayer
        if (player != null) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PositionText(player, Modifier.padding(end = 8.dp))
                Box(modifier = Modifier.weight(1f)) { ProgressSlider(player) }
                DurationText(player, Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                HoldScanButton(
                    icon = Icons.Default.FastRewind,
                    contentDescription = stringResource(R.string.cd_rewind),
                    onBegin = { viewModel.audioScanBegin(false) },
                    onEnd = { viewModel.audioScanEnd() },
                )
                PreviousButton(player, modifier = Modifier.dpadFocus())
                PlayPauseButton(player, modifier = Modifier.size(63.dp).dpadFocus(CircleShape), iconSize = 40.dp)
                NextButton(player, modifier = Modifier.dpadFocus())
                HoldScanButton(
                    icon = Icons.Default.FastForward,
                    contentDescription = stringResource(R.string.cd_fast_forward),
                    onBegin = { viewModel.audioScanBegin(true) },
                    onEnd = { viewModel.audioScanEnd() },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = { viewModel.audioVolumeDown() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.cd_volume_down))
                }
                IconButton(onClick = { viewModel.audioMuteToggle() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.cd_mute))
                }
                IconButton(onClick = { viewModel.audioVolumeUp() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.cd_volume_up))
                }
            }
        }
    }
}

private const val VIDEO_OVERLAY_HIDE_MS = 4000L

@Composable
private fun DebugOverlay(
    info: DebugInfo,
    lanStatus: LanCastStatus? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        val headingStyle = style.copy(fontWeight = FontWeight.Bold)
        val color = Color.White.copy(alpha = 0.9f)
        val headingColor = Color(0xFF80D8FF)

        if (lanStatus != null) {
            Text("LANCAST", style = headingStyle, color = headingColor)
            Text("来源：${lanStatus.client ?: "-"}", style = style, color = color)
            Text("画面：${lanStatus.width}×${lanStatus.height} @${lanStatus.measuredFps.toInt()} fps", style = style, color = color)
            Text("码率：${"%.1f".format(lanStatus.measuredKbps / 1000f)} Mbps", style = style, color = color)
            Text("解码：${lanStatus.decoder.ifBlank { "-" }}", style = style, color = color)
            Text("音频：${if (lanStatus.hasAudio) "AAC 同步中" else "无"}", style = style, color = color)
            return
        }

        val context = LocalContext.current
        debugOverlaySections(context, info).forEachIndexed { i, section ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(section.title.uppercase(), style = headingStyle, color = headingColor)
            section.lines.forEach { Text(it, style = style, color = color) }
        }
    }
}

private data class DebugSection(val title: String, val lines: List<String>)

private fun debugOverlaySections(context: Context, info: DebugInfo): List<DebugSection> = buildList {
    buildList {
        if (info.videoCodec.isNotEmpty()) {
            add(context.getString(R.string.debug_video, info.videoCodec, info.videoRes))
            add(context.getString(R.string.debug_fps_bitrate, info.videoFps, info.bitrateStr))
            add(context.getString(R.string.debug_frames_drops, info.videoFrames, info.droppedFrames))
            add(context.getString(R.string.debug_jitter, info.jitterStr))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_video), it)) }

    buildList {
        if (info.audioCodec.isNotEmpty()) add(context.getString(R.string.debug_audio, info.audioCodec, info.audioVolume))
        info.audio?.let { a ->
            add(context.getString(R.string.debug_audio_buffer, a.backlogMs, a.tunedCushionMs))
            add(context.getString(R.string.debug_audio_glitch, a.trims, a.drops, a.silences, a.underruns, a.xrun))
            add(context.getString(R.string.debug_audio_decode, formatDecode(a.decodeMeanUs, a.decodeMaxUs, a.decodeHeld, a.decodeErrors)))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_audio), it)) }

    add(DebugSection(context.getString(R.string.debug_section_network), listOf(context.getString(R.string.debug_clients, info.connections))))
}

private fun formatDecode(meanUs: Int, maxUs: Int, held: Int, errors: Int): String =
    (if (meanUs == 0) "held=$held"
    else "%.1f/%.1f ms held=%d".format(meanUs / 1000.0, maxUs / 1000.0, held)) + " errs=$errors"
