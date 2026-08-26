package com.todocompanion.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.ReminderEntity
import com.todocompanion.app.data.entity.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Schedules local exact alarms for reminders and the daily summary. No network involved. */
object AlarmScheduler {

    const val ACTION_FIRE = "com.todocompanion.app.action.FIRE"
    const val ACTION_SNOOZE = "com.todocompanion.app.action.SNOOZE"
    const val ACTION_DONE = "com.todocompanion.app.action.DONE"
    const val ACTION_SUMMARY = "com.todocompanion.app.action.SUMMARY"
    const val ACTION_EVENING = "com.todocompanion.app.action.EVENING"
    const val ACTION_AUTO_BACKUP = "com.todocompanion.app.action.AUTO_BACKUP"
    const val ACTION_FOCUS_DONE = "com.todocompanion.app.action.FOCUS_DONE"
    const val ACTION_TRACK_PROMPT = "com.todocompanion.app.action.TRACK_PROMPT"
    const val ACTION_HABIT = "com.todocompanion.app.action.HABIT"
    const val ACTION_HABIT_DONE = "com.todocompanion.app.action.HABIT_DONE"
    const val ACTION_HABIT_SNOOZE = "com.todocompanion.app.action.HABIT_SNOOZE"

    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_REMINDER_ID = "reminderId"
    const val EXTRA_ANNOYING = "annoying"
    const val EXTRA_ESCALATE = "escalate"
    const val EXTRA_STEP = "step"
    const val EXTRA_HABIT_ID = "habitId"
    const val EXTRA_HABIT_NAME = "habitName"
    const val EXTRA_HABIT_MIN = "habitMin"

    private const val SUMMARY_REQ = 918_273
    private const val EVENING_REQ = 918_275
    private const val AUTOBACKUP_REQ = 918_277

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
                is Int -> intent.putExtra(k, v)
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

    private fun fireExtras(taskId: String, title: String, reminderId: String, annoying: Boolean, escalate: Boolean = false, step: Int = 0) =
        mapOf(EXTRA_TASK_ID to taskId, EXTRA_TITLE to title, EXTRA_REMINDER_ID to reminderId,
            EXTRA_ANNOYING to annoying, EXTRA_ESCALATE to escalate, EXTRA_STEP to step)

    fun schedule(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        if (task.completed || task.trashed || task.abandoned) return
        val at = triggerTimeFor(reminder, task) ?: return
        if (at <= System.currentTimeMillis()) return
        val pi = broadcast(context, ACTION_FIRE, reminder.id.hashCode(), fireExtras(task.id, task.title, reminder.id, reminder.annoying, reminder.escalate))
        setAlarm(context, at, pi)
    }

