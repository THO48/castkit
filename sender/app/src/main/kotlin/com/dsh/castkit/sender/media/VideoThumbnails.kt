package com.dsh.castkit.sender.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 视频缩略图：**内存 LRU + 磁盘缓存 + 并发限制**。
 *
 * 为什么要这样：抽一帧的代价在几十到两百毫秒，快速拖动时一屏会同时冒出一二十个条目，
 * 如果每个都直接去解码，解码线程会互相抢 CPU/IO，表现就是卡一下。
 * 于是：
 *  1. 同时最多只跑 [MAX_CONCURRENT] 个解码任务，其余排队；
 *  2. 解码结果同时写进磁盘（`cacheDir/thumb_cache`），重开应用也能直接命中，不必再抽帧；
 *  3. 条目滚出屏幕时协程被取消，解码用 [CancellationSignal] 一起中断，不做无用功。
 *
 * 缩略图尺寸统一为 [THUMB_W]×[THUMB_H]，这样内存/磁盘缓存键只跟 URI 有关，命中率最高。
 */
object VideoThumbnails {

    const val THUMB_W = 480
    const val THUMB_H = 270

    private const val DISK_DIR_NAME = "thumb_cache"
    private const val MAX_DISK_FILES = 800
    private const val EVICT_CHECK_EVERY = 32
    private const val JPEG_QUALITY = 82
    private const val MAX_CONCURRENT = 2

    private val cache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>(maxCacheKb()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }

    private fun maxCacheKb(): Int =
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(8 * 1024)

    private val decodeLimiter = Semaphore(MAX_CONCURRENT)
    private val writes = AtomicInteger(0)

    /** 组合期同步命中内存缓存，避免闪白。 */
    fun cached(uri: Uri): Bitmap? = cache.get(uri.toString())

