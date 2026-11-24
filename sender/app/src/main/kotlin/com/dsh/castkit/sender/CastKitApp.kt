package com.dsh.castkit.sender

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CastKitApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
