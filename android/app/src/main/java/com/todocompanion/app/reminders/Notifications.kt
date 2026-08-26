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
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Your morning brief")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(MORNING_ID, n) }
    }

    fun showEvening(context: Context, leftover: Int) {
        ensureChannel(context)
        val text = if (leftover == 0) "Everything's done. Take 2 minutes to line up tomorrow." else
            "$leftover task${if (leftover == 1) "" else "s"} still open today. Plan tomorrow before you clock off."
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Evening review")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppRoute(context, "open_plan", 918_276))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(EVENING_ID, n) }
    }

    fun show(context: Context, taskId: String, title: String, reminderId: String, annoying: Boolean, escalate: Boolean = false, step: Int = 0) {
        ensureChannel(context)
        val done = broadcast(context, AlarmScheduler.ACTION_DONE, ("done$taskId").hashCode(),
            mapOf(AlarmScheduler.EXTRA_TASK_ID to taskId))
        val snooze = broadcast(context, AlarmScheduler.ACTION_SNOOZE, ("snz$reminderId").hashCode(),
            mapOf(AlarmScheduler.EXTRA_TASK_ID to taskId, AlarmScheduler.EXTRA_TITLE to title,
                AlarmScheduler.EXTRA_REMINDER_ID to reminderId, AlarmScheduler.EXTRA_ANNOYING to annoying))
        // Escalation makes each successive alert harder to ignore: the text nags louder and, once it's
        // been ignored a few rounds, it takes over the screen (full-screen intent) and vibrates.
        val text = if (escalate && step > 0) "Still not done — reminder ×${step + 1}" else "Reminder"
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Done", done)
            .addAction(0, "Snooze 10m", snooze)
        if (escalate) {
            b.setCategory(NotificationCompat.CATEGORY_ALARM)
            b.setVibrate(longArrayOf(0, 400, 200, 400))
            if (step >= 2) b.setFullScreenIntent(openApp(context), true)
        }
        runCatching { NotificationManagerCompat.from(context).notify(taskId.hashCode(), b.build()) }
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
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time for $name")
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Done", broadcast(context, AlarmScheduler.ACTION_HABIT_DONE, reqBase + 1, doneExtras))
            .addAction(0, "Snooze 1h", broadcast(context, AlarmScheduler.ACTION_HABIT_SNOOZE, reqBase + 2, doneExtras))
        runCatching { NotificationManagerCompat.from(context).notify(("habit:$habitId").hashCode(), b.build()) }
    }

    fun showSummary(context: Context, dueToday: Int, brief: String? = null, topHabitId: String? = null, topHabitName: String? = null) {
        ensureChannel(context)
        val tasksLine = if (dueToday == 0) "No tasks due today — enjoy!" else "You have $dueToday task${if (dueToday == 1) "" else "s"} due today."
        // N1: lead with the habit coach brief when there is one, then the task line.
        val body = if (!brief.isNullOrBlank()) "$brief\n$tasksLine" else tasksLine
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
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
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_ID, b.build()) }
    }

    /** U12: a plain automation notification ("phone on silent?") fired when a rule matches. */
    fun simple(context: Context, tag: String, title: String, text: String) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(tag.hashCode(), n) }
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
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Starting: $title")
            .setContentText("Track time on this block?")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(0, "▶ Start tracking", pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(("trackprompt:$taskId").hashCode(), n) }
    }

    /** N2: celebrate a habit reaching its self-chosen reward streak. */
    fun showReward(context: Context, name: String, reward: String, streak: Int) {
        ensureChannel(context)
        val text = "You hit a $streak-day streak on ‘$name’. You earned it: $reward 🎉"
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.btn_star_big_on)
            .setContentTitle("Reward unlocked! 🎁")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(("reward:$name").hashCode(), n) }
    }
}
