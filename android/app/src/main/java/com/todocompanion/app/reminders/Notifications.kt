package com.todocompanion.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL_ID = "reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Task reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Reminders for your tasks" }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    fun show(context: Context, notificationId: Int, title: String, text: String?) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text ?: "Reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        // POST_NOTIFICATIONS is checked by the system; notify() is a no-op if denied.
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, builder.build()) }
    }
}
