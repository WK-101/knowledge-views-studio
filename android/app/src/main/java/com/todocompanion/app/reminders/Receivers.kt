package com.todocompanion.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.todocompanion.app.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** Handles reminder fire / snooze / done and the daily summary. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App
        val taskId = intent.getStringExtra(AlarmScheduler.EXTRA_TASK_ID)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "Task reminder"
        val reminderId = intent.getStringExtra(AlarmScheduler.EXTRA_REMINDER_ID) ?: (taskId ?: "")
        val annoying = intent.getBooleanExtra(AlarmScheduler.EXTRA_ANNOYING, false)

        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                if (app == null || taskId == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val task = app.repository.getTask(taskId)
                        if (task != null && !task.completed && !task.trashed && !task.abandoned) {
                            Notifications.show(context, taskId, title, reminderId, annoying)
                            if (annoying) AlarmScheduler.scheduleFireIn(context, taskId, title, reminderId, true, 15)
                        }
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_SNOOZE -> {
                if (taskId != null) {
                    Notifications.cancel(context, taskId)
                    AlarmScheduler.scheduleFireIn(context, taskId, title, reminderId, annoying, 10)
                }
            }

            AlarmScheduler.ACTION_DONE -> {
                if (app == null || taskId == null) return
                Notifications.cancel(context, taskId)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { app.repository.setCompletedById(taskId, true) } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_SUMMARY -> {
                if (app == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val zone = ZoneId.systemDefault()
                        val today = Instant.now().atZone(zone).toLocalDate()
                        val count = app.repository.allTasksOnce().count {
                            !it.completed && !it.trashed && !it.abandoned && it.dueDate != null &&
                                !Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate().isAfter(today)
                        }
                        Notifications.showSummary(context, count)
                        val s = app.repository.settingsSnapshot()
                        if (s.dailySummaryEnabled) AlarmScheduler.scheduleDailySummary(context, s.dailySummaryHour, s.dailySummaryMinute)
                    } finally { pending.finish() }
                }
            }
        }
    }
}

/** Re-schedules reminders and the daily summary after a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AlarmScheduler.rescheduleAll(context, app.repository)
                val s = app.repository.settingsSnapshot()
                if (s.dailySummaryEnabled) AlarmScheduler.scheduleDailySummary(context, s.dailySummaryHour, s.dailySummaryMinute)
            } finally { pending.finish() }
        }
    }
}
