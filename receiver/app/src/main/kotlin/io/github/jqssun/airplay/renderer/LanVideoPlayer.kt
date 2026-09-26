package io.github.jqssun.airplay.renderer

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
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f

    val active: Boolean get() = url != null
}

/**
 * 局域网视频播放器（发送端「投视频文件」用）。
 *
 * 与 AirPlay 播放、屏幕镜像都不同：这里播的是发送端通过局域网 HTTP 暴露的**原始文件**，
 * 直接用系统 [MediaPlayer] 播放——硬解、原画质、自带声音与进度控制，不需要我们解码任何东西。
 *
 * 线程约定：MediaPlayer 必须在带 Looper 的线程上使用，这里统一 post 到主线程。
 */
class LanVideoPlayer(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(LanVideoState())
    val state: StateFlow<LanVideoState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    @Volatile
    private var surface: Surface? = null

    private var ticker: Runnable? = null

    @Volatile
    private var prepared = false

    /** 播放器还没就绪时先记住要跳的位置，prepared 后立刻应用（开投时发送端会带上本机进度）。 */
    private var pendingSeekMs = -1L

    /** UI 提供/回收渲染 Surface（可能在 play 之前或之后到达）。 */
    fun setSurface(s: Surface?) {
        surface = s
        main.post {
            runCatching { player?.setSurface(s) }
        }
    }

    fun play(url: String, title: String) {
        main.post {
            releaseInternal()
            pendingSeekMs = -1L
            _state.value = LanVideoState(url = url, title = title, buffering = true)
            onLog("局域网视频开始加载: $url")
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
                // http(s) 一律用字符串重载：ContentResolver 那条路对 http 不做保证
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    mp.setDataSource(url)
                } else {
                    mp.setDataSource(context, Uri.parse(url))
                }
                mp.setOnPreparedListener { p ->
                    prepared = true
                    _state.value = _state.value.copy(
                        buffering = false,
                        playing = true,
                        durationMs = p.duration.toLong().coerceAtLeast(0),
                    )
                    onLog("局域网视频就绪（时长 ${p.duration}ms），开始播放")
                    // 发送端开投时可能已经带了本机进度：就绪后立刻跳过去
                    val pending = pendingSeekMs
                    pendingSeekMs = -1L
                    if (pending > 0) {
                        runCatching { seekCompat(p, pending) }
                        _state.value = _state.value.copy(positionMs = pending)
                    }
                    runCatching { p.start() }
                    startTicker()
                }
                mp.setOnVideoSizeChangedListener { _, w, h ->
                    _state.value = _state.value.copy(width = w, height = h)
                }
                mp.setOnBufferingUpdateListener { _, percent ->
                    // 只有还没开始播的阶段才当"缓冲中"展示
                    if (!_state.value.playing) {
                        _state.value = _state.value.copy(buffering = true)
                    }
                }
                mp.setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START ->
                            _state.value = _state.value.copy(buffering = true)
                        MediaPlayer.MEDIA_INFO_BUFFERING_END ->
                            _state.value = _state.value.copy(buffering = false)
                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START ->
                            _state.value = _state.value.copy(buffering = false)
                    }
                    false
                }
                mp.setOnCompletionListener {
                    onLog("局域网视频播放结束")
                    stopInternal()
                }
                mp.setOnErrorListener { _, what, extra ->
                    // 原始错误码只进日志：对用户没意义，这里给友好提示并自动收起播放页
                    onLog("局域网视频播放失败 what=$what extra=$extra，结束本次投送")
                    _state.value = _state.value.copy(
                        error = null,
                        playing = false,
                        buffering = false,
                        ended = "投送已结束",
                    )
                    main.postDelayed({ stopInternal() }, ERROR_EXIT_DELAY_MS)
                    true
                }
                mp.prepareAsync()
            } catch (e: Throwable) {
                onLog("局域网视频加载失败: ${e.message}")
                _state.value = _state.value.copy(error = e.message ?: "加载失败", buffering = false)
                releaseInternal()
            }
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

    fun play() {
        main.post {
            val p = player ?: return@post
            runCatching { p.start() }
            _state.value = _state.value.copy(playing = true)
            startTicker()
        }
    }

    fun pause() {
        main.post {
            val p = player ?: return@post
            runCatching { p.pause() }
            _state.value = _state.value.copy(playing = false)
            stopTicker()
        }
    }

    fun seekTo(positionMs: Long) {
        main.post {
            val p = player
            val duration = _state.value.durationMs
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            // 还没就绪（正在 prepareAsync）：先记住，等 onPrepared 再跳
            if (p == null || !prepared) {
                pendingSeekMs = target
                _state.value = _state.value.copy(positionMs = target)
                return@post
            }
            runCatching { seekCompat(p, target) }
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    private fun seekCompat(p: MediaPlayer, targetMs: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            p.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST)
        } else {
            @Suppress("DEPRECATION")
            p.seekTo(targetMs.toInt())
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
        prepared = false
        runCatching { player?.setSurface(null) }
        runCatching { player?.reset() }
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
                            positionMs = p.currentPosition.toLong(),
                            durationMs = p.duration.toLong().coerceAtLeast(0),
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

    private companion object {
        const val TICK_MS = 500L
        /** 播放失败后停留多久再自动收起播放页。 */
        const val ERROR_EXIT_DELAY_MS = 1800L
    }
}
