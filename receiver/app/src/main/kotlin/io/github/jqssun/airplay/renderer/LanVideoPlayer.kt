package io.github.jqssun.airplay.renderer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

/** 局域网「投视频文件」的播放状态，供 UI 展示。 */
data class LanVideoState(
    val url: String? = null,
    val title: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val error: String? = null,
    /** 播放源已消失时的友好提示（短暂显示后自动退出播放页）。 */
    val ended: String? = null,
) {
    /** 视频自身比例（0 = 未知，UI 退回 16:9）。 */
    val aspect: Float = if (width > 0 && height > 0) width.toFloat() / height else 0f

    val active: Boolean get() = url != null
}

/**
 * 局域网视频播放器（发送端「投视频文件」用）。
 *
 * 这里播的是发送端通过局域网 HTTP 暴露的**原始文件**，不做转码。
 *
 * ## 为什么是 ExoPlayer + FFmpeg（2026-09 真机实测结论）
 *
 * 原来用系统 `MediaPlayer`，它对**解封装**环节没有自主权 —— 用的是安卓自带的
 * `MPEG4Extractor`，而那个解封装器遇到病态时间基会**直接弃轨**：
 *
 * ```
 * MPEG4Extractor: track->timescale overflow
 * ```
 *
 * （实测样本：容器 `mdhd` timescale = 2^31−1、VUI `time_scale` = 2^32−2。）
 * 症状是音频正常、进度条正常、**画面全黑**，连视频解码器都不会被创建。
 * 换成 Media3 的 `Mp4Extractor`（它自己用 Java 重写的一套，时间戳走防溢出的
 * `Util.scaleLargeTimestamp`）之后，同一台机器上该样本**满 30fps 零丢帧**。
 *
 * 但**解码器**这一侧仍然可能吃不下：
 * 样本 FC2 是 1080i MBAFF 隔行流，小米自带的 `c2.xring.avc.decoder` 会**静默丢弃每一个
 * buffer**（`MediaCodec discarded an unknown buffer`），同样表现为黑屏，而且**不报错**。
 * 这种情况 NextLib 文档里说得很明确：`AUTO` 模式只在「平台解不了这个格式」时兜底，
 * **不会从运行时解码失败里恢复**。
 *
 * 所以这里的策略是 **硬解优先 + 看门狗回退 FFmpeg 软解**：
 *  1. 正常起播走硬解（性能最好）；
 *  2. 起播后 [NO_FRAME_FALLBACK_MS] 内一帧都没渲染出来（`onRenderedFirstFrame` 没触发）
 *     就判定硬解器吃不下，切到 `DecoderMode.FFMPEG`；
 *  3. 切到 FFmpeg 是「remap tracks without stopping」，不打断播放、保留进度；
 *  4. 再等 [NO_FRAME_GIVEUP_MS] 还是没画面，才认输并给出提示。
 *
 * 这样正常片源完全不受影响（不会无谓地走软解），只有坏片源才付软解的代价。
 *
 * 线程约定：ExoPlayer 必须在带 Looper 的线程上创建和访问，这里统一 post 到主线程。
 */
