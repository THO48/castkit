package com.dsh.castkit.sender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.os.Build
import com.dsh.castkit.sender.debug.MainThreadWatchdog

class CastKitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 主线程卡顿看门狗：只在可调试包里跑（帧统计看不到主线程阻塞，见该类注释）
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) MainThreadWatchdog.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "castkit_cast"
    }
}
