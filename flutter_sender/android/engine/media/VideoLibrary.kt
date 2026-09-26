package com.dsh.castkit.sender.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/** 排序字段。 */
enum class VideoSortField(val label: String) {
    DATE("时间"),
    NAME("名称"),
    SIZE("大小"),
    DURATION("时长"),
}

/** 预览图标大小：同时决定文件夹与视频每行几个。 */
enum class BrowserIconSize(val label: String, val folderCols: Int, val videoCols: Int) {
    TINY("最小", 5, 3),
    SMALL("小", 4, 3),
    MEDIUM("中", 3, 2),
    LARGE("大", 2, 1),
}

/** 单个视频条目。 */
data class VideoItem(
    val uri: Uri,
    val name: String,
    /** 所在文件夹（相对内部存储，根为 ""），如 `Movies/Telegram`。 */
    val folderPath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** 秒级时间戳（MediaStore 的 DATE_ADDED 或文件的 lastModified）。 */
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
) {
    /** 唯一键：完整相对路径。 */
    val key: String get() = if (folderPath.isEmpty()) name else "$folderPath/$name"
}

/** 文件夹条目（含子目录聚合信息，供排序与展示）。 */
data class FolderEntry(
    val path: String,
    val name: String,
    /** 直接放在该文件夹里的视频数。 */
    val directCount: Int,
    /** 含所有子目录的视频总数。 */
    val totalCount: Int,
    val subFolderCount: Int,
    val coverUri: Uri?,
    val aggDateSec: Long,
    val aggSizeBytes: Long,
    val aggDurationMs: Long,
)

/**
 * 本机视频库（MediaStore + 可选的文件系统扫描），给「投视频文件」的内置浏览器用。
 *
 * - 常规部分走 MediaStore（需要 `READ_MEDIA_VIDEO`），能拿到时长/宽高/缩略图；
 * - `.nomedia` 目录**不在 MediaStore 里**（扫描器会跳过），只能用文件系统遍历补，
 *   而 Android 11+ 上访问这些文件需要「所有文件访问」权限，拿不到时这部分为空。
 * - 目录结构由每条的相对路径推导，天然是树：默认只列最上级，进去再看子目录。
 */
/**
 * 进程内缓存：切换标签页/进出播放页时不必重新查询，直接拿上一次的结果。
 */
object VideoLibraryCache {
    @Volatile
    var merged: List<VideoItem>? = null
}

object VideoLibrary {

    private const val TAG = "VideoLibrary"
    const val ROOT_PATH = ""

    /**
     * .nomedia 扫描时最多为多少个文件探时长。
     * 扫描已在后台跑且时长有磁盘缓存 + 界面按需补探兜底，这里只探一小批让角标尽快出现。
     */
    private const val MAX_DURATION_PROBE = 60

