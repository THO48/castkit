package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * mDNS（Bonjour）广播：`_raop._tcp` + `_airplay._tcp`。
 *
 * CastKit: 注册结果与 TXT 记录会通过 [onLog] 进到 App 日志（并镜像到 Download/castkit-receiver-logs.txt），
 * 便于排查"iPhone 看不到设备"这类问题。
 */
class NsdServiceManager(
    private val ctx: Context,
    private val onLog: ((String) -> Unit)? = null,
) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopRegistration: NsdManager.RegistrationListener? = null
    private var airplayRegistration: NsdManager.RegistrationListener? = null
    private var infoServiceRegistration: NsdManager.RegistrationListener? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun txtSummary(txtRecords: Map<String, String>): String =
        txtRecords.entries.joinToString(" ") { (k, v) -> "$k=$v" }

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun registerRaop(serviceName: String, port: Int, txtRecords: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_raop._tcp"
            this.port = port
            txtRecords.forEach { (k, v) -> setAttribute(k, v) }
        }
        log("广播 _raop._tcp \"$serviceName\" port=$port TXT[${txtSummary(txtRecords)}]")

        raopRegistration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log("_raop._tcp 注册成功：${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                log("_raop._tcp 注册失败 code=$code（iPhone 可能看不到本设备）")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                log("_raop._tcp 已注销")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                log("_raop._tcp 注销失败 code=$code")
            }
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, raopRegistration)
        } catch (e: Exception) {
            log("_raop._tcp 注册异常：${e.message}")
        }
    }

    fun registerAirplay(serviceName: String, port: Int, txtRecords: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_airplay._tcp"
            this.port = port
            txtRecords.forEach { (k, v) -> setAttribute(k, v) }
        }
        log("广播 _airplay._tcp \"$serviceName\" port=$port TXT[${txtSummary(txtRecords)}]")

        airplayRegistration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log("_airplay._tcp 注册成功：${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                log("_airplay._tcp 注册失败 code=$code（iPhone 屏幕镜像列表里可能看不到本设备）")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                log("_airplay._tcp 已注销")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                log("_airplay._tcp 注销失败 code=$code")
            }
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, airplayRegistration)
        } catch (e: Exception) {
            log("_airplay._tcp 注册异常：${e.message}")
        }
    }

    fun unregisterAll() {
        raopRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            raopRegistration = null
        }
        airplayRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            airplayRegistration = null
        }
        infoServiceRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            infoServiceRegistration = null
        }
    }

    fun release() {
        unregisterAll()
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
    }
}
