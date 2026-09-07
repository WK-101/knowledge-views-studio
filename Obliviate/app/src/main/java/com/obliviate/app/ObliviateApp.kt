package com.obliviate.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.obliviate.app.core.service.WipeService

class ObliviateApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            WipeService.CHANNEL_ID,
            getString(R.string.wipe_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.wipe_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
