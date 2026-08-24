package com.todocompanion.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.todocompanion.app.MainActivity

object Notifications {
    const val CHANNEL_ID = "reminders"
    const val SUMMARY_ID = 424242

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Reminders and the daily summary" }
                )
            }
        }
    }

    private fun broadcast(context: Context, action: String, reqCode: Int, extras: Map<String, Any?>): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(action)
        extras.forEach { (k, v) -> when (v) { is String -> i.putExtra(k, v); is Boolean -> i.putExtra(k, v); null -> {} } }
        return PendingIntent.getBroadcast(context, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(context: Context, taskId: String, title: String, reminderId: String, annoying: Boolean) {
        ensureChannel(context)
        val done = broadcast(context, AlarmScheduler.ACTION_DONE, ("done$taskId").hashCode(),
            mapOf(AlarmScheduler.EXTRA_TASK_ID to taskId))
        val snooze = broadcast(context, AlarmScheduler.ACTION_SNOOZE, ("snz$reminderId").hashCode(),
            mapOf(AlarmScheduler.EXTRA_TASK_ID to taskId, AlarmScheduler.EXTRA_TITLE to title,
                AlarmScheduler.EXTRA_REMINDER_ID to reminderId, AlarmScheduler.EXTRA_ANNOYING to annoying))
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText("Reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Done", done)
            .addAction(0, "Snooze 10m", snooze)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(taskId.hashCode(), n) }
    }

    fun cancel(context: Context, taskId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(taskId.hashCode()) }
    }

    const val FOCUS_ID = 424243

    fun showFocusDone(context: Context) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Focus session complete")
            .setContentText("Nice work — time for a break.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(FOCUS_ID, n) }
    }

    fun showSummary(context: Context, dueToday: Int) {
        ensureChannel(context)
        val text = if (dueToday == 0) "No tasks due today — enjoy!" else "You have $dueToday task${if (dueToday == 1) "" else "s"} due today."
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Today")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_ID, n) }
    }
}
