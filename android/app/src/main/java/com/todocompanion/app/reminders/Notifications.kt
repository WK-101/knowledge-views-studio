package com.todocompanion.app.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.todocompanion.app.MainActivity

object Notifications {
    const val CHANNEL_ID = "reminders"
    const val CHANNEL_SILENT = "reminders_silent"
    const val CHANNEL_CUSTOM = "reminders_custom"
    const val SUMMARY_ID = 424242

    // Security (R18): when the user turns on "hide notification content on the lock screen", every
    // notification is built VISIBILITY_SECRET so task titles never surface on a locked device. Kept as a
    // volatile flag updated from the settings flow (notifications fire from background receivers). Off = the
    // platform default. Fully local.
    @Volatile var lockscreenPrivate: Boolean = false

    // R59 (Wave 1) — the snooze duration (minutes) every notification's Snooze action uses, mirrored from
    // the settings flow (notifications fire from background receivers). One shared value across tasks and
    // habits so "Snooze" means the same everywhere. Default 10 min.
    @Volatile var snoozeMinutes: Int = 10
    private fun snoozeLabel(): String { val m = snoozeMinutes; return if (m % 60 == 0 && m >= 60) "${m / 60}h" else "${m}m" }

    // R81 — the reminder notification sound, mirrored from the settings flow: "default" (system default),
    // "silent", or a content:// URI the user picked. On Android O+ a channel's sound is immutable once
    // created, so each choice maps to its own channel; the custom channel is recreated only when its URI
    // actually changes (tracked by [appliedCustomUri]) so a re-pick takes effect without churn.
    @Volatile var reminderSoundSpec: String = "default"
    @Volatile private var appliedCustomUri: String? = null

    private fun isSoundUri(spec: String): Boolean =
        spec.startsWith("content://") || spec.startsWith("android.resource") || spec.startsWith("file://")

    /** The channel a reminder should post to, given the current sound choice. */
    private fun activeChannelId(): String = when {
        reminderSoundSpec == "silent" -> CHANNEL_SILENT
        isSoundUri(reminderSoundSpec) -> CHANNEL_CUSTOM
        else -> CHANNEL_ID
    }

    /** Builder that applies the lock-screen-privacy setting centrally and routes to the sound channel the
     *  user chose. (The `id` local keeps this call from being caught by the project-wide swap onto this helper.) */
    private fun builder(context: Context): NotificationCompat.Builder {
        ensureChannel(context)
        val id = activeChannelId()
        return NotificationCompat.Builder(context, id).apply {
            if (lockscreenPrivate) setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }
    }

