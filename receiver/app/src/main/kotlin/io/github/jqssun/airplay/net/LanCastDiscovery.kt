package io.github.jqssun.airplay.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * LANCast v1 发现协议的服务端（见 docs/LANCast-v1.md §5）：
 *   - 收到 `LCAST1-PROBE` 时单播回 `LCAST1-HERE <tcpPort> <name>`
 *   - 另外每 5 秒向 255.255.255.255:8124 广播一次 HERE，方便先启动的发送端直接看到
 *
 * 只在「安卓投屏接收」开启时运行；持一个组播锁，避免 Wi-Fi 省电把广播过滤掉。
 */
class LanCastDiscovery(
    private val context: Context,
    private val portProvider: () -> Int,
    private val nameProvider: () -> String,
    private val onLog: (String) -> Unit = {},
) {
    private var socket: DatagramSocket? = null
    private var listenThread: Thread? = null
    private var announceThread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        running = true
        acquireMulticastLock()
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LanCast.DISCOVERY_PORT))
            }
        } catch (e: Exception) {
            log("发现套接字绑定失败（${LanCast.DISCOVERY_PORT}）：${e.message}")
            running = false
            return
        }
        listenThread = Thread({ listenLoop() }, "lancast-discover").apply {
            isDaemon = true
            start()
        }
        announceThread = Thread({ announceLoop() }, "lancast-announce").apply {
            isDaemon = true
            start()
        }
        log("已开启投屏自动发现（UDP ${LanCast.DISCOVERY_PORT}）")
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        listenThread = null
        announceThread = null
        releaseMulticastLock()
    }

    private fun listenLoop() {
        val buf = ByteArray(512)
        while (running) {
            val s = socket ?: break
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                when {
                    text.startsWith(LanCast.PROBE) -> replyHere(packet.address, packet.port)
                    // 其它发送端可能在广播探测/应答，忽略
                    else -> Unit
                }
            } catch (e: Exception) {
                if (running) Log.d(TAG, "发现监听结束: ${e.message}")
                break
            }
        }
    }

    private fun announceLoop() {
        // 首次立即广播一次，之后每 5 秒一次（包很小，可忽略耗电）
        while (running) {
            try {
                replyHere(InetAddress.getByName("255.255.255.255"), LanCast.DISCOVERY_PORT)
            } catch (e: Exception) {
                Log.d(TAG, "广播失败: ${e.message}")
            }
            try {
                Thread.sleep(ANNOUNCE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun replyHere(address: InetAddress?, port: Int) {
        val s = socket ?: return
        val tcpPort = runCatching { portProvider() }.getOrDefault(LanCast.DEFAULT_PORT)
        val name = runCatching { nameProvider() }.getOrDefault("CastKit 接收端")
            .replace('\n', ' ')
            .trim()
            .ifBlank { "CastKit 接收端" }
        val payload = "${LanCast.HERE} $tcpPort $name\n".toByteArray(Charsets.UTF_8)
        try {
            s.send(DatagramPacket(payload, payload.size, address ?: InetAddress.getByName("255.255.255.255"), port))
        } catch (e: Exception) {
            Log.d(TAG, "回应发现请求失败: ${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("castkit-announce").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.d(TAG, "组播锁获取失败: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg)
    }

    private companion object {
        const val TAG = "LanCastDiscovery"
        const val ANNOUNCE_INTERVAL_MS = 5000L
    }
}
