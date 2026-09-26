package com.dsh.castkit.sender.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** 应用内本地播放状态。 */
data class LocalPlaybackState(
    val uri: Uri? = null,
    val title: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val error: String? = null,
) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}

/**
 * 应用内本地视频播放器（选视频即播放，不再只是"选中"）。
 *
 * ## 为什么从系统 `MediaPlayer` 换成 Media3 ExoPlayer（2026-09 真机实测）
 *
 * 原来用系统 `MediaPlayer` 播 `content://` 视频。它对**解封装**环节毫无自主权 ——
 * 用的是安卓自带的 `MPEG4Extractor`，而那个解封装器遇到病态容器时间基会**直接弃轨**：
 *
 * ```
 * MPEG4Extractor: track->timescale overflow
 * ```
 *
 * （实测样本：`mdhd` timescale = 2^31−1、VUI `time_scale` = 2^32−2。）
 * 症状是**有声音、进度条在走、画面全黑**，而且全程**连视频解码器都不会创建** ——
 * 问题根本不在解码器，换软解也没用。
 *
 * Media3 的 `Mp4Extractor` 是它自己用 Java 重写的实现，时间戳走防溢出的
 * `Util.scaleLargeTimestamp`，换成它之后同一台机器就能正常出画面。
 *
 * ## 再叠一层 FFmpeg（NextLib）
 *
 * 换掉解封装只是解决一半。**解码器**那一侧仍可能吃不下：实测 1080i MBAFF 隔行流会被
 * 某些硬解器**静默丢弃每一个 buffer**（`MediaCodec discarded an unknown buffer`），
 * 同样黑屏、同样不报错。NextLib 文档明确写了 `AUTO` 只在"平台解不了这个格式"时兜底，
 * **不会从运行期解码失败里恢复**，所以这里配了看门狗：起播若干秒没渲染出第一帧就切
 * `DecoderMode.FFMPEG`（切换是 remap tracks without stopping，不打断播放）。
 * 正常片源不受影响，仍然走硬解。
 *
 * ## 这里刻意「不做」播放前预检
 *
 * 之前这里会先用 `MediaExtractor` + `MediaCodecList` 预检，按「**本机**有没有解码器」
 * 拦截播放。接收端集成 FFmpeg 之后这个逻辑变成错的：发送端硬件解不了的格式
 * （如 MPEG-2/PS），接收端靠软解照样能播 —— 按本机能力拦截会把本来能投的片子拦下来。
 * 所以预检整个撤掉，改成「交给播放器判断 + 按错误类型给人话」。
 *
 * 线程约定：ExoPlayer 必须在带 Looper 的线程上创建和访问，这里统一 post 到主线程。
 */
