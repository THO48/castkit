package com.dsh.castkit.sender.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

/** 探测出来的媒体信息（拿不到的项为 0）。 */
data class VideoProbeInfo(
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isEmpty: Boolean get() = durationMs <= 0 && (width <= 0 || height <= 0)

    companion object {
        val NONE = VideoProbeInfo()
    }
}

/**
 * 用 libVLC 补框架拿不到的媒体信息与缩略图。
 *
 * ## 为什么需要
 *
 * 安卓自带的 `MediaMetadataRetriever` / `MediaStore` / `ThumbnailUtils` **都没有 ASF 解封装器**，
 * 所以 `.wmv` 在媒体库里的 `duration` / `width` / `height` 全是 `NULL`（实测 `content query`
 * 确认过），缩略图也生成不出来。列表里就成了「没有预览图、也没有时长」。
 *
 * libVLC 自带 FFmpeg 的解封装器，同一份片源它能读出 ASF 头里的时长与分辨率，
 * 也能解码出画面 —— 于是这两件事都交给它兜底。
 *
 * ## 两条路
 *
 * - [probe]：只解析容器头，很便宜（几十到几百毫秒），用来补时长/宽高。
 * - [frame]：真起一个播放器解码抽一帧，贵得多（一到几秒），只用来补缩略图。
 *
 * ## 抽帧为什么这么绕
 *
 * `libvlc-all` 的 Java 层**没有暴露 `takeSnapshot`**（整个 jar 里搜不到任何 snapshot 方法，
 * 原生 `libvlc_video_take_snapshot` 也没绑上来），所以不能用官方截图接口。
 * 改用 [ImageReader]：它的 `surface` 就是一个标准 BufferQueue 生产者，libVLC 的 gles2 vout
 * 可以照常往上渲染，渲染完我们从消费者侧 `acquireLatestImage()` 把像素读出来。
 * 这样既不用自己搭 EGL，也不用往界面里塞一个隐藏的 SurfaceView。
 *
 * 两个入口都在 [gate] 上串行：抽帧会真解码，并发跑几个既抢 CPU 又抢 libVLC 内部锁。
 */
object VlcMediaInfo {

    private const val TAG = "VlcMediaInfo"

    /** 抽帧最长等多久（超时就当抽不出来，返回 null，界面继续用占位图）。 */
    private const val FRAME_TIMEOUT_MS = 5000L

    /** 轮询 [ImageReader] 的间隔。 */
    private const val POLL_MS = 30L

    /** 只解封装取时长时等多久。 */
    private const val LENGTH_TIMEOUT_MS = 4000L

    /** 探测时用的渲染目标尺寸：只要让 vout 建得起来就行，不需要真的看画面。 */
    private const val PROBE_W = 480
    private const val PROBE_H = 270

    /** 全黑帧判定阈值：整帧最大亮度低于它就认为"这张没意义"，继续等下一帧。 */
    private const val BLACK_FRAME_MAX_LUMA = 12

    private val gate = Semaphore(1)

    // ------------------------------------------------------------------
    // 时长 / 宽高
    // ------------------------------------------------------------------

    /** 解析容器头拿时长与分辨率。失败返回 null（调用方会缓存成"没有"）。 */
    suspend fun probe(context: Context, uri: Uri): VideoProbeInfo? = gate.withPermit {
        withContext(Dispatchers.IO) {
            val lib = VlcHolder.get(context) ?: return@withContext null
            // 一级：只解析容器头，最便宜
            val parsed = parseOnly(context, lib, uri)
            Log.d(TAG, "probe(parse) $uri -> dur=${parsed?.durationMs} ${parsed?.width}x${parsed?.height}")
            if (parsed != null && parsed.durationMs > 0 && parsed.width > 0) {
                return@withContext parsed
            }

            // 二级：preparse 拿不到时（ASF 实测如此）起一个真播放器。
            // `Media.parse()` 对 `fd://` 上的 ASF 只会返回 dur=0、一条轨都没有，
            // 而同一个文件起播放器就什么都读得出来 —— 所以这一步是必须的，不是保险。
            val played = playProbe(context, lib, uri)
            Log.d(TAG, "probe(player) $uri -> dur=${played?.durationMs} ${played?.width}x${played?.height}")
            played ?: parsed
        }
    }

    private fun parseOnly(context: Context, lib: LibVLC, uri: Uri): VideoProbeInfo? =
        withMedia(context, lib, uri) { media ->
            media.parse(IMedia.Parse.ParseLocal)
            VideoProbeInfo(
                durationMs = media.duration.coerceAtLeast(0),
                width = videoTrackWidth(media),
                height = videoTrackHeight(media),
            )
        }