    private val VIDEO_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "ts", "mov", "avi", "flv", "3gp", "wmv", "mpg", "mpeg", "rmvb", "m2ts",
    )

    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private val projection: Array<String> = buildList {
        add(MediaStore.Video.Media._ID)
        add(MediaStore.Video.Media.DISPLAY_NAME)
        add(MediaStore.Video.Media.DURATION)
        add(MediaStore.Video.Media.SIZE)
        add(MediaStore.Video.Media.WIDTH)
        add(MediaStore.Video.Media.HEIGHT)
        add(MediaStore.Video.Media.DATE_ADDED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Video.Media.RELATIVE_PATH)
        }
    }.toTypedArray()

    /** MediaStore 里的全部视频。 */
    fun loadFromMediaStore(context: Context): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        try {
            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val durIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeIdx = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val wIdx = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val hIdx = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val dateIdx = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val relIdx = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    if (id <= 0) continue
                    val rel = relIdx.takeIf { it >= 0 }?.let { c.getString(it) } ?: ""
                    out += VideoItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = nameIdx.takeIf { it >= 0 }?.let { c.getString(it) } ?: "视频 $id",
                        folderPath = rel.trim('/'),
                        durationMs = durIdx.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L,
                        sizeBytes = sizeIdx.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L,
                        dateAddedSec = dateIdx.takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L,
                        width = wIdx.takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0,
                        height = hIdx.takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0,
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "没有读取视频的权限: ${e.message}")
        } catch (e: Throwable) {
            Log.w(TAG, "扫描视频失败: ${e.message}")
        }
        return out
    }

    /**
     * 文件系统扫描含 `.nomedia` 的文件夹（这些目录 MediaStore 看不到）。
     *
     * 需要「所有文件访问」权限；没有权限时多数目录 `listFiles()` 会返回 null，结果自然为空。
     * 限制深度与时间预算，避免在超大存储上卡太久。
     */
    fun scanNoMediaFolders(
        context: Context,
        budgetMs: Long = 12_000L,
        maxDepth: Int = 10,
    ): List<VideoItem> {
        if (!canReadAllFiles(context)) {
            Log.i(TAG, "没有「所有文件访问」权限，跳过 .nomedia 扫描")
            return emptyList()
        }
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        val out = ArrayList<VideoItem>()
        val deadline = System.currentTimeMillis() + budgetMs
        var probed = 0

        // 关键：MediaStore 是**整棵子树**都跳过，所以 .nomedia 目录下的子目录也要一并下探
        fun walk(dir: File, depth: Int, insideNoMedia: Boolean) {
            if (depth > maxDepth || System.currentTimeMillis() > deadline) return
            val children = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: return

            val noMediaHere = insideNoMedia ||
                children.any { it.isFile && it.name.equals(".nomedia", ignoreCase = true) }

            if (noMediaHere) {
                children.filter { it.isFile && it.extension.lowercase() in VIDEO_EXT }.forEach { f ->
                    // 不在媒体库里，时长只能自己探；限制数量与时间预算，其余的等界面按需再探
                    val duration = if (probed < MAX_DURATION_PROBE && System.currentTimeMillis() < deadline) {
                        probed++
                        probeDurationMs(f)
                    } else {
                        0L
                    }
                    out += fileItem(root, f, duration)
                }
            }
            children.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "Android" }
                .forEach { walk(it, depth + 1, noMediaHere) }
        }

        try {
            walk(root, 0, insideNoMedia = false)
        } catch (e: Throwable) {
            Log.w(TAG, ".nomedia 扫描失败: ${e.message}")
        }
        Log.i(TAG, ".nomedia 扫描到 ${out.size} 个视频")
        return out
    }

    /** 逐个文件探时长（MediaMetadataRetriever），失败返回 0。 */
    private fun probeDurationMs(f: File): Long = try {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { r.release() }
        }
    } catch (_: Throwable) {
        0L
    }

    private fun fileItem(root: File, f: File, durationMs: Long): VideoItem {
        val rel = try {
            f.parentFile?.absolutePath?.removePrefix(root.absolutePath)?.trim('/') ?: ""
        } catch (_: Throwable) {
            ""
        }
        return VideoItem(
            uri = Uri.fromFile(f),
            name = f.name,
            folderPath = rel,
            durationMs = durationMs,
            sizeBytes = f.length(),
            dateAddedSec = f.lastModified() / 1000,
            width = 0,
            height = 0,
        )
    }

    /** 是否已获得「所有文件访问」权限。 */
    fun canReadAllFiles(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /** 汇总：MediaStore + 可选的 .nomedia 扫描结果（按路径去重）。 */
    fun loadAll(context: Context, includeNoMedia: Boolean): List<VideoItem> {
        val store = loadFromMediaStore(context)
        if (!includeNoMedia) return store
        val known = store.mapTo(HashSet()) { it.key }
        val extra = scanNoMediaFolders(context).filter { it.key !in known }
        return store + extra
    }

    // ---------- 缓存：让"重开应用"几乎瞬间出内容 ----------
    //
    // MediaStore 查询很快（几十到几百毫秒），慢的是 .nomedia 的全盘遍历，
    // 所以只把 .nomedia 那部分落盘缓存；媒体库部分每次现查即可。

    /** .nomedia 扫描结果的磁盘缓存文件名。 */
    private const val CACHE_FILE = "videolib_nomedia.tsv"

    /** 缓存多久算新鲜（超过就该后台重扫）。 */
    const val NOMEDIA_TTL_MS = 5 * 60 * 1000L

    private fun cacheFile(context: Context) = File(context.cacheDir, CACHE_FILE)

    /** 缓存里 .nomedia 条目的年龄；没有缓存返回 Long.MAX_VALUE。 */
    fun noMediaCacheAgeMs(context: Context): Long {
        val f = cacheFile(context)
        return if (f.exists()) {
            (System.currentTimeMillis() - f.lastModified()).coerceAtLeast(0)
        } else {
            Long.MAX_VALUE
        }
    }

    fun cachedNoMedia(context: Context): List<VideoItem> {
        val f = cacheFile(context)
        if (!f.exists()) return emptyList()
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        return try {
            f.readLines().drop(1).mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                val folder = p[0]
                val name = p[1]
                val size = p[2].toLongOrNull() ?: 0L
                val mtime = p[3].toLongOrNull() ?: 0L
                val duration = p[4].toLongOrNull() ?: 0L
                val file = File(root, if (folder.isEmpty()) name else "$folder/$name")
                VideoItem(
                    uri = Uri.fromFile(file),
                    name = name,
                    folderPath = folder,
                    durationMs = duration,
                    sizeBytes = size,
                    dateAddedSec = mtime,
                    width = 0,
                    height = 0,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "读取 .nomedia 缓存失败: ${e.message}")
            emptyList()
        }
    }

    fun saveNoMediaCache(context: Context, items: List<VideoItem>) {
        try {
            cacheFile(context).writeText(
                buildString {
                    append(System.currentTimeMillis()).append('\n')
                    items.forEach { v ->
                        // 顺带把界面已按需探到的时长一起存下来，下次直接就有角标
                        val duration = maxOf(v.durationMs, VideoDurations.cached(v.uri))
                        append(v.folderPath).append('\t').append(v.name).append('\t')
                            .append(v.sizeBytes).append('\t').append(v.dateAddedSec).append('\t')
                            .append(duration).append('\n')
                    }
                },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "写 .nomedia 缓存失败: ${e.message}")
        }
    }

    /** 当前文件夹下的**直接子文件夹**（聚合了子树的数量/封面/排序键）。 */
    fun foldersIn(videos: List<VideoItem>, currentPath: String): List<FolderEntry> {
        val prefix = if (currentPath.isEmpty()) "" else "$currentPath/"
        val grouped = LinkedHashMap<String, MutableList<VideoItem>>()
        for (v in videos) {
            if (!v.folderPath.startsWith(prefix)) continue
            val rest = v.folderPath.substring(prefix.length)
            if (rest.isEmpty()) continue
            val childPath = prefix + rest.substringBefore('/')
            grouped.getOrPut(childPath) { mutableListOf() }.add(v)
        }
        return grouped.map { (path, list) ->
            val cover = list.firstOrNull()
            FolderEntry(
                path = path,
                name = path.substringAfterLast('/'),
                directCount = list.count { it.folderPath == path },
                totalCount = list.size,
                subFolderCount = list.mapNotNull {
                    val rest = it.folderPath.removePrefix("$path/")
                    if (rest.isEmpty() || !it.folderPath.startsWith("$path/")) null
                    else rest.substringBefore('/')
                }.toSet().size,
                coverUri = cover?.uri,
                aggDateSec = list.maxOfOrNull { it.dateAddedSec } ?: 0L,
                aggSizeBytes = list.sumOf { it.sizeBytes },
                aggDurationMs = list.sumOf { it.durationMs },
            )
        }
    }

    /** 当前文件夹**直接包含**的视频。 */
    fun videosIn(videos: List<VideoItem>, currentPath: String): List<VideoItem> =
        videos.filter { it.folderPath == currentPath }

    fun sortVideos(videos: List<VideoItem>, field: VideoSortField, asc: Boolean): List<VideoItem> {
        val cmp = when (field) {
            VideoSortField.DATE -> compareBy<VideoItem> { it.dateAddedSec }
            VideoSortField.NAME -> compareBy { it.name.lowercase() }
            VideoSortField.SIZE -> compareBy { it.sizeBytes }
            VideoSortField.DURATION -> compareBy { it.durationMs }
        }
        return videos.sortedWith(if (asc) cmp else cmp.reversed())
    }

    fun sortFolders(folders: List<FolderEntry>, field: VideoSortField, asc: Boolean): List<FolderEntry> {
        val cmp = when (field) {
            VideoSortField.DATE -> compareBy<FolderEntry> { it.aggDateSec }
            VideoSortField.NAME -> compareBy { it.name.lowercase() }
            VideoSortField.SIZE -> compareBy { it.aggSizeBytes }
            VideoSortField.DURATION -> compareBy { it.aggDurationMs }
        }
        return folders.sortedWith(if (asc) cmp else cmp.reversed())
    }

    /** 面包屑：内部存储 / Movies / Telegram */
    fun breadcrumb(path: String): String =
        if (path.isEmpty()) "内部存储" else "内部存储 / " + path.split('/').joinToString(" / ")

    /** 时长格式化 mm:ss / h:mm:ss。 */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** 文件大小格式化。 */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
