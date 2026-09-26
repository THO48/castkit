package com.dsh.castkit.sender.net

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * LANCast v1 客户端：一条 TCP 连接，写帧 + 异步读控制字节。
 *
 * 线程模型：`sendXxx` 由编码线程调用（内部加锁保证整帧原子写入）；控制字节由内部读线程解析后回调。
 */
class LanCastClient(
    private val host: String,
    private val port: Int,
) {
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()
    private var reader: Thread? = null
    private val header = ByteArray(LanCast.FRAME_HEADER_SIZE)

    @Volatile
    var onControl: ((Int) -> Unit)? = null

    /** 反向扩展消息（`0x7F | u16 len | payload`）：目前用于接收端回报播放状态。 */
    @Volatile
    var onExtended: ((type: Int, payload: ByteArray) -> Unit)? = null

    @Volatile
    var onClosed: ((String) -> Unit)? = null

    @Volatile
    private var closed = false

    /** 已完成握手并处于可用状态。 */
    @Volatile
    private var ready = false

    fun isConnected(): Boolean = ready && !closed

    fun connect(handshake: ByteArray, connectTimeoutMs: Int = 8000, readTimeoutMs: Int = LanCast.HANDSHAKE_TIMEOUT_MS): Boolean {
        close()
        closed = false
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        socket = s
        out = BufferedOutputStream(s.getOutputStream(), 1 shl 16)
        synchronized(writeLock) {
            out!!.write(handshake)
            out!!.flush()
        }
        reader = Thread({ readLoop(s) }, "lancast-reader").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun readLoop(s: Socket) {
        var reason = "接收端断开连接"
        try {
            val input: InputStream = s.getInputStream()
            // 第一条消息必须是 ready
            val first = input.read()
            if (first < 0) throw IOException("EOF")
            when (first) {
                LanCast.CTRL_READY -> {
                    ready = true
                    onControl?.invoke(LanCast.CTRL_READY)
                }
                LanCast.CTRL_BYE -> {
                    val code = input.read()
                    reason = "接收端拒绝连接（错误码 $code）"
                    throw IOException(reason)
                }
                else -> {
                    reason = "协议错误：期望 ready，收到 $first"
                    throw IOException(reason)
                }
            }
            s.soTimeout = 0
            val extLen = ByteArray(2)
            while (!closed) {
                val b = input.read()
                if (b < 0) break
                if (b == LanCast.EXT_BYTE) {
                    // 扩展消息：0x7F | u16 len | payload（payload[0] 是类型）
                    readFully(input, extLen, 2)
                    val len = (extLen[0].toInt() and 0xFF) or ((extLen[1].toInt() and 0xFF) shl 8)
                    if (len !in 1..LanCast.MAX_EXT_PAYLOAD) {
                        reason = "扩展消息长度非法: $len"
                        break
                    }
                    val payload = ByteArray(len)
                    readFully(input, payload, len)
                    onExtended?.invoke(payload[0].toInt() and 0xFF, payload.copyOfRange(1, len))
                } else {
                    onControl?.invoke(b)
                }
            }
        } catch (e: Exception) {
            if (!closed) reason = e.message ?: reason
        } finally {
            close()
            onClosed?.invoke(reason)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw IOException("连接被关闭")
            off += n
        }
    }

    /** 整帧原子写入：12 字节头 + 负载。 */
    @Throws(IOException::class)
    fun sendFrame(type: Int, flags: Int, ptsUs: Long, data: ByteArray, offset: Int, len: Int) {
        if (len > LanCast.MAX_PAYLOAD) throw IOException("帧过大: $len")
        val stream = out ?: throw IOException("未连接")
        LanCast.writeFrameHeader(header, type, flags, ptsUs, len)
        synchronized(writeLock) {
            stream.write(header)
            stream.write(data, offset, len)
            stream.flush()
        }
    }

    @Throws(IOException::class)
    fun sendPing() = sendFrame(LanCast.TYPE_PING, 0, 0L, EMPTY, 0, 0)

    /** 通知接收端"投视频结束"（连接断开本身不携带这个语义）。 */
    fun sendStop() = runCatching { sendFrame(LanCast.TYPE_STOP, 0, 0L, EMPTY, 0, 0) }

    /** 投屏遥控：播放/暂停/切换/seek（value 单位为毫秒）。 */
    fun sendControl(action: Int, value: Long = 0L) {
        val payload = ByteArray(5)
        payload[0] = action.toByte()
        LanCast.putU32(payload, 1, value)
        runCatching { sendFrame(LanCast.TYPE_CTRL, 0, 0L, payload, 0, payload.size) }
    }

    fun close() {
        closed = true
        ready = false
        reader?.interrupt()
        reader = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        out = null
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
