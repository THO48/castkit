package com.dsh.castkit.sender.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/** 自动发现的接收端。 */
data class DiscoveredReceiver(
    val name: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long = System.currentTimeMillis(),
) {
    val id: String get() = "$host:$port"
    val label: String get() = if (host.isNotEmpty()) "$name（$host:$port）" else name
}

/**
 * LANCast v1 发现协议（见 docs/LANCast-v1.md §5）：
 *   发送端 -> 255.255.255.255:8124（以及各网卡子网广播地址）  "LCAST1-PROBE\n"
 *   接收端 -> 单播回发送端                                    "LCAST1-HERE <tcpPort> <name>\n"
 *   接收端还会每 5 秒广播一次 HERE，便于先启动的发送端直接看到。
 *
 * 发送端侧实现：同时做「探测」和「被动监听」，15 秒没消息就从列表里摘掉。
 */
class LanCastDiscovery(private val context: Context) {

    private val _receivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val receivers: StateFlow<List<DiscoveredReceiver>> = _receivers.asStateFlow()

    private var socket: DatagramSocket? = null
    private var listenThread: Thread? = null
    private var probeThread: Thread? = null
    private var cleanupThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(0))
            }
        } catch (e: Exception) {
            Log.w(TAG, "发现套接字创建失败", e)
            running = false
            return
        }

        listenThread = Thread({ listenLoop() }, "lancast-discover-listen").apply {
            isDaemon = true
            start()
        }
        probeThread = Thread({ probeLoop() }, "lancast-discover-probe").apply {
            isDaemon = true
            start()
        }
        cleanupThread = Thread({ cleanupLoop() }, "lancast-discover-cleanup").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        listenThread = null
        probeThread = null
        cleanupThread = null
        releaseMulticastLock()
        _receivers.value = emptyList()
    }

    /** 立即探测一次（用户点「重新搜索」时用）。 */
    fun probeNow() {
        Thread({ sendProbe() }, "lancast-discover-probe-once").apply { isDaemon = true; start() }
    }

    private fun listenLoop() {
        val buf = ByteArray(512)
        while (running) {
            val s = socket ?: break
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                if (!text.startsWith(LanCast.HERE)) continue
                val rest = text.removePrefix(LanCast.HERE).trim()
                val portStr = rest.substringBefore(' ').trim()
                val port = portStr.toIntOrNull() ?: continue
                val name = rest.substringAfter(' ', "").trim().ifBlank { "CastKit 接收端" }
                val host = packet.address?.hostAddress ?: continue
                addOrRefresh(name, host, port)
            } catch (e: Exception) {
                if (running) Log.d(TAG, "监听结束: ${e.message}")
                break
            }
        }
    }

    private fun addOrRefresh(name: String, host: String, port: Int) {
        val entry = DiscoveredReceiver(name, host, port)
        val current = _receivers.value
        val existing = current.firstOrNull { it.id == entry.id }
        _receivers.value = if (existing == null) {
            (current + entry).sortedBy { it.name }
        } else {
            current.map { if (it.id == entry.id) entry else it }
        }
    }

    private fun probeLoop() {
        while (running) {
            sendProbe()
            try {
                Thread.sleep(PROBE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun sendProbe() {
        val s = socket ?: return
        val payload = (LanCast.PROBE + "\n").toByteArray(Charsets.UTF_8)
        for (addr in broadcastTargets()) {
            try {
                s.send(DatagramPacket(payload, payload.size, addr, LanCast.DISCOVERY_PORT))
            } catch (e: Exception) {
                Log.d(TAG, "探测 $addr 失败: ${e.message}")
            }
        }
    }

    private fun cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            val kept = _receivers.value.filter { now - it.lastSeenMs < EXPIRY_MS }
            if (kept.size != _receivers.value.size) _receivers.value = kept
        }
    }

    /** 全局广播地址 + 各网卡的子网广播地址。 */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        try {
            targets += InetAddress.getByName("255.255.255.255")
        } catch (_: Exception) {
        }
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    ia.broadcast?.let { targets += it }
                }
            }
        } catch (_: Exception) {
        }
        return targets.toList()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("castkit-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.d(TAG, "组播锁获取失败（不影响基本发现）: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private companion object {
        const val TAG = "LanCastDiscovery"
        const val PROBE_INTERVAL_MS = 3000L
        const val EXPIRY_MS = 15_000L
    }
}
