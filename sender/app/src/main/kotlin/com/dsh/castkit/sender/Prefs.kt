package com.dsh.castkit.sender

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/** 最近连接过的接收端记录（用于快速重连与 AP 隔离兜底）。 */
data class SavedReceiver(
    val name: String,
    val host: String,
    val port: Int,
) {
    val id: String get() = "$host:$port"
}

/** 一次投屏会话的编码/网络参数。 */
data class CastConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val host: String,
    val port: Int,
    val audio: Boolean = false,
    val targetName: String = "",
    /** 尺寸计算时的提示（被夹取/回退），用于 UI 展示。 */
    val sizeNote: String? = null,
) {
    val videoLabel: String get() = "${width}×${height} @${fps}fps ${bitrateBps / 1_000_000}Mbps"
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 16f / 9f
    /** 竖屏（高 > 宽）。 */
    val portrait: Boolean get() = height >= width
}

/**
 * 分辨率预设。`shortSide` = 短边像素（保持屏幕比例时使用）；
 * `landscapeWidth/Height` = 关闭「保持屏幕比例」时使用的字面 16:9 尺寸。
 */
enum class ResolutionPreset(
    val label: String,
    val shortSide: Int?,
    val landscapeWidth: Int,
    val landscapeHeight: Int,
) {
    P480("480p", 480, 854, 480),
    P720("720p", 720, 1280, 720),
    P1080("1080p", 1080, 1920, 1080),
    P1440("1440p", 1440, 2560, 1440),
    NATIVE("跟随本机", null, 0, 0),
    CUSTOM("自定义", null, 0, 0),
}

object Prefs {
    private const val FILE = "castkit_sender"
    private const val PROGRESS_FILE = "castkit_video_progress"
    private const val K_PRESET = "preset"
    private const val K_CUSTOM_W = "custom_w"
    private const val K_CUSTOM_H = "custom_h"
    private const val K_KEEP_ASPECT = "keep_aspect"
    private const val K_FPS = "fps"
    private const val K_BITRATE = "bitrate"
    private const val K_MIRROR_AUDIO = "mirror_audio"
    private const val K_HOST = "host"
    private const val K_PORT = "port"
    private const val K_TARGET_NAME = "target_name"
    private const val K_RECENT_RECEIVERS = "recent_receivers"
    private const val K_AUTO = "auto_discovered"
    private const val K_SORT_FIELD = "browser_sort_field"
    private const val K_SORT_ASC = "browser_sort_asc"
    private const val K_ICON_SIZE = "browser_icon_size"
    private const val K_NOMEDIA = "browser_include_nomedia"

    const val DEF_FPS = 60
    const val DEF_BITRATE_MBPS = 10
    const val MAX_BITRATE_MBPS = 30
    const val DEF_PORT = 8123
    const val DEF_CUSTOM_SHORT_SIDE = 720
    const val DEF_KEEP_ASPECT = true

    fun get(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun progressPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PROGRESS_FILE, Context.MODE_PRIVATE)

    /** 目标地址是否来自自动发现（用于决定是否展开「手动输入」）。 */
    fun autoDiscovered(context: Context): Boolean = get(context).getBoolean(K_AUTO, true)

    fun markAutoDiscovered(context: Context, auto: Boolean) {
        get(context).edit().putBoolean(K_AUTO, auto).apply()
    }

    fun keepAspect(context: Context): Boolean = get(context).getBoolean(K_KEEP_ASPECT, DEF_KEEP_ASPECT)

    fun mirrorAudio(context: Context): Boolean = get(context).getBoolean(K_MIRROR_AUDIO, false)

    fun setMirrorAudio(context: Context, enabled: Boolean) {
        get(context).edit().putBoolean(K_MIRROR_AUDIO, enabled).apply()
    }

    fun targetName(context: Context): String = get(context).getString(K_TARGET_NAME, "") ?: ""

