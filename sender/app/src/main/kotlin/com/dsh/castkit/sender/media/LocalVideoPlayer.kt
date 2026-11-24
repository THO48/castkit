package com.dsh.castkit.sender.media

import android.content.Context
import android.net.Uri
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val speed: Float = 1f,
    val completed: Boolean = false,
    val error: String? = null,
) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}

/**
 * 应用内本地视频播放器（基于 Media3 ExoPlayer）：
 * - 支持倍速、快进/快退、断点续播；
 * - 硬件解码失败自动回落软件解码，内置有声黑屏自检看门狗与安全 Surface 生命周期管理。
 */
@OptIn(UnstableApi::class)
class LocalVideoPlayer(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(LocalPlaybackState())
    val state: StateFlow<LocalPlaybackState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @Volatile
    private var pendingSurface: Surface? = null
    private var surfaceBound = false

    private var ticker: Runnable? = null
    private var blackScreenWatchdog: Runnable? = null

    private var currentUri: Uri? = null
    private var prepared = false
    private var pendingSeekMs = -1L
    private var desiredSpeed = 1f

    private var hasVideoTrack = false
    private var renderedFirstFrame = false
    private var usingSoftwareVideo = false
    private var softwareFallbackAttempted = false

    fun setSurface(s: Surface?) {
        main.post {
            if (s == null) {
                pendingSurface = null
                surfaceBound = false
                player?.setVideoSurface(null)
                return@post
            }
            if (pendingSurface === s && surfaceBound && player != null) return@post
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

    /** 仅在销毁的 Surface 仍是当前有效 Surface 时解绑，避免切换布局误清空新画面。 */
    fun clearSurface(surface: Surface) {
        main.post {
            if (pendingSurface !== surface) return@post
            pendingSurface = null
            surfaceBound = false
            player?.clearVideoSurface(surface)
        }
    }

    fun play(uri: Uri, title: String, startPositionMs: Long = 0L) {
        main.post {
            cancelBlackScreenWatchdog()
            currentUri = uri
            prepared = false
            desiredSpeed = 1f
            pendingSeekMs = startPositionMs.takeIf { it > 0L } ?: -1L
            hasVideoTrack = false
            renderedFirstFrame = false
            softwareFallbackAttempted = false
            _state.value = LocalPlaybackState(
                uri = uri,
                title = title,
                buffering = true,
                positionMs = startPositionMs.coerceAtLeast(0L),
            )
            onLog("本地播放: $uri")
            startPlaybackInternal(uri = uri, startPositionMs = startPositionMs.coerceAtLeast(0L), preferSoftwareVideo = false)
        }
    }

    private fun startPlaybackInternal(
        uri: Uri,
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
            val item = MediaItem.fromUri(uri)
            if (startPositionMs > 0L) {
                p.setMediaItem(item, startPositionMs)
            } else {
                p.setMediaItem(item)
            }
            p.playbackParameters = PlaybackParameters(desiredSpeed)
            p.playWhenReady = true
            p.prepare()
        } catch (e: Throwable) {
            onLog("本地播放加载失败: ${e.message}")
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

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
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
                    val dur = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else 0L
                    if (!prepared) {
                        prepared = true
                        val pending = pendingSeekMs
                        pendingSeekMs = -1L
                        if (pending > 0L && (dur == 0L || pending < dur - 2000L)) {
                            p.seekTo(pending)
                        }
                        if (desiredSpeed != 1f) {
                            p.playbackParameters = PlaybackParameters(desiredSpeed)
                        }
                    }
                    _state.value = _state.value.copy(
                        buffering = false,
                        playing = p.playWhenReady,
                        completed = false,
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
                    _state.value = _state.value.copy(
                        playing = false,
                        completed = true,
                        positionMs = _state.value.durationMs,
                    )
                    stopTicker()
                }
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
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
                onLog("本地硬解未选中视频轨道，自动切换软件解码")
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

            if (isDecoderError && !softwareFallbackAttempted && currentUri != null) {
                onLog("本地硬解异常 (${error.errorCodeName})，自动切换软件解码重试")
                triggerSoftwareFallback()
                return
            }

            val msg = "无法播放此视频（${error.errorCodeName}）"
            onLog(msg)
            _state.value = _state.value.copy(error = msg, playing = false, buffering = false)
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
                onLog("本地播放无画面输出，自动切换软件解码重试")
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

    private fun triggerSoftwareFallback() {
        val uri = currentUri ?: return
        if (softwareFallbackAttempted) return
        softwareFallbackAttempted = true
        val resumePos = player?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs
        prepared = false
        _state.value = _state.value.copy(buffering = true)
        startPlaybackInternal(uri = uri, startPositionMs = resumePos, preferSoftwareVideo = true)
    }

    fun toggle() {
        main.post {
            val p = player ?: return@post
            if (!prepared) return@post
            if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) {
                p.playWhenReady = false
                _state.value = _state.value.copy(playing = false)
                stopTicker()
            } else {
                if (_state.value.completed || p.playbackState == Player.STATE_ENDED) {
                    p.seekTo(0L)
                }
                p.playWhenReady = true
                _state.value = _state.value.copy(playing = true, completed = false)
                startTicker()
            }
        }
    }

    /** 暂停（已经在暂停/未起播则什么都不做）。 */
    fun pause() {
        main.post {
            val p = player ?: return@post
            if (p.playWhenReady) {
                p.playWhenReady = false
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
            if (p == null || !prepared) {
                pendingSeekMs = target
                _state.value = _state.value.copy(positionMs = target)
                return@post
            }
            p.seekTo(target)
            _state.value = _state.value.copy(positionMs = target, completed = false)
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

    fun release() {
        main.post {
            cancelBlackScreenWatchdog()
            stopTicker()
            currentUri = null
            releasePlayerOnly()
            _state.value = LocalPlaybackState()
        }
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
                val p = player ?: return
                val rawDur = p.duration
                val dur = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else _state.value.durationMs
                _state.value = _state.value.copy(
                    positionMs = p.currentPosition.coerceAtLeast(0L),
                    durationMs = dur,
                )
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
        const val TICK_MS = 350L
        const val BLACK_SCREEN_WATCHDOG_MS = 2_800L
    }
}
