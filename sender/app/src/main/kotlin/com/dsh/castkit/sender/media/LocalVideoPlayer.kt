package com.dsh.castkit.sender.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer as VlcMediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
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
    /** 本次播放是"从上次的进度接着播"的：非 0 时播放页提示一次「已从 xx:xx 继续播放」。 */
    val resumedFromMs: Long = 0,
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

    // ------------------------------------------------------------------
    // libVLC 兜底内核
    //
    // 为什么需要：Media3 与 NextLib **都没有 ASF 解封装器**（NextLib 源码里开了
    // --enable-avformat，但发布出的 AAR 不带 libavformat.so），而 NextLib 的 MIME
    // 白名单又不含 WMV/WMA、解码类还是包级私有 —— 所以 WMV/WMA 既解不出来也接不上。
    // libVLC 自带 FFmpeg 的解封装器，一次把这类容器全兜住。
    //
    // 策略：ExoPlayer 优先（已实测的路径不动），只有它报「容器/编码不认识」时才切过来。
    // ------------------------------------------------------------------

    private var vlcPlayer: VlcMediaPlayer? = null

    /** 本次播放是否已经交给过 libVLC（避免来回切）。 */
    private var vlcTried = false

    /** libVLC 播 `content://` 时我们自己持有的文件句柄，播放结束才能关。 */
    private var vlcFd: ParcelFileDescriptor? = null

    /** libVLC 是否已经出过画（收到 Vout 事件）。实测有概率起播后一直不出画，靠看门狗兜。 */
    private var vlcFirstVout = false
    private var vlcVoutRetry = 0
    private var vlcWatchdog: Runnable? = null

    @Volatile
    private var surface: Surface? = null

    /** 渲染区域真实像素尺寸的唯一来源（libVLC 的 vout 需要，Surface 本身查不到）。 */
    @Volatile
    private var surfaceHolder: SurfaceHolder? = null

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
        log("setSurface ${s?.let { "valid=${it.isValid} id=${System.identityHashCode(it)}" } ?: "null"} vlc=${vlcPlayer != null}")
        main.post {
            val vlc = vlcPlayer
            if (vlc != null) {
                // libVLC 换 Surface 必须 detach -> set -> attach，直接 set 可能不生效
                runCatching { vlc.vlcVout.detachViews() }
                if (s != null) {
                    runCatching { vlc.vlcVout.setVideoSurface(s, surfaceHolder) }
                    runCatching { vlc.vlcVout.attachViews() }
                    applyVlcWindowSize(vlc.vlcVout)
                }
            } else {
                runCatching { player?.setVideoSurface(s) }
            }
        }
    }

    /** 渲染区域的实际像素尺寸只能从 SurfaceHolder 拿；libVLC 在跑时拿到就立刻纠正。 */
    fun setSurfaceHolder(holder: SurfaceHolder) {
        surfaceHolder = holder
        main.post {
            val vlc = vlcPlayer ?: return@post
            if (runCatching { vlc.vlcVout.areViewsAttached() }.getOrDefault(false)) {
                applyVlcWindowSize(vlc.vlcVout)
            }
        }
    }

    fun play(uri: Uri, title: String) {
        main.post {
            releaseInternal()
            firstFrameRendered = false
            fallbackStage = 0
            vlcTried = false
            // 上次看到一半：这次接着播（记住的进度见 PlaybackProgress）
            val resumeFrom = PlaybackProgress.get(context, uri)
            _state.value = LocalPlaybackState(
                uri = uri,
                title = title,
                buffering = true,
                resumedFromMs = resumeFrom,
            )
            log(if (resumeFrom > 0) "本地播放: $uri（从 ${resumeFrom}ms 继续）" else "本地播放: $uri")

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
                                // 看完了：把记住的进度清掉，下次从头播（而不是从结尾接着播）
                                _state.value.uri?.let { PlaybackProgress.clear(context, it) }
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
                        // ExoPlayer 连容器都不认识（WMV/ASF 等）时交给 libVLC 再试一次，
                        // 不要直接把「播不了」甩给用户
                        if (!vlcTried && isHandoffError(error)) {
                            vlcTried = true
                            log("ExoPlayer 处理不了（${error.errorCodeName}），改用 libVLC 兜底")
                            switchToVlc(uri, title, _state.value.positionMs)
                            return
                        }
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
                // 接着上次看：seek 放在 playWhenReady 之前，免得先闪一帧片头
                if (resumeFrom > 0) {
                    runCatching { p.seekTo(resumeFrom) }
                    _state.value = _state.value.copy(positionMs = resumeFrom)
                }
                p.playWhenReady = true
            } catch (e: Throwable) {
                log("本地播放加载失败: ${e.message}")
                _state.value = _state.value.copy(error = e.message ?: "加载失败", buffering = false)
                releaseInternal()
            }
        }
    }

    /**
     * 变速播放（长按画面快进）。松手时调用方会传回 1.0。
     *
     * ExoPlayer 的 `setPlaybackSpeed` 音视频一起变速（音高由 Sonic 处理）；
     * libVLC 的 `rate` 在部分解封装器上只作用于视频轨甚至被忽略 —— 这是 libVLC 的行为，
     * 走兜底内核的片源（WMV/RMVB 等）可能只有画面变快。
     */
    fun setSpeed(rate: Float) {
        val target = rate.coerceIn(MIN_RATE, MAX_RATE)
        main.post {
            val vlc = vlcPlayer
            if (vlc != null) {
                runCatching { vlc.rate = target }
                log("变速: ${target}×（libVLC 内核）")
                return@post
            }
            runCatching { player?.setPlaybackSpeed(target) }
            log("变速: ${target}×")
        }
    }

    /**
     * 继续播放（不重新加载）。**投送结束后手机上"接着播"用的就是它** ——
     * 与 [play] 的区别是：不换片源、不重置进度，只把暂停中的播放器放开。
     */
    fun resume() {
        main.post {
            val vlc = vlcPlayer
            if (vlc != null) {
                runCatching { vlc.play() }
                _state.value = _state.value.copy(playing = true)
                startTicker()
                return@post
            }
            val p = player ?: return@post
            p.play()
            _state.value = _state.value.copy(playing = true)
            startTicker()
        }
    }

    fun toggle() {
        main.post {
            if (vlcPlayer != null) {
                toggleVlc()
                return@post
            }
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
            vlcPlayer?.let { mp ->
                if (mp.isPlaying) {
                    runCatching { mp.pause() }
                    _state.value = _state.value.copy(playing = false)
                    stopTicker()
                }
                saveProgress()
                return@post
            }
            val p = player ?: return@post
            if (p.isPlaying) {
                p.pause()
                _state.value = _state.value.copy(playing = false)
                stopTicker()
            }
            saveProgress()
        }
    }

    fun seekTo(positionMs: Long) {
        main.post {
            val duration = _state.value.durationMs
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            vlcPlayer?.let { mp ->
                runCatching { mp.time = target }
                _state.value = _state.value.copy(positionMs = target)
                return@post
            }
            val p = player ?: return@post
            runCatching { p.seekTo(target) }
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    fun release() {
        main.post {
            releaseInternal()
            // libVLC 实例是进程级共享的（VlcHolder），这里**不能**放掉它 ——
            // 列表页的媒体信息探测和缩略图抽帧还在用它。
            _state.value = LocalPlaybackState()
        }
    }

    private fun releaseInternal() {
        // 退出播放页 / 换片源 / 出错收尾：先把进度记下来（记住的进度见 PlaybackProgress）
        saveProgress()
        stopTicker()
        cancelWatchdog()
        cancelVlcWatchdog()
        firstFrameRendered = false
        fallbackStage = 0
        vlcTried = false
        vlcFirstVout = false
        vlcVoutRetry = 0
        droppedFramesTotal = 0
        droppedFramesLogged = 0
        runCatching { player?.setVideoSurface(null) }
        // 必须在 release 之前 detach
        runCatching { decoderManager?.detach() }
        decoderManager = null
        runCatching { player?.release() }
        player = null

        // libVLC 兜底内核：detach -> stop -> release；LibVLC 实例本身留着重用（创建很贵）
        vlcPlayer?.let { mp ->
            runCatching { mp.vlcVout.detachViews() }
            runCatching { mp.setEventListener(null) }
            runCatching { mp.stop() }
            runCatching { mp.detachViews() }
            runCatching { mp.release() }
        }
        vlcPlayer = null

        runCatching { vlcFd?.close() }
        vlcFd = null
    }

    // ------------------------------------------------------------------
    // libVLC 兜底内核实现
    // ------------------------------------------------------------------

    private fun isHandoffError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> true

        else -> false
    }

    /** ExoPlayer 处理不了时切到 libVLC，从当前进度接着播。 */
    private fun switchToVlc(uri: Uri, title: String, startMs: Long) {
        cancelWatchdog()
        stopTicker()
        vlcFirstVout = false
        vlcVoutRetry = 0
        // 拆掉 ExoPlayer 这一套
        runCatching { decoderManager?.detach() }
        decoderManager = null
        runCatching { player?.setVideoSurface(null) }
        runCatching { player?.release() }
        player = null

        val vlc = VlcHolder.get(context) ?: run {
            log("libVLC 初始化失败")
            _state.value = _state.value.copy(error = "本机无法播放该视频", buffering = false)
            return
        }
        val mp = try {
            VlcMediaPlayer(vlc)
        } catch (e: Throwable) {
            log("libVLC 播放器创建失败: ${e.message}")
            _state.value = _state.value.copy(error = "本机无法播放该视频", buffering = false)
            return
        }
        vlcPlayer = mp
        mp.setEventListener { event -> onVlcEvent(event) }

        val media = try {
            openVlcMedia(vlc, uri)
        } catch (e: Throwable) {
            log("libVLC 打不开片源: ${e.message}")
            _state.value = _state.value.copy(error = "本机无法播放该视频", buffering = false)
            return
        }
        if (media == null) {
            log("libVLC 打不开片源（拿不到可读句柄）")
            _state.value = _state.value.copy(error = "本机无法播放该视频", buffering = false)
            return
        }
        // 硬件解码优先；WMV3/VC-1 没有硬解时 libVLC 会自己回落软解
        runCatching { media.setHWDecoderEnabled(true, false) }
        mp.media = media
        media.release()

        val vout = mp.vlcVout
        surface?.let { runCatching { vout.setVideoSurface(it, surfaceHolder) } }
        runCatching { vout.attachViews() }
        // libVLC 的 vout 必须知道渲染区域真实像素尺寸，给大了画面会被裁到只剩黑边
        applyVlcWindowSize(vout)
        _state.value = _state.value.copy(buffering = true, playing = false, title = title)
        mp.play()
        if (startMs > 0) runCatching { mp.time = startMs }
        log("已切到 libVLC 兜底播放")
        scheduleVlcWatchdog(VLC_NO_VOUT_RETRY_MS)
        startTicker()
    }

    /**
     * 「libVLC 起播了但一直不出画」的看门狗。
     *
     * 这不是理论问题：实测同一份片源同一台机器，4 次里有 1 次 libVLC 日志显示
     * `Received first picture`、vout 也建好了，但屏幕上就是全黑 —— 典型的
     * 「vout 挂到了一个已经失效的渲染面」。重挂一次就好，所以这里主动重挂一次再判死。
     */
    private fun scheduleVlcWatchdog(delayMs: Long) {
        cancelVlcWatchdog()
        val r = object : Runnable {
            override fun run() {
                vlcWatchdog = null
                val mp = vlcPlayer ?: return
                if (vlcFirstVout) return
                if (_state.value.playing.not()) {
                    // 还没真正起播（缓冲中），给它时间
                    scheduleVlcWatchdog(2000L)
                    return
                }
                val s = surface
                if (vlcVoutRetry == 0 && s != null) {
                    vlcVoutRetry = 1
                    log("libVLC 起播后没有出画，重新挂一次渲染面")
                    runCatching { mp.vlcVout.detachViews() }
                    runCatching { mp.vlcVout.setVideoSurface(s, surfaceHolder) }
                    runCatching { mp.vlcVout.attachViews() }
                    applyVlcWindowSize(mp.vlcVout)
                    scheduleVlcWatchdog(VLC_NO_VOUT_GIVEUP_MS)
                    return
                }
                // 只有确认**确实有视频轨**才敢判死；纯音频片源本来就不会有 Vout
                val track = runCatching { mp.currentVideoTrack }.getOrNull()
                if (track != null && track.width > 0) {
                    log("libVLC 重挂渲染面后仍然没有画面")
                    _state.value = _state.value.copy(
                        error = "本机无法播放该视频",
                        playing = false,
                        buffering = false,
                    )
                    stopTicker()
                }
            }
        }
        vlcWatchdog = r
        main.postDelayed(r, delayMs)
    }

    private fun cancelVlcWatchdog() {
        vlcWatchdog?.let { main.removeCallbacks(it) }
        vlcWatchdog = null
    }

    /**
     * 把 MediaStore 的 `content://` 变成 libVLC 打得开的片源。
     *
     * libVLC 的 access 模块里**没有** `content` 这一项（实测日志：
     * `stream: looking for access module matching "content": 25 candidates` → `no access modules matched`），
     * 所以不能直接把 `content://` 丢给它。改成先向 ContentResolver 要一个文件句柄，
     * 用 fd 建 Media（libVLC 自己会 dup 这个 fd），句柄留到播放结束再关。
     */
    private fun openVlcMedia(vlc: LibVLC, uri: Uri): Media? {
        if (uri.scheme != "content") return Media(vlc, uri)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        vlcFd = pfd
        return Media(vlc, pfd.fileDescriptor)
    }

    private fun onVlcEvent(event: VlcMediaPlayer.Event) {
        val mp = vlcPlayer ?: return
        when (event.type) {
            VlcMediaPlayer.Event.Playing -> {
                _state.value = _state.value.copy(playing = true, buffering = false)
                syncVlcVideoSize(mp)
                log("libVLC 已开始播放")
            }

            VlcMediaPlayer.Event.Paused ->
                _state.value = _state.value.copy(playing = false)

            VlcMediaPlayer.Event.Buffering ->
                _state.value = _state.value.copy(buffering = event.buffering < 100f)

            VlcMediaPlayer.Event.LengthChanged ->
                _state.value = _state.value.copy(durationMs = mp.length.coerceAtLeast(0))

            VlcMediaPlayer.Event.Vout -> {
                vlcFirstVout = true
                cancelVlcWatchdog()
                syncVlcVideoSize(mp)
                log(
                    "libVLC 出画: 轨道=${mp.currentVideoTrack?.width}x${mp.currentVideoTrack?.height}" +
                        " attached=${runCatching { mp.vlcVout.areViewsAttached() }.getOrDefault(false)}" +
                        " surfaceValid=${surface?.isValid}"
                )
            }

            VlcMediaPlayer.Event.EndReached -> {
                log("libVLC 播放结束")
                stopTicker()
                cancelWatchdog()
                cancelVlcWatchdog()
                _state.value = _state.value.copy(
                    playing = false,
                    buffering = false,
                    positionMs = _state.value.durationMs,
                )
                // 看完了：清掉"下次接着看"的进度
                _state.value.uri?.let { PlaybackProgress.clear(context, it) }
            }

            VlcMediaPlayer.Event.EncounteredError -> {
                log("libVLC 播放失败")
                cancelVlcWatchdog()
                _state.value = _state.value.copy(
                    error = "本机无法播放该视频",
                    playing = false,
                    buffering = false,
                )
                stopTicker()
            }
        }
    }

    private fun syncVlcVideoSize(mp: VlcMediaPlayer) {
        val track = runCatching { mp.currentVideoTrack }.getOrNull() ?: return
        if (track.width > 0 && track.height > 0) {
            _state.value = _state.value.copy(width = track.width, height = track.height)
        }
    }

    /**
     * libVLC 的 vout 必须知道目标窗口尺寸，而且这个尺寸得**等于渲染面的真实像素尺寸**。
     * 给大了不会报错：libVLC 照样解码、照样出画，只是把画面按更大的画布居中排版，
     * 而 SurfaceView 只显示其中一块 —— 结果就是「进度在走、日志说收到画面、屏幕全黑」。
     */
    private fun applyVlcWindowSize(vout: IVLCVout) {
        val dm = context.resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        val holder = surfaceHolder
        if (holder != null) {
            val f = runCatching { holder.surfaceFrame }.getOrNull()
            if (f != null && f.width() > 0 && f.height() > 0) {
                w = f.width()
                h = f.height()
            }
        }
        runCatching { vout.setWindowSize(w, h) }
        log("libVLC 渲染窗口 ${w}x${h}")
    }

    private fun toggleVlc() {
        val mp = vlcPlayer ?: return
        if (mp.isPlaying) {
            runCatching { mp.pause() }
            _state.value = _state.value.copy(playing = false)
            stopTicker()
        } else {
            runCatching { mp.play() }
            _state.value = _state.value.copy(playing = true)
            startTicker()
        }
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
                ticks++
                val vlc = vlcPlayer
                if (vlc != null) {
                    runCatching {
                        _state.value = _state.value.copy(
                            positionMs = vlc.time.coerceAtLeast(0),
                            durationMs = vlc.length.takeIf { it > 0 } ?: _state.value.durationMs,
                        )
                    }
                }
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
                    if (ticks % (DROP_REPORT_MS / TICK_MS) == 0L && droppedFramesTotal > droppedFramesLogged) {
                        val delta = droppedFramesTotal - droppedFramesLogged
                        droppedFramesLogged = droppedFramesTotal
                        log("解码跟不上：最近 5 秒丢 $delta 帧（累计 $droppedFramesTotal，${decoderModeLabel()}）")
                    }
                }
                // 每 5 秒记一次播放进度：中途被杀/断电也不会丢掉太多
                if (ticks % (PROGRESS_SAVE_MS / TICK_MS) == 0L) saveProgress()
                main.postDelayed(this, TICK_MS)
            }
        }
        ticker = r
        main.postDelayed(r, TICK_MS)
    }

    /** 把当前进度写进"下次接着看"的记录（没有片源或还没起播时什么都不做）。 */
    private fun saveProgress() {
        val s = _state.value
        val uri = s.uri ?: return
        if (s.positionMs <= 0) return
        PlaybackProgress.save(context, uri, s.positionMs, s.durationMs)
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

        /** 每隔多久把播放进度写进"下次接着看"的记录。 */
        const val PROGRESS_SAVE_MS = 5000L

        /** 起播后多久还没渲染出第一帧就判定硬解器吃不下（要留出起播缓冲的余量）。 */
        const val NO_FRAME_FALLBACK_MS = 4000L

        /** 切到 FFmpeg 之后再等多久还没画面就放弃（软解起播比硬解慢）。 */
        const val NO_FRAME_GIVEUP_MS = 8000L

        /** libVLC 起播后多久还没出画就重挂一次渲染面。 */
        const val VLC_NO_VOUT_RETRY_MS = 6000L

        /** 重挂渲染面之后再等多久还没画面就判定放不出来。 */
        const val VLC_NO_VOUT_GIVEUP_MS = 6000L

        /** 变速允许的范围：长按快进用得到 3×，留点余量；低于 0.25× 基本等于暂停。 */
        const val MIN_RATE = 0.25f
        const val MAX_RATE = 4.0f
    }
}