    /**
     * 由持久化偏好解析出实际编码参数。
     * 尺寸计算交给 [CaptureSize]：按屏幕比例推导，避免非 16:9 屏幕被拉伸。
     */
    fun config(context: Context): CastConfig {
        val p = get(context)
        val preset = preset(context)
        val (screenW, screenH) = nativeSize(context)
        val keepAspect = p.getBoolean(K_KEEP_ASPECT, DEF_KEEP_ASPECT)
        val customW = p.getInt(K_CUSTOM_W, DEF_CUSTOM_SHORT_SIDE)
        val customH = p.getInt(K_CUSTOM_H, 720)

        val size = CaptureSize.compute(
            screenW = screenW,
            screenH = screenH,
            preset = preset,
            customW = customW,
            customH = customH,
            keepAspect = keepAspect,
        )

        return CastConfig(
            width = size.width,
            height = size.height,
            fps = p.getInt(K_FPS, DEF_FPS).coerceIn(5, 60),
            bitrateBps = p.getInt(K_BITRATE, DEF_BITRATE_MBPS * 1_000_000).coerceIn(500_000, 50_000_000),
            host = p.getString(K_HOST, "")!!.trim(),
            port = p.getInt(K_PORT, DEF_PORT).coerceIn(1, 65535),
            audio = p.getBoolean(K_MIRROR_AUDIO, false),
            targetName = p.getString(K_TARGET_NAME, "") ?: "",
            sizeNote = size.note,
        )
    }

    fun persistConfig(
        context: Context,
        preset: ResolutionPreset,
        customW: Int,
        customH: Int,
        fps: Int,
        mbps: Int,
        host: String,
        port: Int,
        keepAspect: Boolean = keepAspect(context),
        mirrorAudio: Boolean = mirrorAudio(context),
        targetName: String = targetName(context),
    ) {
        get(context).edit()
            .putString(K_PRESET, preset.name)
            .putInt(K_CUSTOM_W, customW)
            .putInt(K_CUSTOM_H, customH)
            .putBoolean(K_KEEP_ASPECT, keepAspect)
            .putInt(K_FPS, fps)
            .putInt(K_BITRATE, mbps * 1_000_000)
            .putBoolean(K_MIRROR_AUDIO, mirrorAudio)
            .putString(K_HOST, host)
            .putInt(K_PORT, port)
            .putString(K_TARGET_NAME, targetName)
            .apply()
    }

    fun preset(context: Context): ResolutionPreset = runCatching {
        ResolutionPreset.valueOf(get(context).getString(K_PRESET, ResolutionPreset.P1080.name)!!)
    }.getOrDefault(ResolutionPreset.P1080)

    fun customWidth(context: Context) = get(context).getInt(K_CUSTOM_W, DEF_CUSTOM_SHORT_SIDE)
    fun customHeight(context: Context) = get(context).getInt(K_CUSTOM_H, 720)
    fun fps(context: Context) = get(context).getInt(K_FPS, DEF_FPS)
    fun mbps(context: Context) = (get(context).getInt(K_BITRATE, DEF_BITRATE_MBPS * 1_000_000) / 1_000_000)
        .coerceIn(1, MAX_BITRATE_MBPS)
    fun host(context: Context) = get(context).getString(K_HOST, "")!!.trim()
    fun port(context: Context) = get(context).getInt(K_PORT, DEF_PORT)

    // ---- 最近连接过的接收端 ----

    fun recentReceivers(context: Context): List<SavedReceiver> {
        val raw = get(context).getString(K_RECENT_RECEIVERS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@mapNotNull null
            val host = parts[0].trim()
            val port = parts[1].toIntOrNull() ?: return@mapNotNull null
            val name = parts[2].trim().ifBlank { host }
            if (host.isBlank()) null else SavedReceiver(name, host, port)
        }
    }

    fun rememberReceiver(context: Context, name: String, host: String, port: Int) {
        val h = host.trim()
        if (h.isBlank() || port !in 1..65535) return
        val cleanName = name.replace('\t', ' ').replace('\n', ' ').trim().ifBlank { h }
        val entry = SavedReceiver(cleanName, h, port)
        val merged = (listOf(entry) + recentReceivers(context).filter { it.id != entry.id }).take(6)
        val encoded = merged.joinToString("\n") { "${it.host}\t${it.port}\t${it.name}" }
        get(context).edit().putString(K_RECENT_RECEIVERS, encoded).apply()
    }

