package com.dsh.castkit.sender.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CastPhase { IDLE, CONNECTING, RUNNING, RECONNECTING, ERROR }

/** 投屏方式：镜像整屏，或把选中的视频文件交给接收端原生播放。 */
enum class CastMode { MIRROR, VIDEO }

data class CastState(
    val phase: CastPhase = CastPhase.IDLE,
    val mode: CastMode = CastMode.MIRROR,
    val message: String = "",
    val host: String = "",
    val port: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrateBps: Int = 0,
    val measuredKbps: Int = 0,
    val measuredFps: Float = 0f,
    /**
     * 应用内本地播放器是否全屏播放中。播放器里的「本机旋转」只改手机自己的方向，
     * 镜像投屏必须据此**停止跟随**本机旋转，否则会把接收端画面一起转过去。
     */
    val localPlayerActive: Boolean = false,
    // ---- 投视频文件时接收端回报的播放状态（发送端进度条据此同步）----
    val remotePositionMs: Long = 0,
    val remoteDurationMs: Long = 0,
    val remotePlaying: Boolean = false,
    val remoteBuffering: Boolean = false,
)

/** 进程内单例状态，Service 写、Activity 读（避免 Binder 样板代码）。 */
object CastBus {
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state

    fun update(block: (CastState) -> CastState) {
        _state.value = block(_state.value)
    }

    fun reset() {
        _state.value = CastState()
    }
}