    suspend fun load(context: Context, uri: Uri): Bitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }

        // 条目滚出屏幕 -> 协程取消 -> 中断底层解码
        val signal = CancellationSignal()
        currentCoroutineContext().job.invokeOnCompletion { signal.cancel() }

        return decodeLimiter.withPermit {
            cache.get(key)?.let { return@withPermit it }

            val fromDisk = withContext(Dispatchers.IO) {
                val f = diskFile(context, key)
                if (f.exists()) {
                    BitmapFactory.decodeFile(f.absolutePath)
                } else {
                    null
                }
            }
            if (fromDisk != null) {
                cache.put(key, fromDisk)
                return@withPermit fromDisk
            }

            val bitmap = generate(context, uri, signal) ?: return@withPermit null
            cache.put(key, bitmap)
            withContext(Dispatchers.IO) { writeToDisk(context, key, bitmap) }
            bitmap
        }
    }

    /**
     * 抽一帧当缩略图。
     *
     * 先走系统那套（媒体库缩略图 / `ThumbnailUtils`），**失败才交给 libVLC** ——
     * 安卓自带的解封装器不认识 ASF，`.wmv` 这类片源在系统这条路永远拿不到图，
     * 而 libVLC 自带的 FFmpeg 认识。反过来，普通片源走系统路径又快又省，
     * 所以顺序不能反。
     */
    private suspend fun generate(context: Context, uri: Uri, signal: CancellationSignal): Bitmap? {
        withContext(Dispatchers.IO) { systemThumbnail(context, uri, signal) }?.let { return it }
        if (signal.isCanceled) return null
        return VlcMediaInfo.frame(context, uri, THUMB_W, THUMB_H)
    }

    private fun systemThumbnail(context: Context, uri: Uri, signal: CancellationSignal): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val size = Size(THUMB_W, THUMB_H)
            if (uri.scheme == "file") {
                // .nomedia 扫出来的 file:// 不在媒体库里，直接抽帧
                val path = uri.path
                if (path == null) null else ThumbnailUtils.createVideoThumbnail(File(path), size, signal)
            } else {
                context.contentResolver.loadThumbnail(uri, size, signal)
            }
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    // ---------- 磁盘缓存 ----------

    private fun diskDir(context: Context): File =
        File(context.cacheDir, DISK_DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun diskFile(context: Context, key: String): File =
        File(diskDir(context), hash(key) + ".jpg")

    private fun writeToDisk(context: Context, key: String, bitmap: Bitmap) {
        try {
            val f = diskFile(context, key)
            if (!f.exists()) {
                f.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }
            if (writes.incrementAndGet() % EVICT_CHECK_EVERY == 0) evictIfNeeded(context)
        } catch (_: Throwable) {
        }
    }

    /** 简单 LRU：文件数超限时按最后修改时间删掉最旧的一批。 */
    private fun evictIfNeeded(context: Context) {
        try {
            val files = diskDir(context).listFiles() ?: return
            if (files.size <= MAX_DISK_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_DISK_FILES)
                .forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    private fun hash(key: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(key.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun clear() {
        cache.evictAll()
    }
}

/**
 * 视频时长与分辨率（按需探测，带缓存）。
 *
 * 两级来源：
 *  1. 系统 `MediaMetadataRetriever` —— 快，覆盖绝大多数片源，`content://` / `file://` 都吃；
 *  2. libVLC `Media.parse()` —— 只在第 1 级拿不到东西时才用。
 *     **这是 `.wmv`/`.asf` 唯一的出路**：安卓自带解封装器不认识 ASF，
 *     媒体库里这几个字段干脆是 `NULL`，`MediaMetadataRetriever` 也只会返回空。
 *
 * 结果（包括"确实拿不到"）都会缓存：判定是确定性的，重试只是白烧 CPU。
 * 探测本身也要限并发 —— `MediaMetadataRetriever` 不轻，libVLC 解析还要过全局锁。
 */
object VideoDurations {

    private const val MAX_CONCURRENT = 2

    private val cache = java.util.concurrent.ConcurrentHashMap<String, VideoProbeInfo>()
    private val limiter = Semaphore(MAX_CONCURRENT)

    /** 只要时长（给 `.nomedia` 结果落盘缓存用）。 */
    fun cached(uri: Uri): Long = cache[uri.toString()]?.durationMs ?: 0L

    /** 组合期同步命中缓存，避免先闪一下 `--:--`。 */
    fun cachedInfo(uri: Uri): VideoProbeInfo? = cache[uri.toString()]

    suspend fun resolveInfo(context: Context, uri: Uri): VideoProbeInfo {
        val key = uri.toString()
        cache[key]?.let { return it }
        return limiter.withPermit {
            cache[key]?.let { return@withPermit it }

            val system = withContext(Dispatchers.IO) { systemProbe(context, uri) }
            // 系统这套齐全（时长 + 分辨率都有）就到此为止 —— 绝大多数片源走这里。
            // 注意不能只看"系统返回了没有"：实测 `.mpg` 是**有分辨率、没时长**，
            // 这种半全的结果照样得让 libVLC 补一次，否则时长还是 `--:--`。
            val info = if (system != null && system.durationMs > 0 && system.width > 0 && system.height > 0) {
                system
            } else {
                val vlc = VlcMediaInfo.probe(context, uri)
                VideoProbeInfo(
                    durationMs = system?.durationMs?.takeIf { it > 0 } ?: vlc?.durationMs ?: 0L,
                    width = system?.width?.takeIf { it > 0 } ?: vlc?.width ?: 0,
                    height = system?.height?.takeIf { it > 0 } ?: vlc?.height ?: 0,
                )
            }
            cache[key] = info
            info
        }
    }

    private fun systemProbe(context: Context, uri: Uri): VideoProbeInfo? {
        return try {
            val r = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file") {
                    val path = uri.path ?: return null
                    r.setDataSource(path)
                } else {
                    r.setDataSource(context, uri)
                }
                val duration =
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val info = VideoProbeInfo(duration, width, height)
                // 时长和分辨率一个都没拿到，等价于"没探到"，交给下一级
                if (info.isEmpty) null else info
            } finally {
                runCatching { r.release() }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
