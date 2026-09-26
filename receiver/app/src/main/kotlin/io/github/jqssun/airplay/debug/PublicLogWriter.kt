package io.github.jqssun.airplay.debug

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * 把接收端日志镜像到 **公共 Download 目录**：`/sdcard/Download/castkit-receiver-logs.txt`。
 *
 * 用途：AirPlay/局域网投屏出问题时，外部工具（或 DSHA 桥的 `/app/readfile`）可以直接读取该文件，
 * 而不必依赖 App 内的日志页或 Android 的 ADB。
 *
 * 实现：API 29+ 走 MediaStore.Downloads（**不需要任何存储权限**）；更低版本直接跳过。
 * 写入做了节流与长度上限，避免频繁 IO。
 */
object PublicLogWriter {

    private const val TAG = "PublicLogWriter"
    const val FILE_NAME = "castkit-receiver-logs.txt"
    private const val MAX_CHARS = 900_000
    private const val MIN_INTERVAL_MS = 2_000L

    @Volatile
    private var lastWriteAt = 0L

    private val worker: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "public-log-writer").apply { isDaemon = true }
        }

    /** 由日志线程调用；内部会节流 + 异步落盘，可放心每次追加时调用。 */
    fun maybeWrite(context: Context, lines: List<String>, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastWriteAt < MIN_INTERVAL_MS) return
        lastWriteAt = now
        val appContext = context.applicationContext
        val snapshot = lines
        worker.execute { write(appContext, snapshot) }
    }

    /** 立即写一次（设置页/导出按钮用）。 */
    fun exportNow(context: Context, lines: List<String>): String? {
        lastWriteAt = System.currentTimeMillis()
        return write(context, lines)
    }

    private fun write(context: Context, lines: List<String>): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val text = lines.joinToString("\n").takeLast(MAX_CHARS)
            val resolver = context.contentResolver
            val uri = existingUri(context) ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: return null

            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            "Download/$FILE_NAME"
        } catch (e: Throwable) {
            Log.w(TAG, "写公共日志失败: ${e.message}")
            null
        }
    }

    private fun existingUri(context: Context): Uri? = try {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(FILE_NAME),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    c.getLong(0).toString(),
                )
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }
}