class LanVideoPlayer(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(LanVideoState())
    val state: StateFlow<LanVideoState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

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

    /**
     * 累计丢帧（解码器跟不上的直接证据）。
     * 软件解码路径没有 MediaCodec 的 `video-debug-dec` 统计日志，只能自己数。
     */
    private var droppedFramesTotal = 0
    private var droppedFramesLogged = 0

    /** 播放器还没就绪时先记住要跳的位置，就绪后立刻应用（开投时发送端会带上本机进度）。 */
    private var pendingSeekMs = -1L

    /** UI 提供/回收渲染 Surface（可能在 play 之前或之后到达）。 */
    fun setSurface(s: Surface?) {
        surface = s
        main.post {
            runCatching { player?.setVideoSurface(s) }
        }
    }

    fun play(url: String, title: String) {
        main.post {
            releaseInternal()
            pendingSeekMs = -1L
            firstFrameRendered = false
            fallbackStage = 0
            _state.value = LanVideoState(url = url, title = title, buffering = true)
            onLog("局域网视频开始加载: $url")

            val manager = DecoderManager()
            decoderManager = manager
            val renderersFactory = NextRenderersFactory(context)
                .setDecoderManager(manager)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            val p = try {
                ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
            } catch (e: Throwable) {
                onLog("ExoPlayer 创建失败: ${e.message}")
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
                    /* handleAudioFocus = */ false,
                )
                // Media3 1.11 里没有 `C.SEEK_CLOSEST`，两参数的 seekTo 也没了，
                // 精确 seek 改由 SeekParameters 表达。原来 MediaPlayer 用的是
                // `seekTo(ms, SEEK_CLOSEST)`（从前一个关键帧解起、落到精确位置），
                // 这里用 EXACT 保持同样的语义 —— 发送端的进度条依赖这个精度。
                p.setSeekParameters(SeekParameters.EXACT)
                p.setVideoSurface(surface)
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING ->
                                _state.value = _state.value.copy(buffering = true)

                            Player.STATE_READY -> {
                                val duration = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0L
                                _state.value = _state.value.copy(
                                    buffering = false,
                                    playing = p.isPlaying,
                                    durationMs = duration,
                                )
                                // 就绪即起播：与原来 MediaPlayer 的 onPrepared -> start() 行为一致
                                p.playWhenReady = true
                                // 发送端开投时可能已经带了本机进度：就绪后立刻跳过去
                                val pending = pendingSeekMs
                                pendingSeekMs = -1L
                                if (pending > 0) {
                                    onLog("局域网视频就绪（时长 ${duration}ms），跳到 ${pending}ms 后播放")
                                    runCatching { p.seekTo(pending) }
                                    _state.value = _state.value.copy(positionMs = pending)
                                } else {
                                    onLog("局域网视频就绪（时长 ${duration}ms），开始播放")
                                }
                                startTicker()
                                // 就绪了才谈得上「有没有画面」，看门狗从这时开始计时
                                scheduleWatchdog(NO_FRAME_FALLBACK_MS)
                            }

                            Player.STATE_ENDED -> {
                                onLog("局域网视频播放结束")
                                stopInternal()
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
                        // 必须报告**真实**用的解码器，不能只看守门狗有没有触发：
                        // 实测 FC2 根本没走我们的回退逻辑 —— Media3 自己就把它交给了 FFmpeg
                        // （全程没有创建任何视频 MediaCodec），这种情况 fallbackStage 仍是 0。
                        onLog("视频画面已出（${decoderModeLabel()}）")
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width <= 0 || videoSize.height <= 0) return
                        // MediaPlayer 的 onVideoSizeChanged 给的是**显示**尺寸，
                        // 这里把像素宽高比折算进去，行为保持一致
                        val ratio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                        _state.value = _state.value.copy(
                            width = (videoSize.width * ratio).roundToInt(),
                            height = videoSize.height,
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // 原始错误只进日志；给用户的提示按错误类别翻译成人话。
                        // 分类依据是实测：换到 ExoPlayer + NextLib 后，容器和编码这两类失败
                        // 是用户最可能撞上的（例：WMV/ASF 没有解封装器 -> PARSING_CONTAINER_UNSUPPORTED）。
                        val friendly = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                            ->
                                "接收端不认识这个容器格式（如 WMV/ASF）"

                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                            ->
                                "接收端解不了这个视频编码"

                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                            ->
                                "取不到片源（网络中断或文件已失效）"

                            else -> "投送已结束"
                        }
                        onLog("局域网视频播放失败 ${error.errorCodeName}，结束本次投送")
                        _state.value = _state.value.copy(
                            error = null,
                            playing = false,
                            buffering = false,
                            ended = friendly,
                        )
                        main.postDelayed({ stopInternal() }, ERROR_EXIT_DELAY_MS)
                    }
                })
                // 软件解码路径（FFmpeg / 系统软解）没有 MediaCodec 的 `video-debug-dec` 统计，
                // 只能自己数丢帧 —— 这是判断中端机能不能跑满 1080i/4K 的唯一依据。
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long,
                    ) {
                        droppedFramesTotal += droppedFrames
                    }
                })
                p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                p.prepare()
                p.playWhenReady = true            } catch (e: Throwable) {
                onLog("局域网视频加载失败: ${e.message}")
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

    fun play() {
        main.post {
            val p = player ?: return@post
            p.play()
            _state.value = _state.value.copy(playing = true)
            startTicker()
        }
    }

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
            val p = player
            val duration = _state.value.durationMs
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            // 还没就绪（正在 prepare）：先记住，就绪后再跳
            if (p == null || p.playbackState != Player.STATE_READY) {
                pendingSeekMs = target
                _state.value = _state.value.copy(positionMs = target)
                return@post
            }
            runCatching { p.seekTo(target) }
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    /** 用户/发送端要求停止：回到空闲状态。 */
    fun stop() {
        main.post {
            stopInternal()
            onLog("局域网视频已停止")
        }
    }

    fun release() {
        main.post { releaseInternal() }
    }

    private fun stopInternal() {
        stopTicker()
        releaseInternal()
        _state.value = LanVideoState()
    }

    private fun releaseInternal() {
        stopTicker()
        cancelWatchdog()
        pendingSeekMs = -1L
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
     * 存在的理由：硬解器吃不下某些码流时**不会报错**，只是静默丢弃 buffer
     * （1080i MBAFF 实测就是 `MediaCodec discarded an unknown buffer`），
     * 而 Media3 的 `AUTO` 模式只在「格式不被支持」时兜底，管不到这种运行期失败。
     */
    private fun scheduleWatchdog(delayMs: Long) {
        cancelWatchdog()
        val r = object : Runnable {
            override fun run() {
                watchdog = null
                val p = player ?: return
                if (firstFrameRendered) return
                // 还在缓冲/准备就先不算，等它进入 READY 再说
                if (p.playbackState != Player.STATE_READY) {
                    scheduleWatchdog(NO_FRAME_FALLBACK_MS)
                    return
                }
                if (fallbackStage == 0) {
                    fallbackStage = 1
                    onLog("硬解 ${NO_FRAME_FALLBACK_MS / 1000} 秒内没有出画面，切换 FFmpeg 软解重试")
                    runCatching { decoderManager?.selectVideoDecoder(DecoderMode.FFMPEG) }
                        .onFailure { onLog("切换 FFmpeg 失败: ${it.message}") }
                    scheduleWatchdog(NO_FRAME_GIVEUP_MS)
                } else {
                    onLog("FFmpeg 软解仍然没有画面，判定接收端解不了这个片源")
                    _state.value = _state.value.copy(
                        error = null,
                        playing = false,
                        buffering = false,
                        ended = "接收端无法播放该视频",
                    )
                    main.postDelayed({ stopInternal() }, ERROR_EXIT_DELAY_MS)
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
                        val duration = p.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0)
                            ?: _state.value.durationMs
                        _state.value = _state.value.copy(
                            positionMs = p.currentPosition.coerceAtLeast(0),
                            durationMs = duration,
                        )
                    }
                    // 每 5 秒把新增的丢帧报一次（只在真的丢了才报，避免刷屏）
                    ticks++
                    if (ticks % (TICKER_DROP_REPORT_MS / TICK_MS) == 0L &&
                        droppedFramesTotal > droppedFramesLogged
                    ) {
                        val delta = droppedFramesTotal - droppedFramesLogged
                        droppedFramesLogged = droppedFramesTotal
                        onLog("解码跟不上：最近 5 秒丢 $delta 帧（累计 $droppedFramesTotal，${decoderModeLabel()}）")
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

    private companion object {
        const val TICK_MS = 500L

        /** 每隔多久把新增丢帧汇总报一次。 */
        const val TICKER_DROP_REPORT_MS = 5000L

        /** 播放失败后停留多久再自动收起播放页。 */
        const val ERROR_EXIT_DELAY_MS = 1800L

        /**
         * 起播后多久还没渲染出第一帧就判定硬解器吃不下。
         * 取值要够宽：网络起播本身可能有几百 ms 到一两秒的缓冲。
         */
        const val NO_FRAME_FALLBACK_MS = 4000L

        /** 切到 FFmpeg 之后再等多久还没画面就放弃（软解起播比硬解慢）。 */
        const val NO_FRAME_GIVEUP_MS = 8000L
    }
}
