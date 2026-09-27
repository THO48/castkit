package com.dsh.castkit.sender.media

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 每部视频记住播放进度：下次打开接着看。
 *
 * 存储用一条 SharedPreferences 字符串：`uri\t位置ms`，`\n` 分隔，**最近写的排最前**，
 * 最多留 [MAX_ENTRIES] 条。视频条目本来就不多，一次读全量、写全量足够；
 * 写走 `apply()`（异步落盘），不会卡主线程。
 *
 * 键就用 `Uri` 原文：同一个文件由 MediaStore 扫出来的
 * `content://media/external/video/media/1234` 是稳定的，重扫、重启都不变。
 *
 * 「看完」的判定见 [nearEndMs]：贴到片尾就**清掉**进度，而不是下次打开从结尾接着播。
 */
object PlaybackProgress {

    private const val TAG = "PlaybackProgress"
    private const val PREF = "castkit_playback_progress"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 200

    /** 「看完了」的默认阈值（离片尾 30 秒内算看完）。 */
    const val NEAR_END_MS = 30_000L

    /** 片头这几秒内不写：等于还没看，免得把上次的进度冲掉。 */
    private const val MIN_SAVE_MS = 5_000L

    /**
     * 「已经看完」的阈值：默认 30 秒，但短片要按片长收窄 ——
     * 20 秒的片子总不能让后 30 秒都算"已看完"，那样它永远记不住进度。
     */
    fun nearEndMs(durationMs: Long): Long =
        if (durationMs > 0) NEAR_END_MS.coerceAtMost(durationMs / 4) else NEAR_END_MS

    /** 上次看到哪（毫秒）。没有记录返回 0。 */
    fun get(context: Context, uri: Uri): Long = read(context)[uri.toString()] ?: 0L

    /** 记一次进度；贴到片尾时改为清掉。 */
    fun save(context: Context, uri: Uri, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_SAVE_MS) return
        if (durationMs > 0 && positionMs >= durationMs - nearEndMs(durationMs)) {
            clear(context, uri)
            return
        }
        val key = uri.toString()
        val entries = LinkedHashMap<String, Long>(MAX_ENTRIES)
        entries[key] = positionMs
        read(context).forEach { (k, v) -> if (k != key && entries.size < MAX_ENTRIES) entries[k] = v }
        write(context, entries)
    }

    fun clear(context: Context, uri: Uri) {
        val key = uri.toString()
        val map = read(context)
        if (!map.containsKey(key)) return
        val entries = LinkedHashMap<String, Long>(map.size)
        map.forEach { (k, v) -> if (k != key) entries[k] = v }
        write(context, entries)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun read(context: Context): Map<String, Long> = runCatching {
        val raw = prefs(context).getString(KEY, null).orEmpty()
        val out = LinkedHashMap<String, Long>()
        raw.lineSequence().forEach { line ->
            val i = line.lastIndexOf('\t')
            if (i > 0) {
                line.substring(i + 1).toLongOrNull()?.let { out[line.substring(0, i)] = it }
            }
        }
        out
    }.getOrElse {
        Log.w(TAG, "读取播放进度失败", it)
        emptyMap()
    }

    private fun write(context: Context, entries: Map<String, Long>) {
        val text = entries.entries.take(MAX_ENTRIES).joinToString("\n") { "${it.key}\t${it.value}" }
        runCatching { prefs(context).edit().putString(KEY, text).apply() }
            .onFailure { Log.w(TAG, "写入播放进度失败", it) }
    }
}
