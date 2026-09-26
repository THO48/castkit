package com.dsh.castkit.sender.media

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

/** 一个视频能不能被这台设备播放。 */
sealed interface Playability {
    /** 有视频轨，且系统里有能解它的解码器。 */
    data object Ok : Playability

    /**
     * 容器/编码在安卓平台上**没有解码器**。
     * 典型：WMV / VC-1 / WMV3 / RMVB / RealVideo —— 这些从来没有进过 Android 的
     * MediaCodec 支持列表，MediaPlayer 只会给一个 `error (1, -2147483648)`。
     */
    data class Unsupported(val mime: String) : Playability

    /** 文件打不开、没有视频轨、或根本不是媒体文件。 */
    data class Broken(val detail: String) : Playability
}

/**
 * 播放前预检：别再让"格式不支持"表现成一个裸错误码。
 *
 * 背景（实测）：消费者常见的 `.wmv`（WMV2/WMV3/VC-1 + WMA）在 Android 上**无法解码**——
 * 平台解码器列表里只有 avc/hevc/av01/vp8/vp9/mp4v/3gpp，没有 wmv 系列。因此
 *   - 发送端本地播放会走到 `MediaPlayer` 的 error 回调；
 *   - 投屏则更糟：接收端拿到的是一个它同样解不了的流，用户只看到"投送已结束"。
 * 与其让用户在两个设备上各失败一次，不如在点播/点投的瞬间就说清楚原因。
 *
 * 注意这是**尽力而为**的检查：`findDecoderForFormat` 会按 MIME +（若存在）profile/level
 * 匹配解码器能力，但没有解码器时它一定能查出来，而"H.264 level 偏高但设备其实能软解"
 * 这类边缘情况可能漏判或误判。所以它只用来**提前给出更友好的提示**，
 * 不作为硬性拦截——真正的判定仍以 MediaPlayer 的 error 回调为准。
 */
object PlayabilityChecker {

    private const val TAG = "PlayabilityChecker"

    fun check(context: Context, uri: Uri): Playability {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var video: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    video = f
                    break
                }
            }
            val format = video ?: return Playability.Broken("文件里没有视频轨")
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return Playability.Broken("视频轨缺少 MIME")
            val decoder = runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            }.getOrNull()
            if (decoder == null) {
                Log.i(TAG, "没有能解码 $mime 的解码器（$uri）")
                Playability.Unsupported(mime)
            } else {
                Log.d(TAG, "$mime -> $decoder")
                Playability.Ok
            }
        } catch (e: Throwable) {
            Log.w(TAG, "预检失败: ${e.message}")
            Playability.Broken(e.message ?: "无法读取该文件")
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * 只有在**确定**没法播时才返回提示语，用来**拦住**这次播放。
     *
     * 目前只拦 [Playability.Unsupported]：`findDecoderForFormat` 返回 null 意味着系统里
     * 确实一个能解这个 MIME 的解码器都没有，这是可靠结论。
     * [Playability.Broken]（提取器读不出来）**不拦**——那是"尽力而为"的判断，
     * 误判的话会把本来能播的文件挡掉，让它继续走 MediaPlayer、由播放器自己报错更稳妥；
     * 这种情况请用 [logMessage] 记日志。
     */
    fun blockReason(result: Playability): String? = when (result) {
        is Playability.Ok -> null
        is Playability.Unsupported ->
            "这台设备没有 ${friendly(result.mime)} 的解码器，换个 MP4（H.264/H.265）或 MKV 再试"
        is Playability.Broken -> null
    }

    /** 需要记日志的说明（含不拦的情况）；一切正常返回 null。 */
    fun logMessage(result: Playability): String? = when (result) {
        is Playability.Ok -> null
        is Playability.Unsupported -> "没有解码器：${result.mime}"
        is Playability.Broken -> "预检读不出文件（不拦，交给播放器）：${result.detail}"
    }

    private fun friendly(mime: String): String = when (mime) {
        "video/x-ms-wmv", "video/x-ms-wm", "video/x-ms-asf" -> "WMV"
        "video/x-msvideo" -> "AVI(某些编码)"
        "video/vnd.rn-realvideo", "application/vnd.rn-realmedia-vbr" -> "RM/RMVB"
        "video/mpeg" -> "MPEG-1/2"
        "video/x-flv" -> "FLV"
        else -> mime
    }
}
