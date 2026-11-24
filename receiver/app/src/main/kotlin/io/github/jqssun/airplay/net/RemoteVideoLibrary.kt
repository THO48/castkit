package io.github.jqssun.airplay.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 发送端通过 HTTP `/api/library` 暴露的单个视频条目。 */
data class RemoteVideoItem(
    val id: String,
    val name: String,
    val folder: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
    val url: String,
    val thumbUrl: String,
)

/** 接收端按目录聚合出的文件夹条目。 */
data class RemoteFolderEntry(
    val path: String,
    val name: String,
    val totalCount: Int,
    val subFolderCount: Int,
    val coverThumbUrl: String?,
    val aggDateSec: Long,
)

/** 发送端远程视频库加载状态。 */
data class RemoteLibraryState(
    val baseUrl: String? = null,
    val deviceName: String = "",
    val activeId: String = "",
    val items: List<RemoteVideoItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val connected: Boolean get() = !baseUrl.isNullOrBlank()
}

/** 远程视频库 HTTP 拉取与目录构建工具。 */
object RemoteVideoLibrary {

    private const val TAG = "RemoteVideoLibrary"

    /** 从任意播放 URL（如 `http://192.168.1.5:8130/item/abc/foo.mp4`）提取基址 `http://192.168.1.5:8130`。 */
    fun extractBaseUrl(playUrl: String): String? = runCatching {
        val u = URL(playUrl)
        val portPart = if (u.port > 0) ":${u.port}" else ""
        "${u.protocol}://${u.host}$portPart"
    }.getOrNull()

    suspend fun fetch(baseUrl: String, refresh: Boolean = false): Result<RemoteLibraryState> =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = if (refresh) "$baseUrl/api/library?refresh=1" else "$baseUrl/api/library"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = conn.responseCode
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw IllegalStateException("HTTP $code")
                    }
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val root = JSONObject(body)
                    val device = root.optString("device", "发送端")
                    val activeId = root.optString("activeId", "")
                    val arr = root.optJSONArray("items")
                    val list = ArrayList<RemoteVideoItem>(arr?.length() ?: 0)
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("id")
                            val url = o.optString("url")
                            if (id.isBlank() || url.isBlank()) continue
                            list += RemoteVideoItem(
                                id = id,
                                name = o.optString("name", "视频"),
                                folder = o.optString("folder", ""),
                                durationMs = o.optLong("durationMs", 0L),
                                sizeBytes = o.optLong("sizeBytes", 0L),
                                dateAddedSec = o.optLong("dateAddedSec", 0L),
                                width = o.optInt("width", 0),
                                height = o.optInt("height", 0),
                                url = url,
                                thumbUrl = o.optString("thumb", ""),
                            )
                        }
                    }
                    RemoteLibraryState(
                        baseUrl = baseUrl,
                        deviceName = device,
                        activeId = activeId,
                        items = list,
                        loading = false,
                        error = null,
                    )
                } finally {
                    conn.disconnect()
                }
            }.onFailure {
                Log.w(TAG, "拉取发送端视频库失败: ${it.message}")
            }
        }

    fun foldersIn(videos: List<RemoteVideoItem>, currentPath: String): List<RemoteFolderEntry> {
        val prefix = if (currentPath.isEmpty()) "" else "$currentPath/"
        val grouped = LinkedHashMap<String, MutableList<RemoteVideoItem>>()
        for (v in videos) {
            if (!v.folder.startsWith(prefix)) continue
            val rest = v.folder.substring(prefix.length)
            if (rest.isEmpty()) continue
            val childPath = prefix + rest.substringBefore('/')
            grouped.getOrPut(childPath) { mutableListOf() }.add(v)
        }
        return grouped.map { (path, list) ->
            val subCount = list.mapNotNull {
                val rest = it.folder.removePrefix("$path/")
                if (rest.isEmpty() || !it.folder.startsWith("$path/")) null
                else rest.substringBefore('/')
            }.toSet().size
            RemoteFolderEntry(
                path = path,
                name = path.substringAfterLast('/'),
                totalCount = list.size,
                subFolderCount = subCount,
                coverThumbUrl = list.firstOrNull { it.thumbUrl.isNotBlank() }?.thumbUrl,
                aggDateSec = list.maxOfOrNull { it.dateAddedSec } ?: 0L,
            )
        }.sortedByDescending { it.aggDateSec }
    }

    fun videosIn(videos: List<RemoteVideoItem>, currentPath: String): List<RemoteVideoItem> =
        videos.filter { it.folder == currentPath }.sortedByDescending { it.dateAddedSec }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}

/** 局域网缩略图内存 LRU 缓存（并发限制为 3，防止拖动列表打满连接）。 */
object RemoteThumbCache {
    private val cache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(maxCacheKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

    private fun maxCacheKb(): Int =
        (Runtime.getRuntime().maxMemory() / 1024 / 10).toInt().coerceAtLeast(12 * 1024)

    private val limiter = Semaphore(3)

    fun cached(url: String): Bitmap? = if (url.isBlank()) null else cache.get(url)

    suspend fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }
        return limiter.withPermit {
            cache.get(url)?.let { return@withPermit it }
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3500
                        readTimeout = 5000
                    }
                    try {
                        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                            conn.inputStream.use { BitmapFactory.decodeStream(it) }
                        } else null
                    } finally {
                        conn.disconnect()
                    }
                } catch (_: Throwable) {
                    null
                }
            }
            if (bmp != null) cache.put(url, bmp)
            bmp
        }
    }

    fun clear() = cache.evictAll()
}
