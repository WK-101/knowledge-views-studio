package com.todocompanion.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.TaskEntity

/** Schedules local exact alarms for reminders. No network involved. */
object AlarmScheduler {

    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_REMINDER_ID = "reminderId"

    fun triggerTimeFor(reminder: ReminderEntity, task: TaskEntity): Long? {
        val offset = (reminder.offsetMin ?: 0) * 60_000L
        return when (reminder.type) {
            "absolute" -> reminder.atTime
            "relativeToDue" -> task.dueDate?.minus(offset)
            "relativeToStart" -> task.startDate?.minus(offset)
            else -> null
        }
    }

    private fun pendingIntent(context: Context, reminder: ReminderEntity, task: TaskEntity): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TASK_ID, task.id)
            putExtra(EXTRA_TITLE, task.title)
            putExtra(EXTRA_REMINDER_ID, reminder.id)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun schedule(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        if (task.completed) return
        val at = triggerTimeFor(reminder, task) ?: return
        if (at <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, reminder, task)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context, reminder, task))
    }

    /** Reschedule every stored reminder (e.g. after reboot). */
    suspend fun rescheduleAll(context: Context, repo: AppRepository) {
        val reminders = repo.allRemindersOnce()
        for (r in reminders) {
            val task = repo.getTask(r.taskId) ?: continue
            schedule(context, r, task)
        }
    }
}
