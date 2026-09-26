package com.dsh.castkit.sender.media

import android.content.Context
import android.util.Log
import org.videolan.libvlc.LibVLC

/**
 * 进程内共享的 libVLC 实例。
 *
 * 为什么要共享而不是各建各的：本工程有**两个**地方要用 libVLC —— 播放器的兜底内核
 * （`LocalVideoPlayer`）和媒体信息/缩略图（`VlcMediaInfo`）。libVLC 实例本身虽然不便宜
 * （几 MB 常驻 + 一次模块初始化），但更要紧的是**同进程多实例是 VLC 不推荐、也最容易
 * 出玄学问题的用法**。所以统一从这里取。
 *
 * 生命周期：进程级，不主动释放 —— 它跟着进程走，页面销毁不需要也不应该把它放掉
 * （玩家返回列表再进播放页时还要用）。真要放掉只在进程退出时由系统回收。
 */
object VlcHolder {

    private const val TAG = "VlcHolder"

    /**
     * 启动参数。
     *
     * `--verbose=2` 是排查期留的：libVLC 的内部日志会进 logcat 的 `VLC` 标签，
     * 之前正是靠它才看出「vout 建好了、画面却全黑」其实是窗口尺寸给错。
     * 两个包都是 debug 构建，可诊断性比日志干净更值钱，所以先留着；
     * 要降噪就把它改成 `--verbose=1` 或删掉（唯一影响是以后排查少一层线索）。
     */
    private val ARGS = arrayListOf(
        "--verbose=2",
        "--no-drop-late-frames",
        "--no-skip-frames",
        "--network-caching=1500",
        "--file-caching=1500",
    )

    @Volatile
    private var instance: LibVLC? = null

    /** 初始化失败过一次就别再反复试（每次都要走一遍 native 初始化）。 */
    @Volatile
    private var failed = false

    fun get(context: Context): LibVLC? {
        instance?.let { return it }
        if (failed) return null
        synchronized(this) {
            instance?.let { return it }
            if (failed) return null
            return try {
                LibVLC(context.applicationContext, ARGS).also { instance = it }
            } catch (e: Throwable) {
                failed = true
                Log.w(TAG, "libVLC 初始化失败: ${e.message}")
                null
            }
        }
    }
}
