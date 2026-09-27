package io.github.jqssun.airplay.ui

import android.app.Activity
import android.content.res.Configuration
import android.view.Surface
import android.view.SurfaceHolder
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.renderer.LanVideoState
import io.github.jqssun.airplay.ui.theme.ImmersiveColors
import io.github.jqssun.airplay.ui.theme.PillShape
import io.github.jqssun.airplay.ui.theme.PlayerMotion
import io.github.jqssun.airplay.ui.theme.PlayerSizes
import io.github.jqssun.airplay.ui.theme.PlayerSpacing
import io.github.jqssun.airplay.ui.theme.PlayerType
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * CastKit: 局域网「投视频文件」播放页。
 *
 * 发送端只给出视频地址，这里用系统播放器播放原始文件，所以画质/声音/进度都是原生的。
 *
 * **视觉与交互刻意与发送端 `VideoPlayerScreen` 保持同一套**（见 [io.github.jqssun.airplay.ui.theme.Immersion]）：
 * 顶栏与底栏全透明、白字白图标带投影、自绘 3dp/12dp 进度条、3 秒自动收起、
 * 系统栏跟着控制栏显隐、矮屏自动换紧凑档。
 *
 * 与发送端的差异是因为**能力不同**，不是样式偷懒：
 *  - 没有「上一个 / 下一个」——接收端只会收到发送端推来的单个地址，没有播放列表；
 *  - 没有「投屏」钮——它自己就是接收端的这一端；
 *  - 没有「切方向」钮——发送端那个钮是为了修发送端自己的画面，接收端这一页从来没有方向控制；
 *  - 没有长按 3× 快进——[io.github.jqssun.airplay.renderer.LanVideoPlayer] 没有倍速接口。
 *  退出改由顶栏返回箭头承担（发送端也是这样：每个动作全页只出现一次），系统返回键同样生效。
 */
