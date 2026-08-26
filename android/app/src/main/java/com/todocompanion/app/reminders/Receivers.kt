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

        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                if (app == null || taskId == null) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val task = app.repository.getTask(taskId)
                        if (task != null && !task.completed && !task.trashed && !task.abandoned) {
                            Notifications.show(context, taskId, title, reminderId, annoying || escalate, escalate, step)
                            when {
                                // Escalation ramps up: re-fire faster each round (5,5,4,3,2 min floor),
                                // and the notification itself grows more insistent (see Notifications.show).
                                escalate -> AlarmScheduler.scheduleFireIn(
                                    context, taskId, title, reminderId, annoying = true,
                                    delayMin = (6 - step).coerceIn(2, 5).toLong(), escalate = true, step = step + 1)
                                annoying -> AlarmScheduler.scheduleFireIn(context, taskId, title, reminderId, true, 15)
                            }
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
                        val stillWanted = h != null && !h.archived &&
                            h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(min)
                        if (stillWanted && !h!!.paused) {
                            val stats = com.todocompanion.app.domain.habit.HabitStats
                            val scheduledToday = stats.isExpectedDay(h, todayEpoch) ||
                                h.freqType == stats.FREQ_TIMES_WEEK || h.freqType == stats.FREQ_TIMES_MONTH
                            val checkins = app.repository.getHabitCheckinsOnce()
                            val doneDays = checkins.filter { it.habitId == habitId && it.status == "done" && stats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                            val todayCount = checkins.firstOrNull { it.habitId == habitId && it.epochDay == todayEpoch }?.count ?: 0
                            val stillDue = stats.dueToday(h, todayEpoch, doneDays, todayCount)
                            if (scheduledToday && stillDue) Notifications.showHabit(context, habitId, name, min, why = h.description)
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

            AlarmScheduler.ACTION_HABIT_SNOOZE -> {
                val habitId = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_ID) ?: return
                val name = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_NAME) ?: "your habit"
                val min = intent.getStringExtra(AlarmScheduler.EXTRA_HABIT_MIN)?.toIntOrNull() ?: 0
                androidx.core.app.NotificationManagerCompat.from(context).cancel(("habit:$habitId").hashCode())
                AlarmScheduler.snoozeHabit(context, habitId, name, min, 60)
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
                if (s.autoBackupEnabled && s.autoBackupFolder.isNotBlank()) AlarmScheduler.scheduleAutoBackup(context, s.autoBackupHour)
                if (s.autoTrackPrompt) AlarmScheduler.scheduleTrackPrompts(context, app.repository)
            } finally { pending.finish() }
        }
    }
}
