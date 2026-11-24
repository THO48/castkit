package com.dsh.castkit.sender.net

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.media.VideoItem
import com.dsh.castkit.sender.media.VideoLibrary
import com.dsh.castkit.sender.media.VideoLibraryCache
import com.dsh.castkit.sender.media.VideoThumbnails
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网视频流 + 视频库 HTTP 服务：
 *  - `GET/HEAD /v/<name>`：兼容旧版单文件直播（支持 Range）
 *  - `GET /api/library`（可选 `?refresh=1`）：返回发送端本机视频库 JSON（供接收端选片）
 *  - `GET/HEAD /item/<id>/<name>`：按 ID 点播任意视频（完整 HTTP Range 206）
 *  - `GET /thumb/<id>.jpg`：按 ID 返回视频缩略图 JPEG（复用 VideoThumbnails 缓存）
 */
class LanCastFileServer(private val context: Context) {

    /** 要投送的单个视频文件。 */
    data class Source(
        val uri: Uri,
        val size: Long,
        val name: String,
        val mime: String,
    )

    /** 暴露给接收端浏览的视频库条目。 */
    data class LibraryEntry(
        val id: String,
        val uri: Uri,
        val name: String,
        val folder: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val dateAddedSec: Long,
        val width: Int,
        val height: Int,
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

    @Volatile
    var activeItemId: String = ""
        private set

    @Volatile
    var baseUrl: String = ""
        private set

    var port: Int = 0
        private set

    private val libraryMap = ConcurrentHashMap<String, LibraryEntry>()
    @Volatile
    private var libraryList: List<LibraryEntry> = emptyList()

    /** 启动服务（带初始视频）并返回该视频的可访问 URL。 */
    @Synchronized
    fun start(src: Source, preferredPort: Int = DEFAULT_PORT): String {
        val base = ensureStarted(preferredPort)
        source = src
        refreshLibraryIndex(forceReload = libraryList.isEmpty(), ensureSource = src)
        val matchedId = libraryList.firstOrNull { it.uri == src.uri }?.id ?: idForUri(src.uri)
        activeItemId = matchedId
        return itemUrl(base, matchedId, src.name)
    }

    /** 仅启动视频库共享（不立即播指定视频，由接收端自行选片）。返回 baseUrl。 */
    @Synchronized
    fun startLibraryOnly(preferredPort: Int = DEFAULT_PORT): String {
        val base = ensureStarted(preferredPort)
        source = null
        activeItemId = ""
        refreshLibraryIndex(forceReload = true, ensureSource = null)
        return base
    }

    /** 接收端切到另一个视频时同步活跃状态。 */
    fun setActiveById(id: String): LibraryEntry? {
        val entry = libraryMap[id] ?: return null
        activeItemId = entry.id
        source = Source(entry.uri, entry.sizeBytes, entry.name, entry.mime)
        return entry
    }

    fun findEntry(id: String): LibraryEntry? = libraryMap[id]

    private fun ensureStarted(preferredPort: Int): String {
        if (running.get() && serverSocket != null && baseUrl.isNotEmpty()) {
            return baseUrl
        }
        stop()
        val ss = ServerSocket()
        ss.reuseAddress = true
        val bound = try {
            ss.bind(InetSocketAddress(preferredPort))
            preferredPort
        } catch (e: Exception) {
            Log.w(TAG, "端口 $preferredPort 不可用（${e.message}），改用随机端口")
            ss.bind(InetSocketAddress(0))
            ss.localPort
        }
        serverSocket = ss
        port = bound
        val ip = localIpv4() ?: run {
            stop()
            throw IOException("找不到局域网 IPv4 地址（请确认已连 Wi-Fi）")
        }
        baseUrl = "http://$ip:$bound"
        running.set(true)
        acceptThread = Thread({ acceptLoop(ss) }, "lancast-file-accept").apply {
            isDaemon = true
            start()
        }
        return baseUrl
    }

    @Synchronized
    fun stop() {
        running.set(false)
        source = null
        activeItemId = ""
        baseUrl = ""
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptThread = null
        port = 0
    }

    fun isRunning(): Boolean = running.get() && serverSocket != null

    /** 构建/刷新可供接收端浏览的视频索引。 */
    @Synchronized
    fun refreshLibraryIndex(forceReload: Boolean = false, ensureSource: Source? = source) {
        val rawItems: List<VideoItem> = try {
            val cached = if (!forceReload) VideoLibraryCache.merged else null
            if (!cached.isNullOrEmpty()) {
                cached
            } else {
                val store = VideoLibrary.loadFromMediaStore(context)
                val extras = if (Prefs.browserIncludeNoMedia(context) && VideoLibrary.canReadAllFiles(context)) {
                    VideoLibrary.cachedNoMedia(context)
                } else {
                    emptyList()
                }
                val known = store.mapTo(HashSet()) { it.key }
                val merged = store + extras.filter { it.key !in known }
                VideoLibraryCache.merged = merged
                merged
            }
        } catch (t: Throwable) {
            Log.w(TAG, "加载视频库索引失败: ${t.message}")
            emptyList()
        }

        val entries = ArrayList<LibraryEntry>(rawItems.size + 1)
        val map = HashMap<String, LibraryEntry>(rawItems.size + 1)
        var hasEnsure = ensureSource == null

        for (v in rawItems) {
            val id = idForUri(v.uri)
            val entry = LibraryEntry(
                id = id,
                uri = v.uri,
                name = v.name,
                folder = v.folderPath,
                durationMs = v.durationMs,
                sizeBytes = v.sizeBytes,
                dateAddedSec = v.dateAddedSec,
                width = v.width,
                height = v.height,
                mime = guessMime(v.name),
            )
            entries += entry
            map[id] = entry
            if (ensureSource != null && v.uri == ensureSource.uri) hasEnsure = true
        }

        if (ensureSource != null && !hasEnsure) {
            val id = idForUri(ensureSource.uri)
            val fallbackFolder = if (ensureSource.uri.scheme == "file") {
                val root = android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: ""
                val parent = ensureSource.uri.path?.let { java.io.File(it).parentFile?.absolutePath } ?: ""
                if (root.isNotEmpty() && parent.startsWith(root)) parent.removePrefix(root).trim('/') else ""
            } else ""
            val entry = LibraryEntry(
                id = id,
                uri = ensureSource.uri,
                name = ensureSource.name,
                folder = fallbackFolder,
                durationMs = 0L,
                sizeBytes = ensureSource.size,
                dateAddedSec = System.currentTimeMillis() / 1000L,
                width = 0,
                height = 0,
                mime = ensureSource.mime,
            )
            entries.add(0, entry)
            map[id] = entry
        }

        libraryMap.clear()
        libraryMap.putAll(map)
        libraryList = entries
    }

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
                val rawTarget = parts.getOrNull(1) ?: "/"
                if (method != "GET" && method != "HEAD") {
                    writeSimple(s, "405 Method Not Allowed")
                    return
                }

                val path = rawTarget.substringBefore('?')
                val query = rawTarget.substringAfter('?', "")
                val range = parseRange(headers["range"])

                when {
                    path == "/api/library" -> serveLibraryJson(s, method, forceRefresh = query.contains("refresh=1"))
                    path.startsWith("/thumb/") -> {
                        val id = path.removePrefix("/thumb/").substringBefore('.').substringBefore('/')
                        serveThumbnail(s, method, id)
                    }
                    path.startsWith("/item/") -> {
                        val id = path.removePrefix("/item/").substringBefore('/')
                        val entry = libraryMap[id]
                        if (entry == null) {
                            writeSimple(s, "404 Not Found")
                        } else {
                            val src = if (entry.sizeBytes > 0) {
                                Source(entry.uri, entry.sizeBytes, entry.name, entry.mime)
                            } else {
                                describe(context, entry.uri)
                            }
                            serveSource(s, method, src, range)
                        }
                    }
                    else -> {
                        val cur = source
                        if (cur == null) {
                            writeSimple(s, "404 Not Found")
                        } else {
                            serveSource(s, method, cur, range)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.d(TAG, "HTTP 连接结束: ${e.message}")
            }
        }
    }

    private fun serveLibraryJson(socket: Socket, method: String, forceRefresh: Boolean) {
        if (forceRefresh || libraryList.isEmpty()) {
            refreshLibraryIndex(forceReload = forceRefresh)
        }
        val base = baseUrl
        val arr = JSONArray()
        for (item in libraryList) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("folder", item.folder)
            obj.put("durationMs", item.durationMs)
            obj.put("sizeBytes", item.sizeBytes)
            obj.put("dateAddedSec", item.dateAddedSec)
            obj.put("width", item.width)
            obj.put("height", item.height)
            obj.put("url", itemUrl(base, item.id, item.name))
            obj.put("thumb", "$base/thumb/${item.id}.jpg")
            arr.put(obj)
        }
        val root = JSONObject().apply {
            put("device", Build.MODEL ?: "Android")
            put("activeId", activeItemId)
            put("count", libraryList.size)
            put("items", arr)
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 15)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (method == "GET") out.write(bytes)
        out.flush()
    }