    /**
     * 起一个真播放器读时长与分辨率。
     *
     * 为什么不能只解封装：`Media.parse()` 在这类片源上返回 `dur=0`、`trackCount=0`，
     * 而**视频轨尺寸只有播放器选上轨之后才拿得到**（`currentVideoTrack`）。
     * 之所以还要挂一个 [ImageReader] 的 surface：VLC 没有可渲染的目标时根本不会建 vout，
     * 视频轨也就不会被选中，尺寸还是读不到 —— 这一步是让它"以为自己有地方输出"，
     * 代价很小（不读像素、不解码多久）。
     */
    private fun playProbe(context: Context, lib: LibVLC, uri: Uri): VideoProbeInfo? =
        withMedia(context, lib, uri) { media ->
            media.addOption(":no-audio")
            media.addOption(":no-spu")
            withPlayer(lib, media, PROBE_W, PROBE_H) { mp, reader ->
                val deadline = SystemClock.elapsedRealtime() + LENGTH_TIMEOUT_MS
                var len = 0L
                var w = 0
                var h = 0
                while (SystemClock.elapsedRealtime() < deadline) {
                    len = mp.length
                    if (w <= 0) {
                        val track = runCatching { mp.currentVideoTrack }.getOrNull()
                        if (track != null && track.width > 0 && track.height > 0) {
                            w = track.width
                            h = track.height
                        }
                    }
                    // 不读像素，但必须把队列里的帧丢掉，否则生产者写满就卡住
                    runCatching { reader.acquireLatestImage()?.close() }
                    if (len > 0 && w > 0) break
                    Thread.sleep(POLL_MS)
                }
                VideoProbeInfo(len.coerceAtLeast(0), w, h)
            }
        }

    private fun videoTrackWidth(media: Media): Int = videoTrack(media)?.width ?: 0

    private fun videoTrackHeight(media: Media): Int = videoTrack(media)?.height ?: 0

    private fun videoTrack(media: Media): IMedia.VideoTrack? {
        for (i in 0 until media.trackCount) {
            val track = media.getTrack(i)
            if (track is IMedia.VideoTrack && track.width > 0 && track.height > 0) return track
        }
        return null
    }

    // ------------------------------------------------------------------
    // 缩略图
    // ------------------------------------------------------------------

    /** 解码出第一张有意义的画面，缩放到 [w]×[h]。失败返回 null。 */
    suspend fun frame(context: Context, uri: Uri, w: Int, h: Int): Bitmap? = gate.withPermit {
        withContext(Dispatchers.IO) {
            val lib = VlcHolder.get(context) ?: return@withContext null
            withMedia(context, lib, uri) { media ->
                media.addOption(":no-audio")
                media.addOption(":no-spu")
                grabFrame(lib, media, w, h)
            }
        }
    }

    private fun grabFrame(lib: LibVLC, media: Media, w: Int, h: Int): Bitmap? =
        withPlayer(lib, media, w, h) { _, reader ->
            var best: Bitmap? = null
            val deadline = SystemClock.elapsedRealtime() + FRAME_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val image = reader.acquireLatestImage()
                if (image == null) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                val bmp = try {
                    imageToBitmap(image, w, h)
                } finally {
                    runCatching { image.close() }
                }
                if (bmp == null) continue
                best?.recycle()
                best = bmp
                // 首帧常常是纯黑（片头/淡入），这种当缩略图很难看，等到有内容的帧再收工
                if (!isMostlyBlack(bmp)) break
            }
            best
        }

    /**
     * 建播放器 + [ImageReader]，把画面渲染目标接上去，播起来，交给 [block]，
     * 最后**无论成败**都把这一整套拆干净（vout 必须 detach 再 release，否则 native 侧会攥着 surface）。
     */
    private fun <T> withPlayer(
        lib: LibVLC,
        media: Media,
        w: Int,
        h: Int,
        block: (MediaPlayer, ImageReader) -> T,
    ): T? {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4)
        val mp = MediaPlayer(lib)
        return try {
            mp.media = media
            val vout = mp.vlcVout
            vout.setVideoSurface(reader.surface, null)
            vout.attachViews()
            // 必须给真实像素尺寸，否则画面会被排到更大的画布上、我们读到的只是一块黑
            vout.setWindowSize(w, h)
            mp.play()
            block(mp, reader)
        } catch (t: Throwable) {
            Log.w(TAG, "libVLC 播放失败: ${t.message}")
            null
        } finally {
            runCatching { mp.stop() }
            runCatching { mp.vlcVout.detachViews() }
            runCatching { mp.release() }
            runCatching { reader.close() }
        }
    }

    private fun imageToBitmap(image: Image, w: Int, h: Int): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0) return null
        val rowPadding = rowStride - pixelStride * w
        val paddedWidth = w + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == w) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return cropped
    }

    /** 整帧几乎全黑（隔行采样，够用且便宜）。 */
    private fun isMostlyBlack(bmp: Bitmap): Boolean {
        var maxLuma = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val luma = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                if (luma > maxLuma) maxLuma = luma
                if (maxLuma > BLACK_FRAME_MAX_LUMA) return false
                x += 8
            }
            y += 8
        }
        return true
    }

    // ------------------------------------------------------------------
    // 公共：建 Media 并保证句柄/资源成对释放
    // ------------------------------------------------------------------

    /**
     * libVLC 的 access 模块里**没有 `content://`**（实测 `no access modules matched`），
     * 所以媒体库条目必须先向 ContentResolver 要一个文件句柄，再用 fd 建 [Media]。
     * 句柄要活到播放结束，所以在这里统一持有、统一关。
     */
    private fun <T> withMedia(
        context: Context,
        lib: LibVLC,
        uri: Uri,
        block: (Media) -> T,
    ): T? {
        var pfd: ParcelFileDescriptor? = null
        val media: Media
        try {
            if (uri.scheme == "content") {
                val opened = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                pfd = opened
                media = Media(lib, opened.fileDescriptor)
            } else {
                media = Media(lib, uri)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "建 Media 失败: ${t.message}")
            runCatching { pfd?.close() }
            return null
        }

        return try {
            block(media)
        } catch (t: Throwable) {
            Log.w(TAG, "libVLC 处理失败: ${t.message}")
            null
        } finally {
            runCatching { media.release() }
            runCatching { pfd?.close() }
        }
    }
}
