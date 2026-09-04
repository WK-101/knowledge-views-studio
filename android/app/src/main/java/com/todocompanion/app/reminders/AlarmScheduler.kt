package com.todocompanion.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.data.entity.EventEntity
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
    const val ACTION_MORNING = "com.todocompanion.app.action.MORNING"
    const val ACTION_EVENT_ALERT = "com.todocompanion.app.action.EVENT_ALERT"
    const val ACTION_OCCASION_NUDGE = "com.todocompanion.app.action.OCCASION_NUDGE"

    const val EXTRA_TASK_ID = "taskId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_REMINDER_ID = "reminderId"
    const val EXTRA_ANNOYING = "annoying"
    const val EXTRA_ESCALATE = "escalate"
    const val EXTRA_STEP = "step"
    const val EXTRA_REPEAT_EVERY = "repeatEvery"   // R59 — recurring-reminder-with-count: interval (min)
    const val EXTRA_REPEAT_COUNT = "repeatCount"   // ...total times to fire (>=2 to repeat)
    const val EXTRA_HABIT_ID = "habitId"
    const val EXTRA_HABIT_NAME = "habitName"
    const val EXTRA_HABIT_MIN = "habitMin"
    const val EXTRA_EVENT_ID = "eventId"
    const val EXTRA_EVENT_TITLE = "eventTitle"
    const val EXTRA_EVENT_LOC = "eventLoc"
    const val EXTRA_EVENT_START = "eventStart"
    const val EXTRA_EVENT_MIN = "eventMin"

    private const val SUMMARY_REQ = 918_273
    private const val EVENING_REQ = 918_275
    private const val AUTOBACKUP_REQ = 918_277
    private const val MORNING_REQ = 918_278
    private const val OCCASION_NUDGE_REQ = 918_279

    fun triggerTimeFor(reminder: ReminderEntity, task: TaskEntity, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val offset = (reminder.offsetMin ?: 0) * 60_000L
        return when (reminder.type) {
            "absolute" -> reminder.atTime
            "relativeToDue" -> task.dueDate?.minus(offset)
            "relativeToStart" -> task.startDate?.minus(offset)
            // R59 (Wave 2) — expert reminder types, all on the one abstraction.
            "relativeToDeadline" -> task.deadlineDate?.minus(offset)
            // All-day reminder at a chosen clock time (offsetMin = minute-of-day) on the due day.
            "dueDayAt" -> task.dueDate?.let { dayAt(it, reminder.offsetMin ?: 540, zone) }
            // Fire the moment it becomes overdue (timed → the due instant; all-day → end of the due day).
            "whenOverdue" -> task.dueDate?.let { overdueMoment(it, zone) }
            // A surprise nudge at a stable-random point within [offsetMin] minutes before due.
            "random" -> task.dueDate?.let { it - randomLeadMs(reminder.id, reminder.offsetMin ?: 120) }
            else -> null
        }
    }

    private fun dayAt(dueMillis: Long, minuteOfDay: Int, zone: ZoneId): Long {
        val day = Instant.ofEpochMilli(dueMillis).atZone(zone).toLocalDate()
        return day.atStartOfDay(zone).toInstant().toEpochMilli() + minuteOfDay.coerceIn(0, 1439).toLong() * 60_000L
    }

    private fun overdueMoment(dueMillis: Long, zone: ZoneId): Long {
        val dt = Instant.ofEpochMilli(dueMillis).atZone(zone)
        return if (dt.hour == 0 && dt.minute == 0)
            dt.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 60_000L
        else dueMillis
    }

    private fun randomLeadMs(seed: String, maxLeadMin: Int): Long =
        kotlin.random.Random(seed.hashCode().toLong()).nextInt(0, maxLeadMin.coerceAtLeast(1) + 1).toLong() * 60_000L

    // ── R59 (Wave 2) · quiet hours ──────────────────────────────────────────────────────────────────
    // Mirrored from the settings flow (reminders fire from background receivers). A reminder that would
    // land inside [quietStartHour, quietEndHour) is deferred to when quiet hours end, so overnight alerts
    // arrive together in the morning instead of waking you.
    @Volatile var quietEnabled = false
    @Volatile var quietStartHour = 22
    @Volatile var quietEndHour = 7

    /** If [nowMillis] is inside quiet hours, the epoch-millis when quiet hours next end; else null. */
    fun quietDeferUntil(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!quietEnabled || quietStartHour == quietEndHour) return null
        val h = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
        val inQuiet = if (quietStartHour < quietEndHour) h in quietStartHour until quietEndHour
        else (h >= quietStartHour || h < quietEndHour)
        if (!inQuiet) return null
        val today0 = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone)
        var end = today0.plusHours(quietEndHour.toLong())
        if (end.toInstant().toEpochMilli() <= nowMillis) end = end.plusDays(1)
        return end.toInstant().toEpochMilli()
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

    private fun fireExtras(taskId: String, title: String, reminderId: String, annoying: Boolean, escalate: Boolean = false, step: Int = 0, repeatEvery: Int? = null, repeatCount: Int? = null) =
        mapOf<String, Any?>(EXTRA_TASK_ID to taskId, EXTRA_TITLE to title, EXTRA_REMINDER_ID to reminderId,
            EXTRA_ANNOYING to annoying, EXTRA_ESCALATE to escalate, EXTRA_STEP to step,
            EXTRA_REPEAT_EVERY to repeatEvery, EXTRA_REPEAT_COUNT to repeatCount)

    fun schedule(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        if (task.completed || task.trashed || task.abandoned) return
        val at = triggerTimeFor(reminder, task) ?: return
        if (at <= System.currentTimeMillis()) return
        val pi = broadcast(context, ACTION_FIRE, reminder.id.hashCode(), fireExtras(task.id, task.title, reminder.id, reminder.annoying, reminder.escalate, 0, reminder.repeatEveryMin, reminder.repeatCount))
        setAlarm(context, at, pi)
    }

    fun cancel(context: Context, reminder: ReminderEntity, task: TaskEntity) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_FIRE, reminder.id.hashCode(), fireExtras(task.id, task.title, reminder.id, reminder.annoying, reminder.escalate, 0, reminder.repeatEveryMin, reminder.repeatCount)))
    }

    /** Re-fire a reminder after [delayMin] minutes (snooze / annoying repeat / escalation / recurring / quiet-defer). */
    fun scheduleFireIn(context: Context, taskId: String, title: String, reminderId: String, annoying: Boolean, delayMin: Long, escalate: Boolean = false, step: Int = 0, repeatEvery: Int? = null, repeatCount: Int? = null) {
        val pi = broadcast(context, ACTION_FIRE, reminderId.hashCode(), fireExtras(taskId, title, reminderId, annoying, escalate, step, repeatEvery, repeatCount))
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
    // Phase F — a [minute] component was added (default 0, so every existing caller is unchanged) so the
    // adaptive-time layer can aim the nudge at any minute-of-day, not just the top of an hour.
    fun scheduleEveningReview(context: Context, hour: Int, minute: Int = 0, zone: ZoneId = ZoneId.systemDefault()) {
        val now = System.currentTimeMillis()
        var next = LocalDate.now(zone).atTime(LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
            .atZone(zone).toInstant().toEpochMilli()
        if (next <= now) next += 86_400_000L
        setAlarm(context, next, broadcast(context, ACTION_EVENING, EVENING_REQ, emptyMap()))
    }

    fun cancelEveningReview(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_EVENING, EVENING_REQ, emptyMap()))
    }

    /**
     * Phase F — (re)schedule the evening review honouring the adaptive-time setting. When the user opted
     * to "adapt the reminder to when I usually close my day", aim it at the median of their recent close
     * times (clamped to the evening window); otherwise keep the fixed [AppSettings.eveningReviewHour]
     * exactly. Cancels when the nudge is off. The single scheduling entry point for the smart layer, called
     * from the settings flow (via the ViewModel), the evening receiver's re-arm, and boot.
     */
    suspend fun scheduleEveningReviewSmart(context: Context, repo: AppRepository, zone: ZoneId = ZoneId.systemDefault()) {
        val s = repo.settingsSnapshot()
        if (!s.eveningReviewEnabled) { cancelEveningReview(context); return }
        val fixed = s.eveningReviewHour.coerceIn(0, 23) * 60
        val minuteOfDay = if (s.eveningReviewAdaptive) {
            val closeMinutes = recentCloseMinutes(repo, zone)
            com.todocompanion.app.domain.ReviewCadence.adaptiveReminderMinuteOfDay(closeMinutes, fixed)
        } else fixed
        scheduleEveningReview(context, minuteOfDay / 60, minuteOfDay % 60, zone)
    }

    /** Minute-of-day of each recent reviewed day's close (its DayLog's updatedAt), newest first. */
    private suspend fun recentCloseMinutes(repo: AppRepository, zone: ZoneId): List<Int> {
        val today = LocalDate.now(zone).toEpochDay()
        return repo.dayLogsOnce()
            .filter { com.todocompanion.app.domain.ReviewCadence.isReviewed(it) && it.updatedAt > 0L && it.epochDay <= today }
            .sortedByDescending { it.epochDay }
            .take(com.todocompanion.app.domain.ReviewCadence.SAMPLE_DAYS)
            .map { Instant.ofEpochMilli(it.updatedAt).atZone(zone).let { z -> z.hour * 60 + z.minute } }
    }

    // ---------- Z4 · morning brief ----------
    fun scheduleMorningBrief(context: Context, hour: Int, zone: ZoneId = ZoneId.systemDefault()) {
        val now = System.currentTimeMillis()
        var next = LocalDate.now(zone).atTime(LocalTime.of(hour.coerceIn(0, 23), 0)).atZone(zone).toInstant().toEpochMilli()
        if (next <= now) next += 86_400_000L
        setAlarm(context, next, broadcast(context, ACTION_MORNING, MORNING_REQ, emptyMap()))
    }
    fun cancelMorningBrief(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_MORNING, MORNING_REQ, emptyMap()))
    }

    // ---------- R46 · occasions reflective nudge ----------
    fun scheduleOccasionNudge(context: Context, hour: Int, zone: ZoneId = ZoneId.systemDefault()) {
        val now = System.currentTimeMillis()
        var next = LocalDate.now(zone).atTime(LocalTime.of(hour.coerceIn(0, 23), 0)).atZone(zone).toInstant().toEpochMilli()
        if (next <= now) next += 86_400_000L
        setAlarm(context, next, broadcast(context, ACTION_OCCASION_NUDGE, OCCASION_NUDGE_REQ, emptyMap()))
    }
    fun cancelOccasionNudge(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(broadcast(context, ACTION_OCCASION_NUDGE, OCCASION_NUDGE_REQ, emptyMap()))
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
        val muted = repo.settingsSnapshot().mutedHabits   // W8
        repo.getHabitsOnce().filter { !it.archived && it.reminderTimes.isNotBlank() && it.id !in muted }.forEach { h ->
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

    // ---------- R38 · calendar event alerts ----------
    private fun eventReqCode(eventId: String, minutesBefore: Int): Int =
        (("ev:$eventId:$minutesBefore").hashCode() and 0x3FFFFFFF) + 3_000_000

    /** First occurrence start strictly after [after] (honouring EXDATE, UNTIL and COUNT), or null. */
    private fun nextOccurrenceStart(e: EventEntity, after: Long, zone: ZoneId): Long? {
        if (e.rrule.isBlank()) return e.startMillis.takeIf { it > after }
        val r = com.todocompanion.app.domain.recurrence.Recurrence.parse(e.rrule)
        val ex = e.exDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        var cur = e.startMillis
        var emitted = 0
        var guard = 0
        while (guard++ < 3000) {
            val day = Instant.ofEpochMilli(cur).atZone(zone).toLocalDate().toEpochDay()
            r?.untilEpochDay?.let { if (day > it) return null }
            if (r?.count != null && emitted >= r.count) return null
            if (day !in ex && cur > after) return cur
            emitted++
            val nxt = com.todocompanion.app.domain.recurrence.Recurrence.next(e.rrule, cur, zone)
            if (nxt <= cur) return null
            cur = nxt
        }
        return null
    }

    /** Schedule one exact alarm per configured lead time for the next upcoming occurrence of [e].
     *  For a repeating event the receiver re-arms the following occurrence after the closest alert fires.
     *  [fromMillis] lets the receiver ask for the occurrence strictly after the one that just fired. */
    fun scheduleEventAlerts(context: Context, e: EventEntity, zone: ZoneId = ZoneId.systemDefault(), fromMillis: Long = System.currentTimeMillis()) {
        cancelEventAlerts(context, e)
        val mins = e.alertsMinutes.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it >= 0 }.distinct()
        if (mins.isEmpty()) return
        val now = System.currentTimeMillis()
        val nextStart = nextOccurrenceStart(e, fromMillis, zone) ?: return
        mins.forEach { m ->
            val at = nextStart - m * 60_000L
            if (at > now) setAlarm(context, at, broadcast(context, ACTION_EVENT_ALERT, eventReqCode(e.id, m),
                mapOf(EXTRA_EVENT_ID to e.id, EXTRA_EVENT_TITLE to e.title, EXTRA_EVENT_LOC to e.location,
                    EXTRA_EVENT_START to nextStart.toString(), EXTRA_EVENT_MIN to m.toString())))
        }
    }

    fun cancelEventAlerts(context: Context, e: EventEntity) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        e.alertsMinutes.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it >= 0 }.distinct().forEach { m ->
            am.cancel(broadcast(context, ACTION_EVENT_ALERT, eventReqCode(e.id, m), emptyMap()))
        }
    }

    /** Re-arm every event's alerts (startup / boot). Self-healing: past occurrences simply don't schedule. */
    suspend fun rescheduleEventAlerts(context: Context, repo: AppRepository) {
        repo.eventsOnce().filter { it.recurrenceParentId == null }.forEach { scheduleEventAlerts(context, it) }
    }
}
