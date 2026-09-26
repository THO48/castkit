package io.github.jqssun.airplay.net

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/** 投视频文件时回报给发送端的播放状态快照。 */
data class LanVideoStatus(
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val buffering: Boolean,
)

/** 局域网投屏（LANCast v1）接收状态，供 UI 展示。 */
data class LanCastStatus(
    val enabled: Boolean = false,
    val listening: Boolean = false,
    val port: Int = LanCast.DEFAULT_PORT,
    val client: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val requestedBitrateBps: Int = 0,
    val measuredKbps: Int = 0,
    val frames: Long = 0,
    val decoder: String = "",
    val error: String? = null,
) {
    /** 流比例（0 = 未知）。UI 用它给视频 Surface 定比例，避免拉伸。 */
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 0f
}

/**
 * LANCast v1 服务端：监听 TCP 端口，接收 CastKit 发送端的 H.264 访问单元，
 * 用 MediaCodec 解码后直接渲染到给定的 Surface（与 AirPlay 镜像共用同一个 SurfaceView）。
 *
 * 线程模型：accept 线程 + 每连接一个会话线程；解码在同一会话线程里同步进行。
 */
class LanCastReceiver(private val onLog: (String) -> Unit = {}) {

    private val _status = MutableStateFlow(LanCastStatus())
    val status: StateFlow<LanCastStatus> = _status.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var statsThread: Thread? = null
    @Volatile
    private var session: Session? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var running = false

    /** 由 AirPlay 服务提供：AirPlay 会话占用画面时拒绝局域网投屏。 */
    @Volatile
    var airPlayBusyProvider: (() -> Boolean)? = null

    /** 局域网会话建立（已回 READY）时回调，供 Service 把界面拉到前台以创建 Surface。 */
    @Volatile
    var onSessionStarted: (() -> Unit)? = null

    /** 发送端要求播放某个局域网视频地址（投视频文件模式）。 */
    @Volatile
    var onPlayUrl: ((String) -> Unit)? = null

    /** 发送端要求结束投视频（用户点了停止）。 */
    @Volatile
    var onStopVideo: (() -> Unit)? = null

    /** 发送端发来投屏控制（action, value）：投视频文件时发送端当遥控器用。 */
    @Volatile
    var onControlAction: ((action: Int, value: Long) -> Unit)? = null

    /**
     * 由 Service 提供当前投视频的播放状态（null = 当前没有在投视频）。
     * 回报动作由**会话自己的线程**驱动，不依赖 coroutine/lifecycle，
     * 也不依赖跨线程可见性——之前用服务里的协程每秒推一次，实测一条都没发出去。
     */
    @Volatile
    var videoStatusProvider: (() -> LanVideoStatus?)? = null

    val hasSession: Boolean get() = session != null

