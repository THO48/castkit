package io.github.jqssun.airplay.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val speed: Float = 1f,
    val error: String? = null,
    /** 播放源已消失时的友好提示（短暂显示后自动退出播放页）。 */
    val ended: String? = null,
) {
    /** 视频自身比例（0 = 未知，UI 退回 16:9）。 */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f

    val active: Boolean get() = url != null
}

/**
 * 局域网视频播放器（发送端「投视频文件」用）。
 *
 * 基于 Media3 [ExoPlayer] 实现：
 * - 同进程直连 Surface，彻底避免跨进程 MediaPlayer 切片时的 BufferQueue 竞态黑屏；
 * - 启用硬件解码失败自动回落软件解码（`setEnableDecoderFallback(true)`）并放宽超规轨道限制；
 * - 内置无画面看门狗：若视频轨在起播后仍未渲染首帧（有声黑屏），自动切换到软件解码优先并断点续播。
 *
 * 线程约定：ExoPlayer 必须在主线程访问，这里所有公开方法统一 post 到主线程。
 */
@OptIn(UnstableApi::class)
class LanVideoPlayer(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(LanVideoState())
    val state: StateFlow<LanVideoState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @Volatile
    private var pendingSurface: Surface? = null
    private var surfaceBound = false

    private var ticker: Runnable? = null
    private var blackScreenWatchdog: Runnable? = null
    private var errorExitRunnable: Runnable? = null

    private var currentUrl: String? = null
    private var currentTitle: String = ""
    private var prepared = false
    private var pendingSeekMs = -1L
    private var desiredSpeed = 1f

    private var hasVideoTrack = false
    private var renderedFirstFrame = false
    private var usingSoftwareVideo = false
    private var softwareFallbackAttempted = false

    /** UI 提供渲染 Surface（可能在 play 之前或之后到达）。 */
    fun setSurface(s: Surface?) {
        main.post {
            if (s == null) {
                pendingSurface = null
                surfaceBound = false
                player?.setVideoSurface(null)
                return@post
            }
            if (pendingSurface === s && surfaceBound && player != null) {
                return@post
            }
            pendingSurface = s
            player?.let { p ->
                p.setVideoSurface(s)
                surfaceBound = true
                if (p.playbackState == Player.STATE_READY && p.playWhenReady && !renderedFirstFrame) {
                    scheduleBlackScreenWatchdog()
                }
            }
        }
    }

    /** UI 回收渲染 Surface：仅在仍是当前有效 Surface 时解绑，避免旧 Surface 销毁误清空新画面。 */
    fun clearSurface(surface: Surface) {
        main.post {
            if (pendingSurface !== surface) return@post
            pendingSurface = null
            surfaceBound = false
            player?.clearVideoSurface(surface)
        }
    }

    fun play(url: String, title: String) {
        main.post {
            cancelPendingErrorExit()
            cancelBlackScreenWatchdog()
            currentUrl = url
            currentTitle = title
            prepared = false
            pendingSeekMs = -1L
            desiredSpeed = 1f
            hasVideoTrack = false
            renderedFirstFrame = false
            softwareFallbackAttempted = false
            _state.value = LanVideoState(url = url, title = title, buffering = true)
            onLog("局域网视频开始加载: $url")
            startPlaybackInternal(url = url, startPositionMs = 0L, preferSoftwareVideo = false)
        }
    }

    private fun startPlaybackInternal(
        url: String,
        startPositionMs: Long,
        preferSoftwareVideo: Boolean,
    ) {
        cancelBlackScreenWatchdog()
        renderedFirstFrame = false
        hasVideoTrack = false

        val needRecreate = player == null || usingSoftwareVideo != preferSoftwareVideo
        val p = if (needRecreate) {
            releasePlayerOnly()
            usingSoftwareVideo = preferSoftwareVideo
            buildExoPlayer(preferSoftwareVideo).also { created ->
                player = created
                pendingSurface?.let { s ->
                    if (s.isValid) {
                        created.setVideoSurface(s)
                        surfaceBound = true
                    }
                }
            }
        } else {
            player!!.also { existing ->
                existing.stop()
                existing.clearMediaItems()
                pendingSurface?.let { s ->
                    if (s.isValid && !surfaceBound) {
                        existing.setVideoSurface(s)
                        surfaceBound = true
                    }
                }
            }
        }

        try {
            val item = MediaItem.fromUri(url)
            if (startPositionMs > 0L) {
                p.setMediaItem(item, startPositionMs)
            } else {
                p.setMediaItem(item)
            }
            p.playbackParameters = PlaybackParameters(desiredSpeed)
            p.playWhenReady = true
            p.prepare()
        } catch (e: Throwable) {
            onLog("局域网视频加载失败: ${e.message}")
            _state.value = _state.value.copy(error = e.message ?: "加载失败", buffering = false)
            releasePlayerOnly()
        }
    }

    private fun buildExoPlayer(preferSoftwareVideo: Boolean): ExoPlayer {
        val codecSelector = if (preferSoftwareVideo) {
            SoftwarePreferredCodecSelector
        } else {
            MediaCodecSelector.DEFAULT
        }
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .build()
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setUserAgent("CastKit/2.0")
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                addListener(playerListener)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    _state.value = _state.value.copy(buffering = true)
                }
                Player.STATE_READY -> {
                    val rawDur = p.duration
                    val dur = if (rawDur != C.TIME_UNSET && rawDur > 0) rawDur else 0L
                    if (!prepared) {
                        prepared = true
                        onLog("局域网视频就绪（时长 ${dur}ms${if (usingSoftwareVideo) "，软件解码" else ""}），开始播放")
                        val pending = pendingSeekMs
                        pendingSeekMs = -1L
                        if (pending > 0L) {
                            val target = if (dur > 0L) pending.coerceIn(0L, dur) else pending
                            p.seekTo(target)
                            _state.value = _state.value.copy(positionMs = target)
                        }
                        if (desiredSpeed != 1f) {
                            p.playbackParameters = PlaybackParameters(desiredSpeed)
                        }
                    }
                    _state.value = _state.value.copy(
                        buffering = false,
                        playing = p.playWhenReady,
                        durationMs = dur,
                        positionMs = p.currentPosition.coerceAtLeast(0L),
                    )
                    if (p.playWhenReady) {
                        startTicker()
                        if (!renderedFirstFrame && pendingSurface != null) {
                            scheduleBlackScreenWatchdog()
                        }
                    } else {
                        stopTicker()
                    }
                }
                Player.STATE_ENDED -> {
                    cancelBlackScreenWatchdog()
                    onLog("局域网视频播放结束")
                    stopInternal()
                }
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val p = player ?: return
            if (prepared) {
                _state.value = _state.value.copy(playing = playWhenReady)
                if (playWhenReady) {
                    startTicker()
                    if (!renderedFirstFrame && pendingSurface != null) {
                        scheduleBlackScreenWatchdog()
                    }
                } else {
                    stopTicker()
                    cancelBlackScreenWatchdog()
                }
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            var foundVideo = false
            var selectedVideo = false
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_VIDEO) {
                    foundVideo = true
                    if (group.isSelected) selectedVideo = true
                }
            }
            hasVideoTrack = foundVideo
            if (foundVideo && !selectedVideo && !softwareFallbackAttempted) {
                onLog("硬件解码器未选中视频轨道，尝试切换软件解码")
                triggerSoftwareFallback()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val displayW = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
            _state.value = _state.value.copy(width = displayW, height = videoSize.height)
        }

        override fun onRenderedFirstFrame() {
            renderedFirstFrame = true
            cancelBlackScreenWatchdog()
            _state.value = _state.value.copy(buffering = false)
        }

        override fun onPlayerError(error: PlaybackException) {
            cancelBlackScreenWatchdog()
            val isDecoderError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED

            if (isDecoderError && !softwareFallbackAttempted && currentUrl != null) {
                onLog("硬件解码异常 (${error.errorCodeName})，自动切换软件解码重试")
                triggerSoftwareFallback()
                return
            }

            onLog("局域网视频播放失败 code=${error.errorCodeName} (${error.message})，结束本次投送")
            _state.value = _state.value.copy(
                error = null,
                playing = false,
                buffering = false,
                ended = "投送已结束",
            )
            cancelPendingErrorExit()
            val r = Runnable { stopInternal() }
            errorExitRunnable = r
            main.postDelayed(r, ERROR_EXIT_DELAY_MS)
        }
    }

    private fun scheduleBlackScreenWatchdog() {
        cancelBlackScreenWatchdog()
        if (softwareFallbackAttempted || renderedFirstFrame) return
        val r = Runnable {
            val p = player ?: return@Runnable
            if (renderedFirstFrame || softwareFallbackAttempted) return@Runnable
            if (pendingSurface == null || !p.playWhenReady || p.playbackState != Player.STATE_READY) return@Runnable
            if (hasVideoTrack || _state.value.width == 0) {
                onLog("检测到视频无画面输出，自动切换软件解码重试…")
                triggerSoftwareFallback()
            }
        }
        blackScreenWatchdog = r
        main.postDelayed(r, BLACK_SCREEN_WATCHDOG_MS)
    }

    private fun cancelBlackScreenWatchdog() {
        blackScreenWatchdog?.let { main.removeCallbacks(it) }
        blackScreenWatchdog = null
    }

    private fun cancelPendingErrorExit() {
        errorExitRunnable?.let { main.removeCallbacks(it) }
        errorExitRunnable = null
    }

    private fun triggerSoftwareFallback() {
        val url = currentUrl ?: return
        if (softwareFallbackAttempted) return
        softwareFallbackAttempted = true
        val resumePos = player?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs
        prepared = false
        _state.value = _state.value.copy(buffering = true)
        startPlaybackInternal(url = url, startPositionMs = resumePos, preferSoftwareVideo = true)
    }

    fun toggle() {
        main.post {
            val p = player ?: return@post
            val next = !p.playWhenReady
            p.playWhenReady = next
            _state.value = _state.value.copy(playing = next)
            if (next) startTicker() else stopTicker()
        }
    }

    fun play() {
        main.post {
            val p = player ?: return@post
            p.playWhenReady = true
            _state.value = _state.value.copy(playing = true)
            startTicker()
        }
    }

    fun pause() {
        main.post {
            val p = player ?: return@post
            p.playWhenReady = false
            _state.value = _state.value.copy(playing = false)
            stopTicker()
        }
    }

    fun seekTo(positionMs: Long) {
        main.post {
            val p = player
            val duration = _state.value.durationMs
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            if (p == null || !prepared) {
                pendingSeekMs = target
                _state.value = _state.value.copy(positionMs = target)
                return@post
            }
            p.seekTo(target)
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(_state.value.positionMs + deltaMs)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        main.post {
            desiredSpeed = clamped
            _state.value = _state.value.copy(speed = clamped)
            player?.playbackParameters = PlaybackParameters(clamped)
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
        main.post {
            cancelPendingErrorExit()
            cancelBlackScreenWatchdog()
            stopTicker()
            releasePlayerOnly()
        }
    }

    private fun stopInternal() {
        cancelPendingErrorExit()
        cancelBlackScreenWatchdog()
        stopTicker()
        currentUrl = null
        currentTitle = ""
        prepared = false
        releasePlayerOnly()
        _state.value = LanVideoState()
    }

    private fun releasePlayerOnly() {
        prepared = false
        surfaceBound = false
        player?.let { p ->
            runCatching { p.removeListener(playerListener) }
            runCatching { p.setVideoSurface(null) }
            runCatching { p.release() }
        }
        player = null
    }

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                val p = player
                if (p != null) {
                    val rawDur = p.duration
                    val dur = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else _state.value.durationMs
                    _state.value = _state.value.copy(
                        positionMs = p.currentPosition.coerceAtLeast(0L),
                        durationMs = dur,
                    )
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

    /** 软件视频解码器优先选择器：在硬件解码异常或黑屏时将软件解码器排到最前。 */
    private object SoftwarePreferredCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): MutableList<MediaCodecInfo> {
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (!mimeType.startsWith("video/")) return all.toMutableList()
            val sw = mutableListOf<MediaCodecInfo>()
            val hw = mutableListOf<MediaCodecInfo>()
            for (info in all) {
                val lower = info.name.lowercase()
                val isSw = info.softwareOnly ||
                    lower.startsWith("c2.android.") ||
                    lower.startsWith("omx.google.")
                if (isSw) sw += info else hw += info
            }
            return (sw + hw).toMutableList()
        }
    }

    private companion object {
        const val TICK_MS = 500L
        const val HTTP_CONNECT_TIMEOUT_MS = 10_000
        const val HTTP_READ_TIMEOUT_MS = 20_000
        const val BLACK_SCREEN_WATCHDOG_MS = 2_800L
        /** 播放失败后停留多久再自动收起播放页。 */
        const val ERROR_EXIT_DELAY_MS = 1_800L
    }
}