    /** R95 — post a notification only when we're actually allowed to. On Android 13+ POST_NOTIFICATIONS is
     *  runtime-revocable, so a bare notify() both risks a swallowed SecurityException and wastes the work of
     *  building a notification the system will silently drop; this gate skips it cleanly instead. On older
     *  APIs the permission is granted implicitly at install. Every notify() in this file funnels through here. */
    private fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            // The default-sound channel always exists (back-compat + the "Default" choice).
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "Reminders and the daily summary" }
                )
            }
            when {
                reminderSoundSpec == "silent" ->
                    if (mgr.getNotificationChannel(CHANNEL_SILENT) == null) {
                        mgr.createNotificationChannel(
                            NotificationChannel(CHANNEL_SILENT, "Reminders (silent)", NotificationManager.IMPORTANCE_HIGH)
                                .apply { description = "Reminders with no sound"; setSound(null, null) }
                        )
                    }
                isSoundUri(reminderSoundSpec) ->
                    if (appliedCustomUri != reminderSoundSpec || mgr.getNotificationChannel(CHANNEL_CUSTOM) == null) {
                        runCatching { mgr.deleteNotificationChannel(CHANNEL_CUSTOM) }
                        val attrs = android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                        mgr.createNotificationChannel(
                            NotificationChannel(CHANNEL_CUSTOM, "Reminders (custom sound)", NotificationManager.IMPORTANCE_HIGH).apply {
                                description = "Reminders with your chosen sound"
                                runCatching { setSound(android.net.Uri.parse(reminderSoundSpec), attrs) }
                            }
                        )
                        appliedCustomUri = reminderSoundSpec
                    }
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

    /** Deep-link into the app carrying a launch action (routed by AppRoot). */
    private fun openAppRoute(context: Context, action: String, reqCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_ACTION, action)
        return PendingIntent.getActivity(context, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    const val EVENING_ID = 424244
    const val MORNING_ID = 424245

    /** Z4: the single daily "morning brief" — one calm note that opens the app for the full picture. */
    fun showMorningBrief(context: Context, line: String) {
        ensureChannel(context)
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Your morning brief")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, MORNING_ID, n)
    }

    fun showEvening(context: Context, leftover: Int) {
        ensureChannel(context)
        val text = if (leftover == 0) "Everything's done. Take 2 minutes to line up tomorrow." else
            "$leftover task${if (leftover == 1) "" else "s"} still open today. Plan tomorrow before you clock off."
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Evening review")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppRoute(context, "open_close_day", 918_276))
            .build()
        post(context, EVENING_ID, n)
    }

    const val OCCASION_LIVE_ID = 424246
    const val OCCASION_NUDGE_ID = 424247

    /** #9 — an ongoing, low-key notification pinning the single most imminent occasion. Posted on demand
     *  (app open / occasion saved), never by a background worker, so it needs no new permission. An empty
     *  list clears it (the setting is off, or nothing is upcoming). */
    fun refreshOccasion(context: Context, occasions: List<com.todocompanion.app.data.entity.CountdownEntity>) {
        val today = java.time.LocalDate.now()
        val next = occasions
            .filter { !it.archived && !it.countUp }
            .map { it to com.todocompanion.app.domain.LifeEvent.daysUntil(it, today) }
            .filter { it.second >= 0 }
            .minByOrNull { it.second }
        if (next == null) { runCatching { NotificationManagerCompat.from(context).cancel(OCCASION_LIVE_ID) }; return }
        val (c, days) = next
        val who = c.personName.ifBlank { c.title }
        val label = com.todocompanion.app.domain.LifeEvent.daysLabel(days)
        val date = com.todocompanion.app.domain.LifeEvent.nextOccurrence(c, today)
        val emoji = c.emoji ?: com.todocompanion.app.domain.LifeEvent.type(c).emoji
        ensureChannel(context)
        val title = if (days == 0L) "$emoji $who is today 🎉" else "$emoji $who — $label"
        val text = "${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}, " +
            "${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}"
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openApp(context))
            .build()
        post(context, OCCASION_LIVE_ID, n)
    }

    /** #23 — one gentle daily reflection (a finite-time thought + today-in-history), opt-in. */
    fun showOccasionNudge(context: Context, reflection: String, history: String?) {
        ensureChannel(context)
        val big = reflection + (history?.let { "\n\nOn this day — $it" } ?: "")
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("A moment to reflect")
            .setContentText(reflection)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, OCCASION_NUDGE_ID, n)
    }

    fun show(context: Context, taskId: String, title: String, reminderId: String, annoying: Boolean, escalate: Boolean = false, step: Int = 0, subText: String? = null) {
        ensureChannel(context)
        val done = broadcast(context, AlarmScheduler.ACTION_DONE, ("done$taskId").hashCode(),
            mapOf(AlarmScheduler.EXTRA_TASK_ID to taskId))
        val snooze = broadcast(context, AlarmScheduler.ACTION_SNOOZE, ("snz$reminderId").hashCode(),
            mapOf(AlarmScheduler.EXTRA_TASK_ID to taskId, AlarmScheduler.EXTRA_TITLE to title,
                AlarmScheduler.EXTRA_REMINDER_ID to reminderId, AlarmScheduler.EXTRA_ANNOYING to annoying))
        // Escalation makes each successive alert harder to ignore: the text nags louder and, once it's
        // been ignored a few rounds, it takes over the screen (full-screen intent) and vibrates.
        val text = if (escalate && step > 0) "Still not done — reminder ×${step + 1}" else (subText ?: "Reminder")
        val b = builder(context)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Done", done)
            .addAction(0, "Snooze ${snoozeLabel()}", snooze)
        if (escalate) {
            b.setCategory(NotificationCompat.CATEGORY_ALARM)
            b.setVibrate(longArrayOf(0, 400, 200, 400))
            if (step >= 2) b.setFullScreenIntent(openApp(context), true)
        }
        post(context, taskId.hashCode(), b.build())
    }

    fun cancel(context: Context, taskId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(taskId.hashCode()) }
    }

    const val EVENT_ALERT_BASE = 424300

    /** R38 — a dedicated-calendar event is coming up. Opens the calendar. No task actions. */
    fun showEventAlert(context: Context, eventId: String, title: String, location: String, startMillis: Long, minutesBefore: Int) {
        ensureChannel(context)
        val whenTxt = runCatching {
            java.time.Instant.ofEpochMilli(startMillis).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a"))
        }.getOrDefault("")
        val lead = when {
            minutesBefore <= 0 -> "now"
            minutesBefore % 1440 == 0 -> "in ${minutesBefore / 1440}d"
            minutesBefore % 60 == 0 -> "in ${minutesBefore / 60}h"
            else -> "in ${minutesBefore}m"
        }
        val text = buildString {
            append(if (minutesBefore <= 0) "Starting now" else "Starts $lead")
            if (whenTxt.isNotBlank()) append(" · ").append(whenTxt)
            if (location.isNotBlank()) append(" · ").append(location)
        }
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(openAppRoute(context, "open_calendar", ("evc$eventId").hashCode()))
            .build()
        post(context, EVENT_ALERT_BASE + (eventId.hashCode() and 0x3FF), n)
    }

    const val FOCUS_ID = 424243

    fun showFocusDone(context: Context) {
        ensureChannel(context)
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Focus session complete")
            .setContentText("Nice work — time for a break.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, FOCUS_ID, n)
    }

    private val HABIT_LINES = listOf(
        "Small steps, big change.", "Keep the streak alive 🔥", "Two minutes is enough to start.",
        "Future-you says thanks.", "Consistency beats intensity.", "You've got this.", "One rep for momentum.",
    )

    fun showHabit(context: Context, habitId: String, name: String, minute: Int = -1, why: String = "") {
        ensureChannel(context)
        // K3: if a motivation/"why" is set, lead with it — it lands harder than a generic line.
        val line = why.takeIf { it.isNotBlank() } ?: HABIT_LINES[(habitId.hashCode() + (System.currentTimeMillis() / 86_400_000L).toInt()).let { ((it % HABIT_LINES.size) + HABIT_LINES.size) % HABIT_LINES.size }]
        val reqBase = ("habit:$habitId").hashCode()
        val doneExtras = mapOf(AlarmScheduler.EXTRA_HABIT_ID to habitId, AlarmScheduler.EXTRA_HABIT_NAME to name, AlarmScheduler.EXTRA_HABIT_MIN to minute.toString())
        val b = builder(context)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time for $name")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Done", broadcast(context, AlarmScheduler.ACTION_HABIT_DONE, reqBase + 1, doneExtras))
            .addAction(0, "Snooze ${snoozeLabel()}", broadcast(context, AlarmScheduler.ACTION_HABIT_SNOOZE, reqBase + 2, doneExtras))
        post(context, ("habit:$habitId").hashCode(), b.build())
    }

    fun showSummary(context: Context, dueToday: Int, brief: String? = null, topHabitId: String? = null, topHabitName: String? = null) {
        ensureChannel(context)
        val tasksLine = if (dueToday == 0) "No tasks due today — enjoy!" else "You have $dueToday task${if (dueToday == 1) "" else "s"} due today."
        // N1: lead with the habit coach brief when there is one, then the task line.
        val body = if (!brief.isNullOrBlank()) "$brief\n$tasksLine" else tasksLine
        val b = builder(context)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(if (!brief.isNullOrBlank()) "Your day" else "Today")
            .setContentText(if (!brief.isNullOrBlank()) brief else tasksLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        // O1: make the brief two-way — check off the day's top habit straight from the notification.
        if (!topHabitId.isNullOrBlank() && !topHabitName.isNullOrBlank()) {
            b.addAction(0, "✓ ${topHabitName.take(22)}", broadcast(context, AlarmScheduler.ACTION_HABIT_DONE, ("summ:$topHabitId").hashCode(),
                mapOf(AlarmScheduler.EXTRA_HABIT_ID to topHabitId, AlarmScheduler.EXTRA_HABIT_NAME to topHabitName, AlarmScheduler.EXTRA_HABIT_MIN to "-1")))
        }
        post(context, SUMMARY_ID, b.build())
    }

    /** U12: a plain automation notification ("phone on silent?") fired when a rule matches. */
    fun simple(context: Context, tag: String, title: String, text: String) {
        ensureChannel(context)
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, tag.hashCode(), n)
    }

    /** U2: a time-blocked task's start time arrived — offer to begin tracking it in one tap. The Start
     *  action broadcasts to the time-tracking receiver with the task's activity already resolved. */
    fun showTrackPrompt(context: Context, taskId: String, title: String, activityId: String) {
        ensureChannel(context)
        val start = Intent(context, com.todocompanion.app.widget.TimeTrackReceiver::class.java)
            .setAction(com.todocompanion.app.widget.ACTION_START)
            .putExtra(com.todocompanion.app.widget.TimeTrackReceiver.EXTRA_ACTIVITY_ID, activityId)
            .putExtra(com.todocompanion.app.widget.TimeTrackReceiver.EXTRA_TASK_ID, taskId)
        val pi = PendingIntent.getBroadcast(context, ("track:$taskId").hashCode(), start, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Starting: $title")
            .setContentText("Track time on this block?")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "▶ Start tracking", pi)
            .build()
        post(context, ("trackprompt:$taskId").hashCode(), n)
    }

    const val SEALED_LETTER_BASE = 424400

    /** Track 3.4 — a letter you sealed for the future is ready to open. Opens The Record (where the sealed
     *  letters live) so you can read it beside the "what's changed since you sealed this" diff. */
    fun showSealedLetter(context: Context, id: String, title: String, createdEpochDay: Long) {
        ensureChannel(context)
        val sealedOn = runCatching {
            java.time.LocalDate.ofEpochDay(createdEpochDay)
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
        }.getOrDefault("")
        val text = if (sealedOn.isNotBlank()) "A letter you sealed on $sealedOn is ready to open." else "A letter you sealed for your future self is ready to open."
        val n = builder(context)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title.ifBlank { "A letter to future you" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\nSee everything you've finished since then."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppRoute(context, "open_record", ("sealed:$id").hashCode()))
            .build()
        post(context, SEALED_LETTER_BASE + (id.hashCode() and 0x3FF), n)
    }

    /** N2: celebrate a habit reaching its self-chosen reward streak. */
    fun showReward(context: Context, name: String, reward: String, streak: Int) {
        ensureChannel(context)
        val text = "You hit a $streak-day streak on ‘$name’. You earned it: $reward 🎉"
        val n = builder(context)
            .setSmallIcon(android.R.drawable.btn_star_big_on)
            .setContentTitle("Reward unlocked! 🎁")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        post(context, ("reward:$name").hashCode(), n)
    }
}