@Composable
fun LanVideoScreen(
    state: LanVideoState,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onSurfaceHolder: (SurfaceHolder) -> Unit = {},
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp < COMPACT_HEIGHT_THRESHOLD_DP

    var overlayVisible by remember { mutableStateOf(true) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }

    // 竖滑调整：**两个都是系统级的**（亮度也是），退出播放不还原。
    var adjusting by remember { mutableStateOf<PlayerAdjustTarget?>(null) }
    var brightness by remember { mutableStateOf(SystemBrightness.current(context)) }
    var volume by remember { mutableStateOf(SystemVolume.current(context)) }

    // 横滑调进度：`seekPreviewMs` 只是给中央提示看的**目标位置**，跳转本身在滑动过程中就实时做了。
    // `seekStartMs` 是按下那一刻的播放位置 —— 目标一律从它算起，**不能**累加当前位置，否则边跳边算会自激。
    var seekPreviewMs by remember { mutableStateOf<Long?>(null) }
    var seekStartMs by remember { mutableStateOf(0L) }

    /**
     * 系统栏（顶部状态栏 + 底部手势条）的控制器，可见性**跟着控制栏走**：
     * 控制栏出现时系统栏一起显示，控制栏收起才一起隐藏（不是进播放页就一律隐藏）。
     */
    val insetsController = remember(activity) {
        activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView) }
    }

    DisposableEffect(insetsController) {
        // 从屏幕边缘往里划可以临时唤出系统栏（看通知/电量），但不改变控制栏的状态
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            // 本页不是独立 Activity，只是整屏接管了主界面，所以必须显式还原系统栏
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(insetsController, overlayVisible) {
        if (overlayVisible) {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 播放中自动收起控件；正在拖动进度条时不收
    LaunchedEffect(state.playing, overlayVisible, scrubMs) {
        if (state.playing && overlayVisible && scrubMs == null) {
            delay(PlayerMotion.OVERLAY_AUTO_HIDE_MS)
            overlayVisible = false
        }
    }

    // 播放时保持屏幕常亮
    val view = LocalView.current
    DisposableEffect(state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }

    // 亮度/音量都是系统级的，**退出不还原** —— 用户滑到哪就是哪

    BackHandler { onStop() }

    val position = scrubMs ?: state.positionMs
    val duration = state.durationMs

    // 横滑一整屏宽对应多少时长：短片源就取片长本身，免得「蹭一下就跑完」
    val seekWindowMs = duration.coerceAtMost(SEEK_WINDOW_MS)

    fun seekBy(deltaMs: Long) {
        val target = (position + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
        onSeek(target)
    }

    /**
     * 横滑调进度：**边滑边跳**（不是松手才跳）。
     *
     * 目标一律从**按下那一刻的位置** `seekStartMs` 算起 —— 如果拿当前 `position` 累加，
     * 每跳一次位置就变一次，下一帧又在这个新位置上再加，会自激跑飞。
     */
    fun seekToLive(fraction: Float) {
        if (duration <= 0L) return
        val t = (seekStartMs + (fraction * seekWindowMs).toLong())
            .coerceIn(0L, duration.coerceAtLeast(0L))
        seekPreviewMs = t
        onSeek(t)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ImmersiveColors.Background)
            // 单击切换控制栏显隐；**双击 = 播放/暂停**（与发送端同一套交互）。
            // 用 detectTapGestures 而不是 clickable：只有它能同时拿到单击与双击。
            //
            // 代价说明：一旦传了 onDoubleTap，`onTap` 就要等一个双击超时（约 300ms）才能确定
            // "没有第二下"，所以单击唤出控制栏会比以前慢一点点。这是"单击 / 双击并存"的固有代价。
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { overlayVisible = !overlayVisible },
                    onDoubleTap = { onToggle() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        VideoSurfaceView(
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            onSurfaceHolder = onSurfaceHolder,
            aspectRatio = if (state.aspect > 0f) state.aspect else 16f / 9f,
        )

        if (state.buffering) {
            CircularProgressIndicator(color = ImmersiveColors.OnScrim)
        }

        state.error?.let { msg ->
            Text(
                msg,
                color = ImmersiveColors.Error,
                style = PlayerType.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(PlayerSpacing.space6),
            )
        }

        // 播放源消失（发送端退出/断网）：中性提示，随后自动收起本页
        state.ended?.let { msg ->
            Text(
                msg,
                color = ImmersiveColors.OnScrim,
                style = PlayerType.titleSmall,
                modifier = Modifier
                    .clip(PillShape)
                    .background(ImmersiveColors.Scrim)
                    .padding(
                        horizontal = PlayerSpacing.space4,
                        vertical = PlayerSpacing.space2,
                    ),
            )
        }

        // 竖滑调整层：铺在画面之上、控制栏之下。
        // 放在这里（而不是加在父 Box 的手势里）是为了不跟"单击切控制栏"打架，理由见 PlayerAdjustLayer。
        PlayerAdjustLayer(
            onAdjustStart = { target ->
                adjusting = target
                // 每次开始滑动都以当前实际值起步，避免上次滑到哪就永远从哪开始
                when (target) {
                    PlayerAdjustTarget.BRIGHTNESS -> brightness = SystemBrightness.current(context)
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
                seekStartMs = position
                seekPreviewMs = seekStartMs
            },
            onSeekDelta = { fraction -> seekToLive(fraction) },
            onSeekEnd = { seekPreviewMs = null },
            seekEnabled = duration > 0L,
        )

        // 竖滑时的中央提示
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

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerTopBar(
                title = state.title.ifBlank { stringResource(R.string.lan_video_fallback_title) },
                onBack = onStop,
            )
        }

        AnimatedVisibility(
            visible = overlayVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomControls(
                positionMs = position,
                durationMs = duration,
                playing = state.playing,
                onScrub = { scrubMs = it },
                onScrubFinished = {
                    scrubMs?.let(onSeek)
                    scrubMs = null
                },
                onSeekBack = { seekBy(-SEEK_STEP_MS) },
                onPlayPause = onToggle,
                onSeekForward = { seekBy(SEEK_STEP_MS) },
                compact = compact,
            )
        }
    }
}

/** 顶栏：只有「返回 + 文件名」，背景全透明（和发送端一致）。 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
        title = {
            Text(
                text = title,
                style = PlayerType.titleSmall.onOverlay(),
                color = ImmersiveColors.OnScrim,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                // 只有一个标题，跑马灯不会像列表那样变成持续动画开销
                modifier = Modifier.basicMarquee(),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            navigationIconContentColor = ImmersiveColors.OnScrim,
            titleContentColor = ImmersiveColors.OnScrim,
        ),
    )
}

/**
 * 底栏：进度条 + 时间码 + 三格控制排（`↺10` · 播放/暂停 · `↷10`）。
 *
 * @param compact 矮屏（横屏手机，可用高度只有 384dp）用的紧凑版：时间码挪到进度条**同一行的两端**、
 *        内边距与间距收紧、主按钮缩到 56dp，省掉一整行的高度。
 */
@Composable
private fun PlayerBottomControls(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onSeekBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    compact: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 不铺任何底色：整条 scrim 底会变成一大块压暗画面的黑板。
            // 留 navigationBarsPadding 只是保证控制排不被手势条压住。
            .navigationBarsPadding()
            .padding(
                horizontal = PlayerSpacing.edge,
                vertical = if (compact) PlayerSpacing.space1 else PlayerSpacing.space2,
            ),
        verticalArrangement = Arrangement.spacedBy(
            if (compact) PlayerSpacing.space1 else PlayerSpacing.space2,
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
                    style = PlayerType.labelSmall.onOverlay(),
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
                        .padding(horizontal = PlayerSpacing.space2),
                )
                Text(
                    text = formatTimeCode(durationMs),
                    style = PlayerType.labelSmall.onOverlay(),
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
                    style = PlayerType.labelMedium.onOverlay(),
                    color = ImmersiveColors.TextSecondary,
                )
                Text(
                    text = formatTimeCode(durationMs),
                    style = PlayerType.labelMedium.onOverlay(),
                    color = ImmersiveColors.TextSecondary,
                )
            }
        }

        // 三格：后退 10 秒 | 播放暂停 | 前进 10 秒。
        // 「退出」在顶栏返回箭头上（发送端同样不在控制排里重复放退出）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconSlot(
                icon = Icons.Filled.Replay10,
                label = stringResource(R.string.cd_seek_back_10),
                onClick = onSeekBack,
            )

            PlayerPlayPauseButton(
                playing = playing,
                onClick = onPlayPause,
                size = if (compact) {
                    PlayerSizes.playerPrimaryButtonCompact
                } else {
                    PlayerSizes.playerPrimaryButton
                },
            )

            PlayerIconSlot(
                icon = Icons.Filled.Forward10,
                label = stringResource(R.string.cd_seek_forward_10),
                onClick = onSeekForward,
            )
        }
    }
}

