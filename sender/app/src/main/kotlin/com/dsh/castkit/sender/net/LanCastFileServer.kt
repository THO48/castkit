package com.dsh.castkit.sender.net

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单文件 HTTP 服务：把选中的视频通过局域网暴露给接收端，接收端用系统原生播放器直接播。
 *
 * 为什么要自己写而不是转码推流：接收端能拿到**原始码流**——原始画质、有声音、能拖进度、
 * 硬解，发送端也完全不碰 MediaProjection（不需要录屏授权）。代价只是发送端要一直开着服务。
 *
 * 实现要点：
 *  - 走 ContentResolver 读取（支持 SAF 选出来的 `content://`，不要求是真实文件路径）；
 *  - 完整支持 `Range` 请求（206 + Content-Range），否则接收端拖进度条会失败；
 *  - 每个连接一个线程，响应完即关（MediaPlayer 可能自己开多条连接）；
 *  - 只服务一个文件，任何 GET 路径都返回它。
 */
class LanCastFileServer(private val context: Context) {

    /** 要投送的文件。 */
    data class Source(
        val uri: Uri,
        val size: Long,
        val name: String,
        val mime: String,
    )

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(MAX_CONNECTIONS) { r ->
        Thread(r, "lancast-file-http").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    @Volatile
    private var source: Source? = null

    var port: Int = 0
        private set

    /** 启动服务并返回可访问的完整 URL；失败抛异常。 */
    @Synchronized
    fun start(src: Source, preferredPort: Int = DEFAULT_PORT): String {
        stop()
        source = src
        val ss = ServerSocket()
        ss.reuseAddress = true
        val bound = try {
            ss.bind(InetSocketAddress(preferredPort))
            preferredPort
        } catch (e: Exception) {
            // 首选端口被占：退回系统随机端口
            Log.w(TAG, "端口 $preferredPort 不可用（${e.message}），改用随机端口")
            ss.bind(InetSocketAddress(0))
            ss.localPort
        }
        serverSocket = ss
        port = bound
        running.set(true)
        acceptThread = Thread({ acceptLoop(ss) }, "lancast-file-accept").apply {
            isDaemon = true
            start()
        }
        val ip = localIpv4() ?: run {
            stop()
            throw IOException("找不到局域网 IPv4 地址（请确认已连 Wi-Fi）")
        }
        return url(ip, bound, src.name)
    }

    @Synchronized
    fun stop() {
        running.set(false)
        source = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        port = 0
    }

    fun isRunning(): Boolean = running.get() && serverSocket != null

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept 结束: ${e.message}")
                break
            }
            try {
                socket.tcpNoDelay = true
                pool.execute { handle(socket) }
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = READ_TIMEOUT_MS
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0)?.uppercase() ?: return
                if (method != "GET" && method != "HEAD") {
                    writeSimple(s, "405 Method Not Allowed")
                    return
                }
                serve(s, method, parseRange(headers["range"]))
            } catch (e: Throwable) {
                Log.d(TAG, "HTTP 连接结束: ${e.message}")
            }
        }
    }

    /** `bytes=start-end` / `bytes=start-` / `bytes=-suffix`；非法一律当整文件。 */
    private fun parseRange(header: String?): LongRange? {
        val raw = header?.trim() ?: return null
        if (!raw.startsWith("bytes=", ignoreCase = true)) return null
        val spec = raw.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return try {
            when {
                startText.isEmpty() && endText.isNotEmpty() -> {
                    // 末尾 N 字节：用负数起始表示
                    val n = endText.toLong()
                    -n..-1L
                }
                startText.isNotEmpty() && endText.isEmpty() -> startText.toLong()..Long.MAX_VALUE
                startText.isNotEmpty() && endText.isNotEmpty() ->
                    startText.toLong()..endText.toLong()
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun serve(socket: Socket, method: String, range: LongRange?) {
        val src = source ?: run {
            writeSimple(socket, "404 Not Found")
            return
        }
        val total = src.size
        val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)

        if (total <= 0) {
            // 拿不到大小（极少数 provider）：退化成一次性整文件响应
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: ${src.mime}\r\n" +
                    "Accept-Ranges: none\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.ISO_8859_1),
            )
            out.flush()
            if (method == "GET") streamWhole(src, out)
            return
        }

        val start: Long
        val end: Long
        if (range != null && range.first < 0) {
            // 末尾 N 字节
            val n = (-range.first).coerceAtMost(total)
            start = total - n
            end = total - 1
        } else if (range != null) {
            start = range.first.coerceIn(0, total - 1)
            end = (if (range.last == Long.MAX_VALUE) total - 1 else range.last).coerceIn(start, total - 1)
        } else {
            start = 0
            end = total - 1
        }
        val length = end - start + 1
        val header = buildString {
            append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${src.mime}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$total\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (method == "HEAD") return

        val input = try {
            context.contentResolver.openInputStream(src.uri)
        } catch (e: Throwable) {
            Log.w(TAG, "打开视频失败: ${e.message}")
            null
        } ?: return
        input.use { stream ->
            if (start > 0) skipFully(stream, start)
            val buf = ByteArray(1 shl 16)
            var remaining = length
            while (remaining > 0 && running.get()) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = stream.read(buf, 0, want)
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
            out.flush()
        }
    }

    private fun streamWhole(src: Source, out: BufferedOutputStream) {
        val input = try {
            context.contentResolver.openInputStream(src.uri)
        } catch (e: Throwable) {
            Log.w(TAG, "打开视频失败: ${e.message}")
            return
        } ?: return
        input.use { stream ->
            val buf = ByteArray(1 shl 16)
            while (running.get()) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.flush()
        }
    }

    private fun writeSimple(socket: Socket, status: String) {
        try {
            val body = status.toByteArray(Charsets.ISO_8859_1)
            socket.getOutputStream().write(
                ("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            socket.getOutputStream().write(body)
            socket.getOutputStream().flush()
        } catch (_: Exception) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > MAX_HEADER_LINE) return null
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (input.read() < 0) return
                left--
            }
        }
    }

    companion object {
        private const val TAG = "LanCastFileServer"
        const val DEFAULT_PORT = 8130
        private const val MAX_CONNECTIONS = 4
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_HEADER_LINE = 8192

        /** 视频在局域网里的访问地址。 */
        fun url(ip: String, port: Int, name: String): String =
            "http://$ip:$port/v/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        /** 取本机局域网 IPv4（优先 wlan 网卡）。 */
        fun localIpv4(): String? {
            var fallback: String? = null
            try {
                for (nif in NetworkInterface.getNetworkInterfaces()) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val addr = ia.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (nif.name.startsWith("wlan")) return ip
                            if (fallback == null) fallback = ip
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "枚举网卡失败: ${e.message}")
            }
            return fallback
        }

        /** 读取文件名/大小/MIME（支持 SAF 的 content:// 与 .nomedia 扫描出来的 file://）。 */
        fun describe(context: Context, uri: Uri): Source {
            // file:// 走 File API：ContentResolver 对它没有 OpenableColumns（否则大小会是 -1，接收端就不能拖进度了）
            if (uri.scheme == "file") {
                val f = uri.path?.let { java.io.File(it) }
                if (f != null && f.exists()) {
                    return Source(uri, f.length(), f.name, guessMime(f.name))
                }
            }
            var name = uri.lastPathSegment?.substringAfterLast('/') ?: "video"
            var size = -1L
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取文件信息失败: ${e.message}")
            }
            val mime = context.contentResolver.getType(uri) ?: guessMime(name)
            return Source(uri, size, name, mime)
        }

        private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "flv" -> "video/x-flv"
            "3gp" -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }
}
