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
        val escalate = intent.getBooleanExtra(AlarmScheduler.EXTRA_ESCALATE, false)
        val step = intent.getIntExtra(AlarmScheduler.EXTRA_STEP, 0)
        val repeatEvery = intent.getIntExtra(AlarmScheduler.EXTRA_REPEAT_EVERY, -1).takeIf { it > 0 }
        val repeatCount = intent.getIntExtra(AlarmScheduler.EXTRA_REPEAT_COUNT, -1).takeIf { it > 0 }

        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                if (app == null || taskId == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val task = app.repository.getTask(taskId)
                        // W8: suppress reminders for a task whose list the user has muted.
                        val listMuted = task?.listId != null && app.repository.settingsSnapshot().mutedLists.contains(task.listId)
                        // R59 (Wave 2) — quiet hours: hold this reminder and re-arm it for when quiet hours end.
                        val deferUntil = if (task != null && !task.completed && !task.trashed && !task.abandoned && !listMuted)
                            AlarmScheduler.quietDeferUntil(System.currentTimeMillis()) else null
                        if (deferUntil != null) {
                            val delay = ((deferUntil - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
                            AlarmScheduler.scheduleFireIn(context, taskId, title, reminderId, annoying, delay, escalate, step, repeatEvery, repeatCount)
                            return@launch
                        }
                        if (task != null && !task.completed && !task.trashed && !task.abandoned && !listMuted) {
                            // R37 · Port 4 — reminder-wording MRT: on the first (non-escalation) fire, micro-
                            // randomise the motivational line and log the impression so the Nudge Lab can read
                            // out which wording actually gets a task done. Escalation keeps its own insistent text.
                            var subText: String? = null
                            if (!escalate && step == 0) {
                                val today = java.time.LocalDate.now().toEpochDay()
                                if (app.repository.nudgeForHabitDay(taskId, today) == null) {
                                    val variant = com.todocompanion.app.domain.habit.FourthWave.pickVariant(taskId.hashCode().toLong() + today)
                                    subText = com.todocompanion.app.domain.habit.FourthWave.NUDGE_VARIANTS[variant]
                                    app.repository.upsertNudgeEvent(com.todocompanion.app.data.entity.NudgeEventEntity(
                                        id = java.util.UUID.randomUUID().toString(), habitId = taskId, variant = variant,
                                        epochDay = today, targetKind = "task", createdAt = System.currentTimeMillis()))
                                }
                            }
                            Notifications.show(context, taskId, title, reminderId, annoying || escalate, escalate, step, subText)
                            when {
                                // Escalation ramps up: re-fire faster each round (5,5,4,3,2 min floor),
                                // and the notification itself grows more insistent (see Notifications.show).
                                escalate -> AlarmScheduler.scheduleFireIn(
                                    context, taskId, title, reminderId, annoying = true,
                                    delayMin = (6 - step).coerceIn(2, 5).toLong(), escalate = true, step = step + 1)
                                // R59 (Wave 2) — recurring reminder with a count: re-fire every N min, up to the count.
                                repeatCount != null && repeatCount >= 2 && repeatEvery != null && step + 1 < repeatCount ->
                                    AlarmScheduler.scheduleFireIn(context, taskId, title, reminderId, annoying, repeatEvery.toLong(),
                                        step = step + 1, repeatEvery = repeatEvery, repeatCount = repeatCount)
                                annoying -> AlarmScheduler.scheduleFireIn(context, taskId, title, reminderId, true, 15)
                            }
                        }
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_SNOOZE -> {
                if (taskId != null) {
                    Notifications.cancel(context, taskId)
                    AlarmScheduler.scheduleFireIn(context, taskId, title, reminderId, annoying, Notifications.snoozeMinutes.toLong())
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
                        val tasksOnce = app.repository.allTasksOnce()
                        val count = tasksOnce.count {
                            !it.completed && !it.trashed && !it.abandoned && it.dueDate != null &&
                                !Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate().isAfter(today)
                        }
                        // N1: the daily coach brief — keystone + at-risk streak — in the morning notification.
                        val brief = runCatching {
                            com.todocompanion.app.domain.habit.HabitInsights.dailyBrief(
                                app.repository.getHabitsOnce(), app.repository.getHabitCheckinsOnce(), tasksOnce, today.toEpochDay(), zone
                            )?.let { b -> (listOf(b.headline) + b.moves.take(1).map { "${it.emoji} ${it.text}" }).joinToString(" · ") }
                        }.getOrNull()
                        // O1: find the top still-due build habit so the brief can be checked off in place.
                        val topHabit = runCatching {
                            val hs = com.todocompanion.app.domain.habit.HabitStats
                            val checkins = app.repository.getHabitCheckinsOnce()
                            val epoch = today.toEpochDay()
                            app.repository.getHabitsOnce().filter { !it.archived && !it.paused && it.habitType != "break" }.firstOrNull { h ->
                                val hc = checkins.filter { it.habitId == h.id }
                                val doneDays = hc.filter { it.status == "done" && hs.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                                hs.dueToday(h, epoch, doneDays, hc.firstOrNull { it.epochDay == epoch }?.count ?: 0)
                            }
                        }.getOrNull()
                        Notifications.showSummary(context, count, brief, topHabit?.id, topHabit?.name)
                        val s = app.repository.settingsSnapshot()
                        if (s.dailySummaryEnabled) AlarmScheduler.scheduleDailySummary(context, s.dailySummaryHour, s.dailySummaryMinute)
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_EVENING -> {
                if (app == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val zone = ZoneId.systemDefault()
                        val today = Instant.now().atZone(zone).toLocalDate()
                        // Count today's open tasks left unfinished — the reason to sit down and plan tomorrow.
                        val leftover = app.repository.allTasksOnce().count {
                            !it.completed && !it.trashed && !it.abandoned && it.dueDate != null &&
                                !Instant.ofEpochMilli(it.dueDate!!).atZone(zone).toLocalDate().isAfter(today)
                        }
                        Notifications.showEvening(context, leftover)
                        val s = app.repository.settingsSnapshot()
                        if (s.eveningReviewEnabled) AlarmScheduler.scheduleEveningReview(context, s.eveningReviewHour)
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_MORNING -> {
                if (app == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val zone = ZoneId.systemDefault()
                        val today = Instant.now().atZone(zone).toLocalDate()
                        val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        val open = app.repository.allTasksOnce().filter { !it.completed && !it.trashed && !it.abandoned && !it.isNote }
                        val dueToday = open.filter { it.dueDate != null && it.dueDate!! < endToday }
                        // The single most pressing task: highest importance+urgency, then earliest due.
                        val top = dueToday.minWithOrNull(
                            compareByDescending<com.todocompanion.app.data.entity.TaskEntity> { it.importance + it.urgency }.thenBy { it.dueDate ?: Long.MAX_VALUE }
                        )
                        val line = when {
                            dueToday.isEmpty() -> "Nothing due today — a clear run. Open the app for your next best move."
                            top != null -> "${dueToday.size} due today. Start with: ${top.title}."
                            else -> "${dueToday.size} due today."
                        }
                        Notifications.showMorningBrief(context, line)
                        val s = app.repository.settingsSnapshot()
                        if (s.morningBriefEnabled) AlarmScheduler.scheduleMorningBrief(context, s.morningBriefHour)
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_OCCASION_NUDGE -> {
                if (app == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val s = app.repository.settingsSnapshot()
                        if (s.occasionNudge) {
                            val today = java.time.LocalDate.now()
                            Notifications.showOccasionNudge(context,
                                com.todocompanion.app.domain.Almanac.reflection(today),
                                com.todocompanion.app.domain.Almanac.onThisDay(today))
                            AlarmScheduler.scheduleOccasionNudge(context, s.occasionNudgeHour)
                        }
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_AUTO_BACKUP -> {
                if (app == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val s = app.repository.settingsSnapshot()
                        val folder = s.autoBackupFolder.ifBlank { s.syncFolder }
                        if (s.autoBackupEnabled && folder.isNotBlank()) {
                            val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                            com.todocompanion.app.data.sync.SyncEngine.backup(context, app.repository, folder, "todo-backup-$stamp.json", s.syncPassphrase)
                            AlarmScheduler.scheduleAutoBackup(context, s.autoBackupHour)
                        }
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_TRACK_PROMPT -> {
                if (app == null || taskId == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val s = app.repository.settingsSnapshot()
                        if (!s.autoTrackPrompt) return@launch
                        val task = app.repository.getTask(taskId)
                        if (task != null && !task.completed && !task.trashed && !task.abandoned) {
                            // Skip if something is already being tracked against this task.
                            val already = app.repository.runningTimeEntries().any { it.taskId == taskId }
                            if (!already) {
                                val acts = app.repository.getTimeActivitiesOnce()
                                val actId = task.defaultActivityId?.takeIf { id -> acts.any { it.id == id && !it.archived } }
                                    ?: app.repository.ensureTaskActivity()
                                Notifications.showTrackPrompt(context, taskId, task.title, actId)
                            }
                        }
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_FOCUS_DONE -> Notifications.showFocusDone(context)

            AlarmScheduler.ACTION_HABIT -> {
                if (app == null) return
                val habitId = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_ID) ?: return
                val name = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_NAME) ?: "your habit"
                val min = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_MIN)?.toIntOrNull() ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val zone = ZoneId.systemDefault()
                        val todayEpoch = java.time.LocalDate.now(zone).toEpochDay()
                        val h = app.repository.getHabitsOnce().firstOrNull { it.id == habitId }
                        // Self-heal: only fire + reschedule while the time is still configured.
                        val muted = app.repository.settingsSnapshot().mutedHabits.contains(habitId)   // W8
                        val stillWanted = h != null && !h.archived && !muted &&
                            h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(min)
                        if (stillWanted && !h!!.paused) {
                            val stats = com.todocompanion.app.domain.habit.HabitStats
                            val scheduledToday = stats.isExpectedDay(h, todayEpoch) ||
                                h.freqType == stats.FREQ_TIMES_WEEK || h.freqType == stats.FREQ_TIMES_MONTH
                            val checkins = app.repository.getHabitCheckinsOnce()
                            val doneDays = checkins.filter { it.habitId == habitId && it.status == "done" && stats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                            val todayCount = checkins.firstOrNull { it.habitId == habitId && it.epochDay == todayEpoch }?.count ?: 0
                            val stillDue = stats.dueToday(h, todayEpoch, doneDays, todayCount)
                            // R59 (Wave 2) — honour quiet hours: skip the nudge when we're in the quiet window
                            // (the habit re-arms for its next day regardless).
                            if (scheduledToday && stillDue && AlarmScheduler.quietDeferUntil(System.currentTimeMillis()) == null)
                                Notifications.showHabit(context, habitId, name, min, why = h.description)
                            AlarmScheduler.rescheduleHabit(context, habitId, name, min)
                        }
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_HABIT_DONE -> {
                if (app == null) return
                val habitId = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_ID) ?: return
                androidx.core.app.NotificationManagerCompat.from(context).cancel(("habit:$habitId").hashCode())
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val todayEpoch = java.time.LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                        val h = app.repository.getHabitsOnce().firstOrNull { it.id == habitId }
                        if (h != null) app.repository.setCheckinValue(habitId, todayEpoch, h.targetPerDay.coerceAtLeast(1))
                        com.todocompanion.app.widget.HabitsWidget.refresh(context)
                        com.todocompanion.app.widget.HabitStatsWidget.refresh(context)
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_EVENT_ALERT -> {
                if (app == null) return
                val eventId = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_ID) ?: return
                val evTitle = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_TITLE) ?: "Event"
                val loc = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_LOC) ?: ""
                val start = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_START)?.toLongOrNull() ?: 0L
                val min = intent.getStringExtra(AlarmScheduler.EXTRA_EVENT_MIN)?.toIntOrNull() ?: 0
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val e = app.repository.eventById(eventId) ?: return@launch
                        Notifications.showEventAlert(context, eventId, evTitle, loc, start, min)
                        // Repeating event: once the closest lead alert has fired, arm the following occurrence.
                        if (e.rrule.isNotBlank()) {
                            val closest = e.alertsMinutes.split(",").mapNotNull { it.trim().toIntOrNull() }.minOrNull()
                            if (closest != null && min == closest) AlarmScheduler.scheduleEventAlerts(context, e, fromMillis = start)
                        }
                    } finally { pending.finish() }
                }
            }

            AlarmScheduler.ACTION_HABIT_SNOOZE -> {
                val habitId = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_ID) ?: return
                val name = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_NAME) ?: "your habit"
                val min = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_MIN)?.toIntOrNull() ?: 0
                androidx.core.app.NotificationManagerCompat.from(context).cancel(("habit:$habitId").hashCode())
                AlarmScheduler.snoozeHabit(context, habitId, name, min, Notifications.snoozeMinutes)
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
                AlarmScheduler.scheduleHabitReminders(context, app.repository)
                val s = app.repository.settingsSnapshot()
                if (s.dailySummaryEnabled) AlarmScheduler.scheduleDailySummary(context, s.dailySummaryHour, s.dailySummaryMinute)
                if (s.eveningReviewEnabled) AlarmScheduler.scheduleEveningReview(context, s.eveningReviewHour)
                if (s.morningBriefEnabled) AlarmScheduler.scheduleMorningBrief(context, s.morningBriefHour)
                if (s.occasionNudge) AlarmScheduler.scheduleOccasionNudge(context, s.occasionNudgeHour)
                if (s.autoBackupEnabled && s.autoBackupFolder.isNotBlank()) AlarmScheduler.scheduleAutoBackup(context, s.autoBackupHour)
                if (s.autoTrackPrompt) AlarmScheduler.scheduleTrackPrompts(context, app.repository)
                AlarmScheduler.rescheduleEventAlerts(context, app.repository)
            } finally { pending.finish() }
        }
    }
}
