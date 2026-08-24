package com.todocompanion.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.TaskEntity
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Schedules local exact alarms for reminders and the daily summary. No network involved. */
object AlarmScheduler {

    const val ACTION_FIRE = "com.todocompanion.app.action.FIRE"
    const val ACTION_SNOOZE = "com.todocompanion.app.action.SNOOZE"
    const val ACTION_DONE = "com.todocompanion.app.action.DONE"
    const val ACTION_SUMMARY = "com.todocompanion.app.action.SUMMARY"
    const val ACTION_FOCUS_DONE = "com.todocompanion.app.action.FOCUS_DONE"

    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_REMINDER_ID = "reminderId"
    const val EXTRA_ANNOYING = "annoying"

    private const val SUMMARY_REQ = 918_273

    fun triggerTimeFor(reminder: ReminderEntity, task: TaskEntity): Long? {
        val offset = (reminder.offsetMin ?: 0) * 60_000L
        return when (reminder.type) {
            "absolute" -> reminder.atTime
            "relativeToDue" -> task.dueDate?.minus(offset)
            "relativeToStart" -> task.startDate?.minus(offset)
            else -> null
        }
    }

    private fun broadcast(context: Context, action: String, requestCode: Int, extras: Map<String, Any?>): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(action)
        extras.forEach { (k, v) ->
            when (v) {
                is String -> intent.putExtra(k, v)
                is Boolean -> intent.putExtra(k, v)
                null -> {}
            }
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun setAlarm(context: Context, atMillis: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    private fun fireExtras(taskId: String, title: String, reminderId: String, annoying: Boolean) =
        mapOf(EXTRA_TASK_ID to taskId, EXTRA_TITLE to title, EXTRA_REMINDER_ID to reminderId, EXTRA_ANNOYING to annoying)

    fun schedule(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        if (task.completed || task.trashed || task.abandoned) return
        val at = triggerTimeFor(reminder, task) ?: return
        if (at <= System.currentTimeMillis()) return
        val pi = broadcast(context, ACTION_FIRE, reminder.id.hashCode(), fireExtras(task.id, task.title, reminder.id, reminder.annoying))
        setAlarm(context, at, pi)
    }

    fun cancel(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_FIRE, reminder.id.hashCode(), fireExtras(task.id, task.title, reminder.id, reminder.annoying)))
    }

    /** Re-fire a reminder after [delayMin] minutes (snooze / annoying repeat). */
    fun scheduleFireIn(context: Context, taskId: String, title: String, reminderId: String, annoying: Boolean, delayMin: Long) {
        val pi = broadcast(context, ACTION_FIRE, reminderId.hashCode(), fireExtras(taskId, title, reminderId, annoying))
        setAlarm(context, System.currentTimeMillis() + delayMin * 60_000L, pi)
    }

    suspend fun rescheduleAll(context: Context, repo: AppRepository) {
        repo.allRemindersOnce().forEach { r ->
            val task = repo.getTask(r.taskId) ?: return@forEach
            schedule(context, r, task)
        }
    }

    // ---------- daily summary ----------
    fun scheduleDailySummary(context: Context, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()) {
        val now = System.currentTimeMillis()
        var next = LocalDate.now(zone).atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
            .atZone(zone).toInstant().toEpochMilli()
        if (next <= now) next += 86_400_000L
        setAlarm(context, next, broadcast(context, ACTION_SUMMARY, SUMMARY_REQ, emptyMap()))
    }

    fun cancelDailySummary(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_SUMMARY, SUMMARY_REQ, emptyMap()))
    }

    private const val FOCUS_REQ = 918_274

    /** Fire a "focus complete" notification at [atMillis] even if the app is backgrounded. */
    fun scheduleFocusDone(context: Context, atMillis: Long) {
        if (atMillis <= System.currentTimeMillis()) return
        setAlarm(context, atMillis, broadcast(context, ACTION_FOCUS_DONE, FOCUS_REQ, emptyMap()))
    }

    fun cancelFocusDone(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_FOCUS_DONE, FOCUS_REQ, emptyMap()))
    }
}