/**
 * 自绘进度条：**3dp 细轨 + 12dp 圆形滑块**。
 *
 * 与发送端同一份实现。触摸区仍是整行 48dp 高，视觉上的细轨居中对齐其中；
 * 用同一段手势区分按下 / 拖动 / 抬起，所以「松手才下发 seek」不需要防抖。
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
    val thumbPx = with(density) { PlayerSizes.scrubThumb.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PlayerSizes.minTouchTarget)
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
                .height(PlayerSizes.scrubTrackHeight)
                .clip(CircleShape)
                .background(ImmersiveColors.ScrubTrackInactive),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(PlayerSizes.scrubTrackHeight)
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
                .size(PlayerSizes.scrubThumb)
                .clip(CircleShape)
                .background(ImmersiveColors.ScrubThumb),
        )
    }
}

/** 次级图标槽：固定 48dp 见方的点击区，图标 24dp。 */
@Composable
private fun PlayerIconSlot(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(PlayerSizes.minTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ShadowedIcon(icon = icon, contentDescription = label)
    }
}

/** 播放/暂停：视觉主体，默认 64dp 实心圆 + 32dp 图标（矮屏紧凑版 56dp）。 */
@Composable
private fun PlayerPlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    size: Dp = PlayerSizes.playerPrimaryButton,
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
            contentDescription = stringResource(R.string.cd_play_pause),
            tint = ImmersiveColors.Background,
            modifier = Modifier.size(PlayerSizes.playerPrimaryGlyph),
        )
    }
}

/**
 * 浮层文字的投影。顶栏/底栏全透明后，白字是直接压在画面上的 ——
 * 亮场景里没有底就没有对比度，用一层黑色投影代替 scrim 垫底（字幕的通行做法）。
 */
private val OverlayTextShadow = Shadow(
    color = ImmersiveColors.Shadow.copy(alpha = 0.75f),
    offset = Offset(0f, 1f),
    blurRadius = 7f,
)

/** 浮层文字：套上 [OverlayTextShadow]。 */
private fun TextStyle.onOverlay(): TextStyle = copy(shadow = OverlayTextShadow)

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

/** 带投影的浮层图标。 */
@Composable
private fun ShadowedIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = PlayerSizes.playerSecondaryGlyph,
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

/** 时间码：0 也显示 00:00（`--:--` 会让人以为没在计时）。 */
private fun formatTimeCode(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 矮屏（横屏手机）判据：可用高度低于这个值就用紧凑底栏。 */
private const val COMPACT_HEIGHT_THRESHOLD_DP = 480

/** 紧凑版进度条的触摸行高：比常规的 48dp 矮一点，横屏下省出来的高度很关键。 */
private val COMPACT_SCRUB_HEIGHT = 32.dp

/** ±10 秒的步长。 */
private const val SEEK_STEP_MS = 10_000L

/**
 * 横滑调进度时，**一整屏宽**对应多少时长。
 *
 * 90 秒是个手感值：整屏滑一遍大约跨一分半，长片不用反复搓、短片又不会太跳。
 * 片源比它还短时取片长本身（见 `seekWindowMs`），免得「蹭一下就跑完」。
 * 与发送端同值，两端手感一致。
 */
private const val SEEK_WINDOW_MS = 90_000L
