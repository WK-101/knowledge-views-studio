package com.todocompanion.app.widget

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.reminders.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R108 — a fully headless focus / pomodoro / stopwatch timer, started from the Quick-bar widget's
 * Time popup with no app window. Focus in this app is a *mode of time tracking* (a running interval
 * tagged kind="focus" on the one timeline) plus a scheduled completion alarm — there is no live UI
 * dependency — so a timer can run entirely from a widget: we open the interval, post an ongoing
 * notification whose system chronometer counts up/down on its own, and set an exact alarm that fires
 * even if the app is dead. When it fires (or the user taps Stop) we finalize the interval and clear the
 * notification. Reuses [Notifications.showFocusDone] for the completion chime. Fully offline; the only
 * capability used is the notification the app already posts and the exact-alarm permission it already
 * holds — no foreground service, no new permission.
 */
object FocusTimer {
    const val ACTION_STOP = "com.todocompanion.app.action.FOCUS_TIMER_STOP"
    const val ACTION_DONE = "com.todocompanion.app.action.FOCUS_TIMER_DONE"
    const val EXTRA_ENTRY = "entryId"
    const val EXTRA_END = "endMillis"
    const val EXTRA_LABEL = "label"
    private const val NOTIF_ID = 424260
    private const val CHANNEL = "focus_timer"
    private const val ALARM_REQ = 918_299

    /** Start a session. [targetMin] 0 = open-ended stopwatch; >0 = focus / pomodoro countdown. */
    fun start(context: Context, targetMin: Int, label: String) {
        val app = context.applicationContext as? App ?: return
        app.appScope.launch {
            val actId = runCatching { app.repository.ensureFocusActivity() }.getOrNull() ?: return@launch
            val multi = runCatching { app.repository.settingsSnapshot().multiTimer }.getOrDefault(false)
            val entryId = runCatching {
                app.repository.startTimeTracking(actId, stopFirst = !multi, kind = "focus")
            }.getOrNull() ?: return@launch
            val now = System.currentTimeMillis()
            val end = if (targetMin > 0) now + targetMin * 60_000L else 0L
            withContext(Dispatchers.Main) { showNotification(context, label, entryId, end, now) }
            if (end > 0L) scheduleDone(context, end, entryId, label)
            Widgets.refreshAll(context)
        }
    }

    /** User tapped Stop (or Stop from the notification) — finalize the interval and clear everything. */
    fun stop(context: Context, entryId: String?) {
        cancelAlarm(context)
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
        val app = context.applicationContext as? App ?: return
        app.appScope.launch {
            if (!entryId.isNullOrEmpty()) runCatching { app.repository.stopTimeEntry(entryId) }
            else runCatching { app.repository.stopTimeTracking() }
            Widgets.refreshAll(context)
        }
    }

    /** The countdown elapsed — finalize the interval, clear the ongoing notification, chime. */
    fun complete(context: Context, entryId: String?) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
        val app = context.applicationContext as? App ?: return
        app.appScope.launch {
            if (!entryId.isNullOrEmpty()) runCatching { app.repository.stopTimeEntry(entryId) }
            Widgets.refreshAll(context)
        }
        runCatching { Notifications.showFocusDone(context) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Focus timer", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The running focus / pomodoro / stopwatch timer"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun showNotification(context: Context, label: String, entryId: String, endMillis: Long, startMillis: Long) {
        ensureChannel(context)
        val countDown = endMillis > 0L
        val stopPI = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, FocusTimerReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ENTRY, entryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openPI = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_ACTION, "open_focus")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText(if (countDown) "Focus in progress" else "Stopwatch running")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(if (countDown) endMillis else startMillis)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPI)
        if (countDown && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) n.setChronometerCountDown(true)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n.build()) }
    }

    private fun donePI(context: Context, entryId: String, label: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, ALARM_REQ,
            Intent(context, FocusTimerReceiver::class.java).setAction(ACTION_DONE)
                .putExtra(EXTRA_ENTRY, entryId).putExtra(EXTRA_LABEL, label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun scheduleDone(context: Context, atMillis: Long, entryId: String, label: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = donePI(context, entryId, label)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else am.setExact(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    private fun cancelAlarm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // The entry id is baked into the PendingIntent, but action+requestCode match for cancel.
        runCatching {
            am.cancel(PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, FocusTimerReceiver::class.java).setAction(ACTION_DONE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        }
    }
}

/** Handles the timer's Stop action and its completion alarm — both run headlessly. */
class FocusTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entry = intent.getStringExtra(FocusTimer.EXTRA_ENTRY)
        when (intent.action) {
            FocusTimer.ACTION_STOP -> FocusTimer.stop(context, entry)
            FocusTimer.ACTION_DONE -> FocusTimer.complete(context, entry)
        }
    }
}