    @Synchronized
    fun start(port: Int = LanCast.DEFAULT_PORT) {
        if (running) return
        running = true
        _status.value = LanCastStatus(enabled = true, port = port)
        val t = Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                _status.value = _status.value.copy(listening = true)
                log("局域网投屏监听 0.0.0.0:$port")
                while (running) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (running) log("accept 结束: ${e.message}")
                        break
                    }
                    socket.tcpNoDelay = true
                    if (session != null || (airPlayBusyProvider?.invoke() == true)) {
                        rejectBusy(socket)
                        continue
                    }
                    val s = Session(socket, port)
                    session = s
                    s.start()
                }
            } catch (e: Exception) {
                log("监听失败: ${e.message}")
                _status.value = _status.value.copy(listening = false, error = e.message)
            }
        }, "lancast-accept").apply { isDaemon = true; start() }
        acceptThread = t
        startStatsLoop()
    }

    @Synchronized
    fun stop() {
        running = false
        session?.close("服务停止")
        session = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        statsThread = null
        _status.value = LanCastStatus(enabled = false)
    }

    /**
     * 由 UI 提供/回收渲染 Surface（与 AirPlay 镜像共用 VideoSurfaceView）。
     *
     * 同一个 Surface 对象会被 UI 反复上报（布局/比例变化、重组都会触发 SurfaceHolder 回调），
     * 只有**真的换了 Surface 对象**才需要重建解码器。此前不判重，实测屏幕旋转时 100ms 内
     * 连续建了 4 个解码器，最终把编解码器资源耗尽，出现"解码器创建失败"。
     */
    fun setSurface(s: Surface?) {
        if (surface === s) return
        surface = s
        session?.onSurfaceChanged(s)
    }

    fun stopSession(reason: String = "已被 AirPlay 会话占用") {
        session?.close(reason)
        session = null
    }

    private fun startStatsLoop() {
        statsThread = Thread({
            var lastFrames = 0L
            while (running) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                val s = session ?: continue
                val (kbps, frames) = s.sampleStats()
                lastFrames = frames
                _status.value = _status.value.copy(measuredKbps = kbps, frames = lastFrames)
            }
            _status.value = _status.value.copy(measuredKbps = 0)
        }, "lancast-stats").apply { isDaemon = true; start() }
    }

    private fun rejectBusy(socket: Socket) {
        try {
            socket.getOutputStream().write(
                byteArrayOf(LanCast.CTRL_BYE.toByte(), LanCast.ERR_BUSY.toByte()),
            )
            socket.getOutputStream().flush()
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
        log("拒绝连接（已有投屏会话）")
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    /** 单个发送端会话。 */
    private inner class Session(private val socket: Socket, private val port: Int) {
        private var codec: MediaCodec? = null
        private var codecW = 0
        private var codecH = 0
        private var firstPtsUs = -1L
        private var csd: ByteArray? = null

        /** 当前解码器是用哪份 CSD 建起来的：尺寸相同但 CSD 换了也必须重建。 */
        private var appliedCsd: ByteArray? = null
        private val bufferInfo = MediaCodec.BufferInfo()
        private var bytesInWindow = 0L
        private var framesInWindow = 0L
        private var lastSampleAt = System.currentTimeMillis()
        private var closed = false
        private var hasKeyFrame = false
        private val outputLock = Object()
        private var out: OutputStream? = null
        private var statusThread: Thread? = null

        fun start() {
            Thread({ run() }, "lancast-session").apply { isDaemon = true; start() }
        }

        private fun run() {
            val input = BufferedInputStream(socket.getInputStream(), 1 shl 16)
            out = socket.getOutputStream()
            try {
                socket.soTimeout = LanCast.HANDSHAKE_TIMEOUT_MS
                val head = ByteArray(LanCast.HANDSHAKE_SIZE)
                readFully(input, head, LanCast.HANDSHAKE_SIZE)
                socket.soTimeout = LanCast.IDLE_TIMEOUT_MS
                val hs = LanCast.parseHandshake(head, LanCast.HANDSHAKE_SIZE) ?: run {
                    sendCtrl(LanCast.CTRL_BYE, LanCast.ERR_PROTOCOL)
                    throw IllegalStateException("握手协议不匹配")
                }
                csd = hs.csd
                log("发送端接入 ${socket.inetAddress?.hostAddress} ${hs.width}x${hs.height}@${hs.fps} ${hs.bitrateBps / 1_000_000}Mbps")
                _status.value = _status.value.copy(
                    client = socket.inetAddress?.hostAddress,
                    port = port,
                    width = hs.width,
                    height = hs.height,
                    fps = hs.fps,
                    requestedBitrateBps = hs.bitrateBps,
                    error = null,
                )
                sendCtrl(LanCast.CTRL_READY, 0)
                csd?.let { feedCsd(it) }
                runCatching { onSessionStarted?.invoke() }
                startWatchdog()

                val header = ByteArray(LanCast.FRAME_HEADER_SIZE)
                val payload = ByteArray(1 shl 16)
                while (!closed) {
                    readFully(input, header, LanCast.FRAME_HEADER_SIZE)
                    val fh = LanCast.parseFrameHeader(header)
                    if (fh.len < 0 || fh.len > LanCast.MAX_PAYLOAD) {
                        throw IllegalStateException("帧长度非法: ${fh.len}")
                    }
                    var data = payload
                    if (fh.len > data.size) data = ByteArray(fh.len)
                    if (fh.len > 0) readFully(input, data, fh.len)
                    bytesInWindow += fh.len
                    when (fh.type) {
                        // 发送端中途改了分辨率（旋转等）：更新尺寸并释放解码器，等随后的 CSD 重建
                        LanCast.TYPE_RESIZE -> onResize(data, fh.len)
                        LanCast.TYPE_PLAY_URL -> handlePlayUrl(data, fh.len)
                        LanCast.TYPE_CTRL -> handleControl(data, fh.len)
                        LanCast.TYPE_STOP -> runCatching { onStopVideo?.invoke() }
                        LanCast.TYPE_VIDEO_CSD -> feedCsd(data.copyOf(fh.len))
                        LanCast.TYPE_VIDEO_AU -> feedFrame(data, fh.len, fh.ptsUs, fh.flags and LanCast.FLAG_KEYFRAME != 0)
                        LanCast.TYPE_PING -> Unit
                        else -> Unit
                    }
                    drain()
                }
            } catch (e: SocketTimeoutException) {
                log("连接空闲超时，断开")
            } catch (e: Throwable) {
                if (!closed) log("会话结束: ${e.message}")
            } finally {
                closeInternal()
            }
        }

        private fun readFully(input: BufferedInputStream, buf: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val n = input.read(buf, off, len - off)
                if (n < 0) throw IllegalStateException("连接被关闭")
                off += n
            }
        }

        private fun sendCtrl(type: Int, code: Int) {
            synchronized(outputLock) {
                val stream = out ?: return
                try {
                    stream.write(type)
                    if (type == LanCast.CTRL_BYE) stream.write(code)
                    stream.flush()
                } catch (_: Exception) {
                }
            }
        }

        private fun requestKeyFrame() {
            sendCtrl(LanCast.CTRL_REQUEST_KEYFRAME, 0)
        }

        fun onSurfaceChanged(s: Surface?) {
            // Surface 变化（进入/退出全屏、分屏等）：重建解码器，并请发送端补一个 I 帧
            synchronized(outputLock) {
                releaseCodec()
                if (s == null) return
                // 关键：CSD 可能早于 Surface 到达（先开发送端、后开接收端），
                // 这里用已缓存的 CSD 补建解码器，否则会一直黑屏
                csd?.let { feedCsdLocked(it) }
            }
            if (s != null) requestKeyFrame()
        }

        /** 看门狗：Surface/CSD 就绪但解码器还没建起来时（竞态）每 2 秒补一次。 */
        private fun startWatchdog() {
            Thread({
                while (!closed) {
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (closed) break
                    if (surface == null) continue
                    synchronized(outputLock) {
                        if (codec == null) csd?.let { feedCsdLocked(it) }
                    }
                }
            }, "lancast-watchdog").apply { isDaemon = true; start() }
        }

        /** 收到 TYPE_CTRL：把遥控指令转给 Service（播放/暂停/切换/seek）。 */
        private fun handleControl(data: ByteArray, len: Int) {
            if (len < 5) return
            val action = data[0].toInt() and 0xFF
            val value = LanCast.u32(data, 1)
            runCatching { this@LanCastReceiver.onControlAction?.invoke(action, value) }
        }

        /** 收到投视频请求后开始每秒回报播放状态（在本会话线程里跑，不依赖 Service）。 */
        private fun startStatusTicker() {
            if (statusThread != null) return
            statusThread = Thread({
                var count = 0L
                while (!closed) {
                    try {
                        Thread.sleep(LanCast.STATUS_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (closed) break
                    val snap = this@LanCastReceiver.videoStatusProvider?.invoke() ?: continue
                    val payload = ByteArray(10)
                    payload[0] = LanCast.EXT_STATUS.toByte()
                    LanCast.putU32(payload, 1, snap.positionMs)
                    LanCast.putU32(payload, 5, snap.durationMs)
                    payload[9] = ((if (snap.playing) 1 else 0) or (if (snap.buffering) 2 else 0)).toByte()
                    sendExtended(payload)
                    count++
                    if (count == 1L || count % 15L == 0L) {
                        log("回报状态 pos=${snap.positionMs}ms dur=${snap.durationMs}ms playing=${snap.playing}")
                    }
                }
            }, "lancast-status").apply { isDaemon = true; start() }
        }

        /** 反向扩展消息：`0x7F | u16 len | payload`（与输出流上的控制字节互斥写入）。 */
        fun sendExtended(payload: ByteArray) {
            if (payload.isEmpty() || payload.size > LanCast.MAX_EXT_PAYLOAD) return
            synchronized(outputLock) {
                val stream = out ?: return
                try {
                    stream.write(LanCast.EXT_BYTE)
                    stream.write(payload.size and 0xFF)
                    stream.write((payload.size ushr 8) and 0xFF)
                    stream.write(payload)
                    stream.flush()
                } catch (_: Exception) {
                }
            }
        }

        /** 收到 TYPE_PLAY_URL：把地址交给 Service 用系统原生播放器播放。
         * `data` 是会话内复用的缓冲，必须先拷成字符串再交给别的线程。
         */
        private fun handlePlayUrl(data: ByteArray, len: Int) {
            if (len <= 0) return
            val url = String(data, 0, len, Charsets.UTF_8)
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                log("忽略非 http 播放地址: $url")
                return
            }
            log("收到投视频请求: $url")
            _status.value = _status.value.copy(error = null)
            runCatching { this@LanCastReceiver.onPlayUrl?.invoke(url) }
            startStatusTicker()
        }

        /** 收到 TYPE_RESIZE：更新尺寸 -> 释放解码器；随后的 CSD 帧会按新尺寸重建。 */
        private fun onResize(data: ByteArray, len: Int) {            if (len < 4) return
            val w = LanCast.u16(data, 0)
            val h = LanCast.u16(data, 2)
            if (w <= 0 || h <= 0) return
            val oldW = _status.value.width
            val oldH = _status.value.height
            log("发送端改分辨率: ${oldW}x${oldH} -> ${w}x${h}")
            synchronized(outputLock) {
                _status.value = _status.value.copy(width = w, height = h, error = null)
                releaseCodecLocked()
                hasKeyFrame = false
                firstPtsUs = -1L
            }
        }

        private fun feedCsd(bytes: ByteArray) = synchronized(outputLock) { feedCsdLocked(bytes) }

        private fun feedCsdLocked(bytes: ByteArray) {
            csd = bytes
            val (sps, pps) = LanCast.unpackCsd(bytes) ?: run {
                log("CSD 解析失败")
                return
            }
            val cur = surface
            val fmtW = _status.value.width
            val fmtH = _status.value.height
            if (cur == null) {
                log("等待渲染 Surface（收到 CSD ${fmtW}x$fmtH）")
                return
            }
            if (codec != null && codecW == fmtW && codecH == fmtH &&
                appliedCsd?.contentEquals(bytes) == true
            ) {
                return
            }
            releaseCodec()
            try {
                val format = MediaFormat.createVideoFormat(MIME, fmtW.coerceAtLeast(16), fmtH.coerceAtLeast(16))
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                val c = MediaCodec.createDecoderByType(MIME)
                c.configure(format, cur, null, 0)
                c.start()
                codec = c
                codecW = fmtW
                codecH = fmtH
                appliedCsd = bytes.copyOf()
                hasKeyFrame = false
                firstPtsUs = -1L
                _status.value = _status.value.copy(decoder = c.name)
                log("解码器就绪 ${c.name} ${fmtW}x$fmtH")
                requestKeyFrame()
            } catch (e: Throwable) {
                // CodecException 的 message 常常是 null，真正的信息在 diagnosticInfo 里
                val extra = (e as? MediaCodec.CodecException)
                    ?.let { " diagnose=${it.diagnosticInfo} recoverable=${it.isRecoverable} transient=${it.isTransient}" }
                    ?: ""
                log("解码器创建失败: ${e.javaClass.simpleName} ${e.message}$extra (${fmtW}x$fmtH)")
                _status.value = _status.value.copy(error = e.message ?: e.javaClass.simpleName)
                releaseCodec()
            }
        }

        private fun feedFrame(data: ByteArray, len: Int, ptsUs: Long, keyframe: Boolean) =
            synchronized(outputLock) { feedFrameLocked(data, len, ptsUs, keyframe) }

        private fun feedFrameLocked(data: ByteArray, len: Int, ptsUs: Long, keyframe: Boolean) {
            val c = codec ?: run {
                // 还没准备好解码器（例如 Surface 未就绪）：丢弃，等 CSD/关键帧
                if (keyframe) requestKeyFrame()
                return
            }
            if (!keyframe && !hasKeyFrame) return
            if (firstPtsUs < 0) firstPtsUs = ptsUs
            val relPts = ptsUs - firstPtsUs
            try {
                val index = c.dequeueInputBuffer(10_000)
                if (index < 0) return
                val buffer = c.getInputBuffer(index) ?: return
                buffer.clear()
                buffer.put(data, 0, len)
                var flags = 0
                if (keyframe) {
                    flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    hasKeyFrame = true
                }
                c.queueInputBuffer(index, 0, len, relPts, flags)
                framesInWindow++
                lastSampleAt = System.currentTimeMillis()
            } catch (e: Throwable) {
                log("送帧失败: ${e.message}")
            }
        }

        private fun drain() = synchronized(outputLock) { drainLocked() }

        private fun drainLocked() {
            val c = codec ?: return
            while (true) {
                val index = try {
                    c.dequeueOutputBuffer(bufferInfo, 0)
                } catch (_: Throwable) {
                    return
                }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    index >= 0 -> {
                        try {
                            c.releaseOutputBuffer(index, true)
                        } catch (_: Throwable) {
                        }
                    }
                    else -> return
                }
            }
        }

        private fun releaseCodec() = synchronized(outputLock) { releaseCodecLocked() }

        private fun releaseCodecLocked() {
            val c = codec ?: return
            codec = null
            codecW = 0
            codecH = 0
            appliedCsd = null
            try {
                c.stop()
            } catch (_: Throwable) {
            }
            try {
                c.release()
            } catch (_: Throwable) {
            }
            _status.value = _status.value.copy(decoder = "")
        }

        /** 返回 (近 1 秒 kbps, 累计帧数)。 */
        fun sampleStats(): Pair<Int, Long> {
            val now = System.currentTimeMillis()
            val dt = (now - lastSampleAt).coerceAtLeast(1)
            val kbps = (bytesInWindow * 8 / dt).toInt()
            bytesInWindow = 0
            lastSampleAt = now
            val total = framesInWindow
            framesInWindow = 0
            return kbps to total
        }

        fun close(reason: String = "关闭") {
            sendCtrl(LanCast.CTRL_BYE, 0)
            closeInternal()
            Log.i(TAG, "关闭会话: $reason")
        }

        private fun closeInternal() {
            if (closed) return
            closed = true
            releaseCodec()
            try {
                socket.close()
            } catch (_: Exception) {
            }
            if (session === this) session = null
            _status.value = _status.value.copy(
                client = null,
                measuredKbps = 0,
                decoder = "",
                width = 0,
                height = 0,
                fps = 0,
            )
        }
    }

    private companion object {
        const val TAG = "LanCastReceiver"
        const val MIME = "video/avc"
    }
}
