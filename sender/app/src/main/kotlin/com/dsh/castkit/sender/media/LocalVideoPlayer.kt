package com.dsh.castkit.sender.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
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
    val error: String? = null,
) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
}

/**
 * 应用内本地视频播放器（选视频即播放，不再只是"选中"）。
 *
 * 用系统 [MediaPlayer] 播本机 `content://` 视频：硬解、有声音、可拖进度，零第三方依赖。
 * MediaPlayer 必须在带 Looper 的线程上使用，这里统一 post 到主线程。
 */
class LocalVideoPlayer(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(LocalPlaybackState())
    val state: StateFlow<LocalPlaybackState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    @Volatile
    private var surface: Surface? = null

    private var ticker: Runnable? = null

    fun setSurface(s: Surface?) {
        surface = s
        main.post { runCatching { player?.setSurface(s) } }
    }

    fun play(uri: Uri, title: String) {
        main.post {
            releaseInternal()
            _state.value = LocalPlaybackState(uri = uri, title = title, buffering = true)
            onLog("本地播放: $uri")
            // 「安卓根本没有这个格式的解码器」（WMV/RMVB 等）要在建 MediaPlayer 之前就说清楚，
            // 否则用户只会看到一个 error (1, -2147483648)。预检要读文件头，放后台线程做。
            Thread {
                val verdict = PlayabilityChecker.check(context, uri)
                main.post { if (_state.value.uri == uri) startPlayback(uri, verdict) }
            }.apply { isDaemon = true; name = "castkit-precheck"; start() }
        }
    }

    /** 预检通过（或只是没判准）之后真正起播。 */
    private fun startPlayback(uri: Uri, verdict: Playability) {
        PlayabilityChecker.logMessage(verdict)?.let { onLog(it) }
        PlayabilityChecker.blockReason(verdict)?.let { msg ->
            onLog("预检未通过: $msg")
            _state.value = _state.value.copy(error = msg, buffering = false)
            return
        }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            mp.setSurface(surface)
            mp.setDataSource(context, uri)
            mp.setOnPreparedListener { p ->
                _state.value = _state.value.copy(
                    buffering = false,
                    playing = true,
                    durationMs = p.duration.toLong().coerceAtLeast(0),
                )
                runCatching { p.start() }
                startTicker()
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                _state.value = _state.value.copy(width = w, height = h)
            }
            mp.setOnInfoListener { _, what, _ ->
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START ->
                        _state.value = _state.value.copy(buffering = true)
                    MediaPlayer.MEDIA_INFO_BUFFERING_END,
                    MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START ->
                        _state.value = _state.value.copy(buffering = false)
                }
                false
            }
            mp.setOnCompletionListener {
                _state.value = _state.value.copy(playing = false, positionMs = _state.value.durationMs)
                stopTicker()
            }
            mp.setOnErrorListener { _, what, extra ->
                val msg = "播放失败 what=$what extra=$extra"
                onLog(msg)
                _state.value = _state.value.copy(error = msg, playing = false, buffering = false)
                true
            }
            mp.prepareAsync()
        } catch (e: Throwable) {
            onLog("本地播放加载失败: ${e.message}")
            _state.value = _state.value.copy(error = e.message ?: "加载失败", buffering = false)
            releaseInternal()
        }
    }

    fun toggle() {
        main.post {
            val p = player ?: return@post
            val playing = runCatching { p.isPlaying }.getOrDefault(false)
            if (playing) {
                runCatching { p.pause() }
                _state.value = _state.value.copy(playing = false)
                stopTicker()
            } else {
                runCatching { p.start() }
                _state.value = _state.value.copy(playing = true)
                startTicker()
            }
        }
    }

    /** 暂停（已经在暂停/未起播则什么都不做）。 */
    fun pause() {
        main.post {
            val p = player ?: return@post
            if (runCatching { p.isPlaying }.getOrDefault(false)) {
                runCatching { p.pause() }
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
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    p.seekTo(target, MediaPlayer.SEEK_CLOSEST)
                } else {
                    @Suppress("DEPRECATION")
                    p.seekTo(target.toInt())
                }
            }
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
        runCatching { player?.setSurface(null) }
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
    }

    private fun startTicker() {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                val p = player ?: return
                runCatching {
                    _state.value = _state.value.copy(
                        positionMs = p.currentPosition.toLong(),
                        durationMs = if (p.duration > 0) p.duration.toLong() else _state.value.durationMs,
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

    private companion object {
        const val TICK_MS = 500L
    }
}