    private fun serveThumbnail(socket: Socket, method: String, id: String) {
        val entry = libraryMap[id] ?: run {
            writeSimple(socket, "404 Not Found")
            return
        }
        val bmp = try {
            VideoThumbnails.cached(entry.uri) ?: runBlocking { VideoThumbnails.load(context, entry.uri) }
        } catch (_: Throwable) {
            null
        }
        if (bmp == null) {
            writeSimple(socket, "404 Not Found")
            return
        }
        val baos = ByteArrayOutputStream(16 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        val bytes = baos.toByteArray()
        val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 15)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: max-age=3600\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (method == "GET") out.write(bytes)
        out.flush()
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

    private fun serveSource(socket: Socket, method: String, src: Source, range: LongRange?) {
        val total = src.size
        val out = BufferedOutputStream(socket.getOutputStream(), 1 shl 16)

        if (total <= 0) {
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
            val n = (-range.first).coerceAtMost(total)
            start = total - n
            end = total - 1
        } else if (range != null) {
            if (range.first >= total) {
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                out.flush()
                return
            }
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

        // 优先走 O(1) 内核 lseek（RandomAccessFile / ParcelFileDescriptor + FileChannel），
        // 避免 moov 在文件末尾的数百 MB ~ 数 GB MP4 出现慢速顺序 skip
        if (src.uri.scheme == "file") {
            val file = src.uri.path?.let { File(it) }
            if (file != null && file.exists()) {
                try {
                    RandomAccessFile(file, "r").use { raf ->
                        if (start > 0) raf.seek(start)
                        val buf = ByteArray(1 shl 16)
                        var remaining = length
                        while (remaining > 0 && running.get()) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            val n = raf.read(buf, 0, want)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                        out.flush()
                    }
                    return
                } catch (e: Throwable) {
                    Log.d(TAG, "RandomAccessFile 发送中断: ${e.message}")
                    return
                }
            }
        }

        val pfd = try {
            context.contentResolver.openFileDescriptor(src.uri, "r")
        } catch (_: Throwable) {
            null
        }
        if (pfd != null) {
            pfd.use { fd ->
                FileInputStream(fd.fileDescriptor).use { fis ->
                    if (start > 0) {
                        val positioned = runCatching {
                            fis.channel.position(start)
                            true
                        }.getOrDefault(false)
                        if (!positioned) skipFully(fis, start)
                    }
                    val buf = ByteArray(1 shl 16)
                    var remaining = length
                    while (remaining > 0 && running.get()) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val n = fis.read(buf, 0, want)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                    out.flush()
                }
            }
            return
        }

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
        private const val MAX_CONNECTIONS = 24
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_HEADER_LINE = 8192

        fun idForUri(uri: Uri): String {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(uri.toString().toByteArray(Charsets.UTF_8))
            return bytes.take(8).joinToString("") { "%02x".format(it) }
        }

        /** 按条目 ID 构造视频直播地址。 */
        fun itemUrl(base: String, id: String, name: String): String =
            "$base/item/$id/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        /** 旧版兼容地址格式。 */
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