    fun removeRecentReceiver(context: Context, id: String) {
        val kept = recentReceivers(context).filter { it.id != id }
        val encoded = kept.joinToString("\n") { "${it.host}\t${it.port}\t${it.name}" }
        get(context).edit().putString(K_RECENT_RECEIVERS, encoded).apply()
    }

    // ---- 视频播放断点续播记忆 ----

    fun saveVideoProgress(context: Context, uri: String, positionMs: Long, durationMs: Long) {
        if (uri.isBlank()) return
        val p = progressPrefs(context)
        // 快播完（最后 5 秒内）或开头 3 秒内则清除断点
        if (positionMs < 3_000L || (durationMs > 0 && durationMs - positionMs < 5_000L)) {
            p.edit().remove(uri).apply()
            return
        }
        // 控制条目数量上限（最多 200 条）
        if (p.all.size > 200) {
            val edit = p.edit()
            p.all.keys.take(40).forEach { edit.remove(it) }
            edit.putLong(uri, positionMs).apply()
        } else {
            p.edit().putLong(uri, positionMs).apply()
        }
    }

    fun getVideoProgress(context: Context, uri: String): Long =
        if (uri.isBlank()) 0L else progressPrefs(context).getLong(uri, 0L)

    // ---- 视频浏览器的显示设置 ----

    fun browserSortField(context: Context): com.dsh.castkit.sender.media.VideoSortField = runCatching {
        com.dsh.castkit.sender.media.VideoSortField.valueOf(
            get(context).getString(K_SORT_FIELD, com.dsh.castkit.sender.media.VideoSortField.DATE.name)!!,
        )
    }.getOrDefault(com.dsh.castkit.sender.media.VideoSortField.DATE)

    fun setBrowserSortField(context: Context, v: com.dsh.castkit.sender.media.VideoSortField) {
        get(context).edit().putString(K_SORT_FIELD, v.name).apply()
    }

    fun browserSortAsc(context: Context): Boolean = get(context).getBoolean(K_SORT_ASC, false)

    fun setBrowserSortAsc(context: Context, v: Boolean) {
        get(context).edit().putBoolean(K_SORT_ASC, v).apply()
    }

    fun browserIconSize(context: Context): com.dsh.castkit.sender.media.BrowserIconSize = runCatching {
        com.dsh.castkit.sender.media.BrowserIconSize.valueOf(
            get(context).getString(K_ICON_SIZE, com.dsh.castkit.sender.media.BrowserIconSize.MEDIUM.name)!!,
        )
    }.getOrDefault(com.dsh.castkit.sender.media.BrowserIconSize.MEDIUM)

    fun setBrowserIconSize(context: Context, v: com.dsh.castkit.sender.media.BrowserIconSize) {
        get(context).edit().putString(K_ICON_SIZE, v.name).apply()
    }

    fun browserIncludeNoMedia(context: Context): Boolean = get(context).getBoolean(K_NOMEDIA, false)

    fun setBrowserIncludeNoMedia(context: Context, v: Boolean) {
        get(context).edit().putBoolean(K_NOMEDIA, v).apply()
    }

    /** 快捷投屏选中设备后，只更新目标地址（不动分辨率/码率等设置）。 */
    fun setTarget(context: Context, host: String, port: Int, name: String = "", auto: Boolean = true) {
        get(context).edit()
            .putString(K_HOST, host)
            .putInt(K_PORT, port)
            .putString(K_TARGET_NAME, name)
            .putBoolean(K_AUTO, auto)
            .apply()
        rememberReceiver(context, name, host, port)
    }

    /** 本机屏幕分辨率（失败时退回 1280×720）。 */
    @Suppress("DEPRECATION")
    fun nativeSize(context: Context): Pair<Int, Int> {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val dm = DisplayMetrics()
                wm.defaultDisplay.getRealMetrics(dm)
                dm.widthPixels to dm.heightPixels
            }
        } catch (_: Throwable) {
            1280 to 720
        }
    }
}
