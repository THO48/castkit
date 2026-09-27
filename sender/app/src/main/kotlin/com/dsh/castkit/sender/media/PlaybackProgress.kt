package com.dsh.castkit.sender.media

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
     * 离片尾这么近就当作"已经到底了"（只在**拖到最后 1 秒**这种情形下命中）。
     *
     * 曾经这里是"离片尾 30 秒内一律清掉"，结果"差 20 秒看完就退出"的进度整个丢了
     * （用户实测报上来的）。**看完**这件事该由播放器的 `STATE_ENDED` / libVLC `EndReached`
     * 来判定（那边直接 `clear`），不该用"离结尾还有多远"去猜。
     */
    private const val END_EPSILON_MS = 1_000L

    /**
     * 全量进度表（`Uri` 原文 → 位置 ms）。
     *
     * 文件列表要按格子画"缩略图下面那条进度条" —— 每格单独去读一次 SharedPreferences
     * 会把同一条字符串解析几十上百遍，所以内存里留一份、磁盘只在启动时读一次，
     * 写入时同步更新并发射给 UI。
     */
    private val _positions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val positions: StateFlow<Map<String, Long>> = _positions.asStateFlow()

    @Volatile
    private var loaded = false

    /** 首次使用把磁盘记录读进内存（幂等；文件列表进页时调一次即可）。 */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        _positions.value = readFromDisk(context)
    }

    /**
     * 「正好看完」的判定阈值：默认 30 秒，短片按片长 1/4 收窄（20 秒的片子不该把后 30 秒都算完）。
     *
     * 注意它**不再参与"要不要记进度"**（那是 [save] 里 1 秒的 [END_EPSILON_MS]），
     * 只用于「投送结束时本机要不要自动接着播」：接收端已经放到结尾附近就别再起播了，
     * 否则手机上会把最后几秒又放一遍。
     */
    fun nearEndMs(durationMs: Long): Long =
        if (durationMs > 0) NEAR_END_MS.coerceAtMost(durationMs / 4) else NEAR_END_MS

    /** 上次看到哪（毫秒）。没有记录返回 0。 */
    fun get(context: Context, uri: Uri): Long {
        ensureLoaded(context)
        return _positions.value[uri.toString()] ?: 0L
    }

    /** 记一次进度；只把"拖到最后 1 秒"当作到底（真播完由播放器的 ENDED 清，见类注释）。 */
    fun save(context: Context, uri: Uri, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_SAVE_MS) return
        if (durationMs > 0 && positionMs >= durationMs - END_EPSILON_MS) {
            clear(context, uri)
            return
        }
        ensureLoaded(context)
        val key = uri.toString()
        val entries = LinkedHashMap<String, Long>(MAX_ENTRIES)
        entries[key] = positionMs
        _positions.value.forEach { (k, v) -> if (k != key && entries.size < MAX_ENTRIES) entries[k] = v }
        publish(context, entries)
    }

    fun clear(context: Context, uri: Uri) {
        ensureLoaded(context)
        val key = uri.toString()
        val map = _positions.value
        if (!map.containsKey(key)) return
        val entries = LinkedHashMap<String, Long>(map.size)
        map.forEach { (k, v) -> if (k != key) entries[k] = v }
        publish(context, entries)
    }

    /** 内存表与磁盘一起更新，并把新表发射给 UI。 */
    private fun publish(context: Context, entries: Map<String, Long>) {
        _positions.value = entries
        writeToDisk(context, entries)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readFromDisk(context: Context): Map<String, Long> = runCatching {
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

    private fun writeToDisk(context: Context, entries: Map<String, Long>) {
        val text = entries.entries.take(MAX_ENTRIES).joinToString("\n") { "${it.key}\t${it.value}" }
        runCatching { prefs(context).edit().putString(KEY, text).apply() }
            .onFailure { Log.w(TAG, "写入播放进度失败", it) }
    }
}