class LocalVideoPlayer(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(LocalPlaybackState())
    val state: StateFlow<LocalPlaybackState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    /**
     * 诊断日志同时写 logcat 和上层回调。
     *
     * `PlayerViewModel` 构造本类时**没有**传 onLog，所以只走回调的话这些"用了哪个解码器 /
     * 有没有丢帧"的关键信息在真机上是完全看不到的 —— 而排查播放问题恰恰全靠它们。
     */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }

    /** 运行时解码器选择器（NextLib）。必须与 player 一对一，且在 prepare 之前 attach。 */
    private var decoderManager: DecoderManager? = null

    @Volatile
    private var surface: Surface? = null

    private var ticker: Runnable? = null
    private var watchdog: Runnable? = null

    /** 本次播放是否已经渲染出过第一帧。 */
    private var firstFrameRendered = false

    /** 0 = 还在硬解；1 = 已经切到 FFmpeg。 */
    private var fallbackStage = 0

    /** 累计丢帧：软解路径没有 MediaCodec 的统计日志，只能自己数。 */
    private var droppedFramesTotal = 0
    private var droppedFramesLogged = 0

    fun setSurface(s: Surface?) {
        surface = s
        main.post { runCatching { player?.setVideoSurface(s) } }
    }

    fun play(uri: Uri, title: String) {
        main.post {
            releaseInternal()
            firstFrameRendered = false
            fallbackStage = 0
            _state.value = LocalPlaybackState(uri = uri, title = title, buffering = true)
            log("本地播放: $uri")

            val manager = DecoderManager()
            decoderManager = manager
            val renderersFactory = NextRenderersFactory(context)
                .setDecoderManager(manager)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            val p = try {
                ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
            } catch (e: Throwable) {
                log("ExoPlayer 创建失败: ${e.message}")
                _state.value = _state.value.copy(error = e.message ?: "播放器创建失败", buffering = false)
                decoderManager = null
                return@post
            }
            player = p
            try {
                // 必须在 prepare 之前 attach；ExoPlayer 默认就是 DefaultTrackSelector，满足前提
                runCatching { manager.attach(p) }
                p.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                // Media3 1.11 没有 `C.SEEK_CLOSEST`；精确 seek 改用 SeekParameters 表达，
                // 与原来 `seekTo(ms, SEEK_CLOSEST)` 的语义一致（播放页进度条依赖这个精度）。
                p.setSeekParameters(SeekParameters.EXACT)
                p.setVideoSurface(surface)
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING ->
                                _state.value = _state.value.copy(buffering = true)

                            Player.STATE_READY -> {
                                _state.value = _state.value.copy(
                                    buffering = false,
                                    playing = p.isPlaying,
                                    durationMs = p.duration.takeIf { it != C.TIME_UNSET }
                                        ?.coerceAtLeast(0) ?: 0L,
                                )
                                startTicker()
                                // 就绪了才谈得上「有没有画面」，看门狗从这时开始计时
                                scheduleWatchdog(NO_FRAME_FALLBACK_MS)
                            }

                            Player.STATE_ENDED -> {
                                _state.value = _state.value.copy(
                                    playing = false,
                                    positionMs = _state.value.durationMs,
                                )
                                stopTicker()
                            }

                            Player.STATE_IDLE -> Unit
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _state.value = _state.value.copy(playing = isPlaying, buffering = false)
                        if (isPlaying) startTicker()
                    }

                    override fun onRenderedFirstFrame() {
                        firstFrameRendered = true
                        cancelWatchdog()
                        // 报告**真实**用的解码器，而不是"看门狗有没有触发" ——
                        // 有些片源是 Media3 自己就路由到 FFmpeg 的，看门狗根本没机会触发
                        log("画面已出（${decoderModeLabel()}）")
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width <= 0 || videoSize.height <= 0) return
                        // MediaPlayer 的 onVideoSizeChanged 给的是**显示**尺寸，
                        // 这里把像素宽高比折算进去，UI 里的比例计算保持一致
                        val ratio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                        _state.value = _state.value.copy(
                            width = (videoSize.width * ratio).roundToInt(),
                            height = videoSize.height,
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val friendly = friendlyMessage(error)
                        log("本地播放失败 ${error.errorCodeName}: $friendly")
                        _state.value = _state.value.copy(
                            error = friendly,
                            playing = false,
                            buffering = false,
                        )
                        stopTicker()
                    }
                })
                // 软解路径没有 MediaCodec 的 video-debug-dec 统计，只能自己数丢帧
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long,
                    ) {
                        droppedFramesTotal += droppedFrames
                    }
                })
                p.setMediaItem(MediaItem.fromUri(uri))
                p.prepare()
                p.playWhenReady = true
            } catch (e: Throwable) {
                log("本地播放加载失败: ${e.message}")
                _state.value = _state.value.copy(error = e.message ?: "加载失败", buffering = false)
                releaseInternal()
            }
        }
    }

    fun toggle() {
        main.post {
            val p = player ?: return@post
            if (p.isPlaying) {
                p.pause()
                _state.value = _state.value.copy(playing = false)
                stopTicker()
            } else {
                p.play()
                _state.value = _state.value.copy(playing = true)
                startTicker()
            }
        }
    }

    /** 暂停（已经在暂停/未起播则什么都不做）。 */
    fun pause() {
        main.post {
            val p = player ?: return@post
            if (p.isPlaying) {
                p.pause()
                _state.value = _state.value.copy(playing = false)
                stopTicker()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        main.post {
            val p = player ?: return@post
            val duration = _state.value.durationMs
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            runCatching { p.seekTo(target) }
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    fun release() {
        main.post {
            releaseInternal()
            _state.value = LocalPlaybackState()
        }
    }

    private fun releaseInternal() {
        stopTicker()
        cancelWatchdog()
        firstFrameRendered = false
        fallbackStage = 0
        droppedFramesTotal = 0
        droppedFramesLogged = 0
        runCatching { player?.setVideoSurface(null) }
        // 必须在 release 之前 detach
        runCatching { decoderManager?.detach() }
        decoderManager = null
        runCatching { player?.release() }
        player = null
    }

    /**
     * 「起播了但一直没画面」的看门狗。
     *
     * 硬解器吃不下某些码流时**不会报错**，只是静默丢弃 buffer（1080i MBAFF 实测就是
     * `MediaCodec discarded an unknown buffer`），而 Media3 的 `AUTO` 只在"格式不被支持"
     * 时兜底，管不到这种运行期失败。
     */
    private fun scheduleWatchdog(delayMs: Long) {
        cancelWatchdog()
        val r = object : Runnable {
            override fun run() {
                watchdog = null
                val p = player ?: return
                if (firstFrameRendered) return
                if (p.playbackState != Player.STATE_READY) {
                    scheduleWatchdog(NO_FRAME_FALLBACK_MS)
                    return
                }
                if (fallbackStage == 0) {
                    fallbackStage = 1
                    log("硬解 ${NO_FRAME_FALLBACK_MS / 1000} 秒内没出画面，切换 FFmpeg 软解重试")
                    runCatching { decoderManager?.selectVideoDecoder(DecoderMode.FFMPEG) }
                        .onFailure { log("切换 FFmpeg 失败: ${it.message}") }
                    scheduleWatchdog(NO_FRAME_GIVEUP_MS)
                } else {
                    log("FFmpeg 软解仍然没有画面，判定本机解不了这个片源")
                    _state.value = _state.value.copy(
                        error = "本机解不了这个视频（硬解软解都失败）",
                        playing = false,
                        buffering = false,
                    )
                    stopTicker()
                }
            }
        }
        watchdog = r
        main.postDelayed(r, delayMs)
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    /** 当前实际生效的解码类别；未知/还没初始化时返回 null。 */
    private fun activeVideoMode(): DecoderMode? =
        runCatching { decoderManager?.activeVideoMode }.getOrNull()

    private fun decoderModeLabel(): String = when (activeVideoMode()) {
        DecoderMode.HARDWARE -> "硬解"
        DecoderMode.SOFTWARE -> "系统软解"
        DecoderMode.FFMPEG -> "FFmpeg 软解"
        DecoderMode.AUTO -> "自动"
        null -> if (fallbackStage > 0) "FFmpeg 软解（回退）" else "未知解码器"
    }

    private fun startTicker() {
        stopTicker()
        var ticks = 0L
        val r = object : Runnable {
            override fun run() {
                val p = player
                if (p != null) {
                    runCatching {
                        _state.value = _state.value.copy(
                            positionMs = p.currentPosition.coerceAtLeast(0),
                            durationMs = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0)
                                ?: _state.value.durationMs,
                        )
                    }
                    // 每 5 秒把新增丢帧报一次（只在真丢了才报）
                    ticks++
                    if (ticks % (DROP_REPORT_MS / TICK_MS) == 0L && droppedFramesTotal > droppedFramesLogged) {
                        val delta = droppedFramesTotal - droppedFramesLogged
                        droppedFramesLogged = droppedFramesTotal
                        log("解码跟不上：最近 5 秒丢 $delta 帧（累计 $droppedFramesTotal，${decoderModeLabel()}）")
                    }
                }
                main.postDelayed(this, TICK_MS)
            }
        }
        ticker = r
        main.postDelayed(r, TICK_MS)
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    /**
     * 把 ExoPlayer 的错误码翻译成用户看得懂的一句话。
     * 直接显示 `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` 这种对用户没有意义。
     */
    private fun friendlyMessage(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> "这个容器格式本机播不了（如 WMV/ASF）"

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "本机没有能解这个视频的编码器"

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> "读不到这个文件（可能已被移动或没有权限）"

        else -> "播放失败（${error.errorCodeName}）"
    }

    private companion object {
        const val TAG = "LocalVideoPlayer"
        const val TICK_MS = 500L

        /** 每隔多久把新增丢帧汇总报一次。 */
        const val DROP_REPORT_MS = 5000L

        /** 起播后多久还没渲染出第一帧就判定硬解器吃不下（要留出起播缓冲的余量）。 */
        const val NO_FRAME_FALLBACK_MS = 4000L

        /** 切到 FFmpeg 之后再等多久还没画面就放弃（软解起播比硬解慢）。 */
        const val NO_FRAME_GIVEUP_MS = 8000L
    }
}
