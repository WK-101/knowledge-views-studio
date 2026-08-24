package com.todocompanion.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.todocompanion.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Posts a notification when a scheduled reminder fires. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID) ?: return
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Task reminder"
        Notifications.show(context, taskId.hashCode(), title, "Reminder")
    }
}

/** Re-schedules all reminders after the device reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AlarmScheduler.rescheduleAll(context, app.repository)
            } finally {
                pending.finish()
            }
        }
    }
}
