package com.dsh.castkit.sender.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.dsh.castkit.sender.CastConfig
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.ResolutionPreset
import com.dsh.castkit.sender.net.DiscoveredReceiver

/**
 * 投屏设置页的状态。
 *
 * 状态提升的范围（Q13=C）：把原来散在 `CastTab` 里的一堆 `remember { mutableStateOf }`
 * 搬进 ViewModel ——配置变更或页面重建后不再丢失。
 *
 * **不搬**进程级状态：`CastBus`（要被两个 Service 写）、`LanCastDiscovery`（挂在 Activity 上、
 * 与生命周期绑定）、`Prefs`（SharedPreferences 门面）。VM 只负责读写它们，不持有所有权。
 *
 * CUSTOM 已移除（设计系统 §10 偏差 7），所以这里不再有 customW / customH 两个状态；
 * `persist()` 仍把存量值原样写回，避免动到 `Prefs` 的存储格式。
 */
class CastViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    var preset by mutableStateOf(Prefs.preset(context))
        private set

    var fps by mutableIntStateOf(Prefs.fps(context).takeIf { it > 0 } ?: Prefs.DEF_FPS)
        private set

    var mbps by mutableIntStateOf(Prefs.mbps(context))
        private set

    var keepAspect by mutableStateOf(Prefs.keepAspect(context))
        private set

    var host by mutableStateOf(Prefs.host(context))
        private set

    var portText by mutableStateOf(Prefs.port(context).toString())
        private set

    /** 「手动输入地址」区块是否展开。没自动发现过设备时默认展开（沿用原逻辑）。 */
    var showManual by mutableStateOf(
        Prefs.host(context).isBlank() && !Prefs.autoDiscovered(context),
    )
        private set

    var selectedId by mutableStateOf<String?>(null)
        private set

    /** `MainActivity` 传进来的初始配置只应用一次。 */
    private var initialApplied = false

    fun applyInitial(initial: CastConfig) {
        if (initialApplied) return
        initialApplied = true
        if (initial.fps > 0) fps = initial.fps
        if (initial.host.isNotBlank()) host = initial.host
        if (initial.port > 0) portText = initial.port.toString()
    }

    fun selectPreset(value: ResolutionPreset) {
        preset = value
        persist()
    }

    fun selectFps(value: Int) {
        fps = value
        persist()
    }

    fun selectMbps(value: Int) {
        mbps = value.coerceIn(1, 20)
        persist()
    }

    /**
     * 拖动码率滑块时用的"草稿"写入：**不落盘**。
     *
     * M3 的 `Slider` 有 `onValueChangeFinished`，所以可以只在松手时 `persist()`；
     * 改造前用的是 Miuix `Slider`（没有这个回调），只能每一帧都写一次
     * SharedPreferences —— 拖一次滑块会写几十次。
     */
    fun setMbpsDraft(value: Int) {
        mbps = value.coerceIn(1, 20)
    }

    // 注意命名：这几个不能叫 `setXxx` —— 上面的 `var xxx` 已经生成了同名的
    // private setter，Kotlin 会因为 JVM 签名冲突（platform declaration clash）直接编译失败。
    fun applyKeepAspect(value: Boolean) {
        keepAspect = value
        persist()
    }

    fun applyHost(value: String) {
        host = value
        Prefs.markAutoDiscovered(context, false)
        persist()
    }

    fun applyPort(value: String) {
        portText = value.filter { it.isDigit() }.take(5)
        Prefs.markAutoDiscovered(context, false)
        persist()
    }

    fun toggleManual() {
        showManual = !showManual
    }

    fun selectReceiver(receiver: DiscoveredReceiver) {
        selectedId = receiver.id
        host = receiver.host
        portText = receiver.port.toString()
        Prefs.markAutoDiscovered(context, true)
        persist()
    }

    /**
     * 自动选中：没有选中项（或选中的设备消失了）就选第一个发现的设备。
     *
     * 由页面在 `LaunchedEffect(found)` 里调用——保持"发现结果一变就重新评估"的原语义。
     */
    fun autoSelectIfNeeded(found: List<DiscoveredReceiver>) {
        if (selectedId != null && found.any { it.id == selectedId }) return
        val first = found.firstOrNull() ?: return
        selectReceiver(first)
    }

    fun persist() {
        Prefs.persistConfig(
            context = context,
            preset = preset,
            fps = fps,
            mbps = mbps,
            host = host.trim(),
            port = portText.toIntOrNull() ?: Prefs.DEF_PORT,
            keepAspect = keepAspect,
        )
    }
}
