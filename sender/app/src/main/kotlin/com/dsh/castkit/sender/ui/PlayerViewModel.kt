package com.dsh.castkit.sender.ui

import android.app.Application
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import androidx.lifecycle.AndroidViewModel
import com.dsh.castkit.sender.media.LocalPlaybackState
import com.dsh.castkit.sender.media.LocalVideoPlayer
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放页的状态。
 *
 * 这里做的是**真正的状态提升**（Q13=C）：`LocalVideoPlayer` 实例原来建在
 * `remember { LocalVideoPlayer(context) }` 里——页面一重建（横竖屏切换、
 * 进程内配置变更）实例就没了，`MediaPlayer` 随之泄漏或被打断。
 * 现在它归 ViewModel 所有，生命周期与 Activity 对齐。
 *
 * 播放**引擎**不动：仍然是系统 `MediaPlayer`（不引入 ExoPlayer / Media3），
 * 遥控器状态机、每秒回报、时长兜底全部在别处，这里只做所有权转移与转发。
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val player = LocalVideoPlayer(app.applicationContext)

    val state: StateFlow<LocalPlaybackState> = player.state

    fun play(uri: Uri, title: String) = player.play(uri, title)

    fun setSurface(surface: Surface?) = player.setSurface(surface)

    /** libVLC 的 vout 需要渲染区域的实际像素尺寸，只有 SurfaceHolder 拿得到。 */
    fun setSurfaceHolder(holder: SurfaceHolder) = player.setSurfaceHolder(holder)

    fun toggle() = player.toggle()

    fun pause() = player.pause()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** 长按快进：按下传 3.0，松手传回 1.0。 */
    fun setSpeed(rate: Float) = player.setSpeed(rate)

    fun release() = player.release()

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