    fun cancel(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_FIRE, reminder.id.hashCode(), fireExtras(task.id, task.title, reminder.id, reminder.annoying, reminder.escalate)))
    }

    /** Re-fire a reminder after [delayMin] minutes (snooze / annoying repeat / escalation). */
    fun scheduleFireIn(context: Context, taskId: String, title: String, reminderId: String, annoying: Boolean, delayMin: Long, escalate: Boolean = false, step: Int = 0) {
        val pi = broadcast(context, ACTION_FIRE, reminderId.hashCode(), fireExtras(taskId, title, reminderId, annoying, escalate, step))
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

    // ---------- evening review ----------
    fun scheduleEveningReview(context: Context, hour: Int, zone: ZoneId = ZoneId.systemDefault()) {
        val now = System.currentTimeMillis()
        var next = LocalDate.now(zone).atTime(LocalTime.of(hour.coerceIn(0, 23), 0))
            .atZone(zone).toInstant().toEpochMilli()
        if (next <= now) next += 86_400_000L
        setAlarm(context, next, broadcast(context, ACTION_EVENING, EVENING_REQ, emptyMap()))
    }

    fun cancelEveningReview(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_EVENING, EVENING_REQ, emptyMap()))
    }

    // ---------- automatic backup ----------
    fun scheduleAutoBackup(context: Context, hour: Int, zone: ZoneId = ZoneId.systemDefault()) {
        val now = System.currentTimeMillis()
        var next = LocalDate.now(zone).atTime(LocalTime.of(hour.coerceIn(0, 23), 0)).atZone(zone).toInstant().toEpochMilli()
        if (next <= now) next += 86_400_000L
        setAlarm(context, next, broadcast(context, ACTION_AUTO_BACKUP, AUTOBACKUP_REQ, emptyMap()))
    }
    fun cancelAutoBackup(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_AUTO_BACKUP, AUTOBACKUP_REQ, emptyMap()))
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

    // ---------- U2: timebox → track prompt ----------
    private fun trackReq(taskId: String): Int = (("tp:$taskId").hashCode() and 0x3FFFFFFF) + 2_000_000

    /** Schedule a "start tracking?" prompt at the start of each of today's timed (non-all-day) task
     *  blocks still in the future. Idempotent — reschedule at startup and after any task change. */
    suspend fun scheduleTrackPrompts(context: Context, repo: AppRepository, zone: ZoneId = ZoneId.systemDefault()) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        repo.allTasksOnce().forEach { t ->
            if (t.completed || t.trashed || t.abandoned || t.isNote || t.isAllDay) return@forEach
            val due = t.dueDate ?: return@forEach
            if (due <= now) return@forEach
            if (Instant.ofEpochMilli(due).atZone(zone).toLocalDate() != today) return@forEach
            setAlarm(context, due, broadcast(context, ACTION_TRACK_PROMPT, trackReq(t.id),
                mapOf(EXTRA_TASK_ID to t.id, EXTRA_TITLE to t.title)))
        }
    }

    // ---------- habit reminders ----------
    private fun habitReqCode(habitId: String, minute: Int): Int = (("h:$habitId:$minute").hashCode() and 0x3FFFFFFF) + 1_000_000

    /** Schedule the next occurrence of every configured habit reminder time. Self-healing: a fired
     *  alarm re-validates against the current habit before reshowing/rescheduling, so removed times
     *  simply stop. Call after any habit change, at startup, and on boot. */
    suspend fun scheduleHabitReminders(context: Context, repo: AppRepository, zone: ZoneId = ZoneId.systemDefault()) {
        val now = System.currentTimeMillis()
        repo.getHabitsOnce().filter { !it.archived && it.reminderTimes.isNotBlank() }.forEach { h ->
            h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1439 }.forEach { min ->
                var next = LocalDate.now(zone).atTime(LocalTime.of(min / 60, min % 60)).atZone(zone).toInstant().toEpochMilli()
                if (next <= now) next += 86_400_000L
                setAlarm(context, next, broadcast(context, ACTION_HABIT, habitReqCode(h.id, min),
                    mapOf(EXTRA_HABIT_ID to h.id, EXTRA_HABIT_NAME to ((h.emoji?.plus(" ") ?: "") + h.name), EXTRA_HABIT_MIN to min.toString())))
            }
        }
    }

    /** Snooze a habit reminder: fire it again [delayMin] minutes from now (notification action). */
    fun snoozeHabit(context: Context, habitId: String, habitName: String, minute: Int, delayMin: Int = 60) {
        val at = System.currentTimeMillis() + delayMin * 60_000L
        setAlarm(context, at, broadcast(context, ACTION_HABIT, habitReqCode(habitId, minute) + 7,
            mapOf(EXTRA_HABIT_ID to habitId, EXTRA_HABIT_NAME to habitName, EXTRA_HABIT_MIN to minute.toString())))
    }

    /** Reschedule a single habit-reminder alarm for the next day (called from the receiver). */
    fun rescheduleHabit(context: Context, habitId: String, habitName: String, minute: Int, zone: ZoneId = ZoneId.systemDefault()) {
        var next = LocalDate.now(zone).atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant().toEpochMilli()
        if (next <= System.currentTimeMillis()) next += 86_400_000L
        setAlarm(context, next, broadcast(context, ACTION_HABIT, habitReqCode(habitId, minute),
            mapOf(EXTRA_HABIT_ID to habitId, EXTRA_HABIT_NAME to habitName, EXTRA_HABIT_MIN to minute.toString())))
    }
}
