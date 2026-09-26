package com.dsh.castkit.sender.media

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

    @Volatile
    private var surface: Surface? = null

    private var ticker: Runnable? = null

    fun setSurface(s: Surface?) {
        surface = s
        main.post { runCatching { player?.setVideoSurface(s) } }
    }

    fun play(uri: Uri, title: String) {
        main.post {
            releaseInternal()
            _state.value = LocalPlaybackState(uri = uri, title = title, buffering = true)
            onLog("本地播放: $uri")

            val p = try {
                ExoPlayer.Builder(context)
                    .setRenderersFactory(
                        DefaultRenderersFactory(context)
                            .setEnableDecoderFallback(true),
                    )
                    .build()
            } catch (e: Throwable) {
                onLog("ExoPlayer 创建失败: ${e.message}")
                _state.value = _state.value.copy(error = e.message ?: "播放器创建失败", buffering = false)
                return@post
            }
            player = p
            try {
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
                        onLog("本地播放失败 ${error.errorCodeName}: $friendly")
                        _state.value = _state.value.copy(
                            error = friendly,
                            playing = false,
                            buffering = false,
                        )
                        stopTicker()
                    }
                })
                p.setMediaItem(MediaItem.fromUri(uri))
                p.prepare()
                p.playWhenReady = true
            } catch (e: Throwable) {
                onLog("本地播放加载失败: ${e.message}")
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
        runCatching { player?.setVideoSurface(null) }
        runCatching { player?.release() }
        player = null
    }

    private fun startTicker() {
        stopTicker()
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
        const val TICK_MS = 500L
    }
}
