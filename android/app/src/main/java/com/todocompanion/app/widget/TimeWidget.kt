package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Round 13 — the Time tracker on the home screen: the running activity with a live-ticking timer and a
 * one-tap Stop, or your top activities to start with one tap. The fastest possible capture, matching the
 * widgets Tasks, Habits and Momentum already have. Offline; reads and writes the local DB only.
 */
class TimeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val running = app.repository.runningTimeEntry()
                val activities = app.repository.wsTimeActivitiesOnce().filter { !it.archived }
                    .sortedBy { it.sortOrder }
                val byId = activities.associateBy { it.id }
                val views = RemoteViews(context.packageName, R.layout.widget_time)

                if (running != null) {
                    val a = byId[running.activityId]
                    views.setTextViewText(R.id.tw_state, (a?.emoji?.plus(" ") ?: "") + (a?.name ?: "Tracking"))
                    // Chronometer counts up from the interval's start using the monotonic clock base.
                    val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - running.startMillis)
                    views.setChronometer(R.id.tw_timer, base, null, true)
                    views.setViewVisibility(R.id.tw_timer, View.VISIBLE)
                    views.setViewVisibility(R.id.tw_stop, View.VISIBLE)
                    views.setOnClickPendingIntent(R.id.tw_stop, action(context, ACTION_STOP, null, 1))
                } else {
                    views.setTextViewText(R.id.tw_state, if (activities.isEmpty()) "Add an activity in Time" else "Not tracking")
                    views.setViewVisibility(R.id.tw_timer, View.GONE)
                    views.setViewVisibility(R.id.tw_stop, View.GONE)
                }

                // Up to three start chips (the running one, if shown, doubles as a quick switch).
                val chipIds = intArrayOf(R.id.tw_a1, R.id.tw_a2, R.id.tw_a3)
                chipIds.forEachIndexed { i, vid ->
                    val a = activities.getOrNull(i)
                    if (a == null) { views.setViewVisibility(vid, View.INVISIBLE) }
                    else {
                        views.setViewVisibility(vid, View.VISIBLE)
                        val label = (a.emoji?.plus(" ") ?: "") + a.name
                        views.setTextViewText(vid, if (running?.activityId == a.id) "▶ $label" else label)
                        views.setOnClickPendingIntent(vid, action(context, ACTION_START, a.id, 100 + i))
                    }
                }
                // Tap the label opens the Time tab.
                views.setOnClickPendingIntent(R.id.tw_state, openTime(context))
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    private fun action(context: Context, act: String, activityId: String?, req: Int): PendingIntent {
        val i = Intent(context, TimeTrackReceiver::class.java).apply {
            action = act
            activityId?.let { putExtra(TimeTrackReceiver.EXTRA_ACTIVITY_ID, it) }
        }
        return PendingIntent.getBroadcast(context, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openTime(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_time")
        }
        return PendingIntent.getActivity(context, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, TimeWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, TimeWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}

/** Handles the Time widget's start/stop buttons: writes to the DB, then refreshes the widget. */
class TimeTrackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_START -> intent.getStringExtra(EXTRA_ACTIVITY_ID)?.let { actId ->
                        val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                        val multi = app.repository.settingsSnapshot().multiTimer
                        app.repository.startTimeTracking(actId, taskId = taskId, stopFirst = !multi)
                        com.todocompanion.app.reminders.AutomationRunner.onStart(context, app.repository, actId)
                    }
                    ACTION_STOP -> app.repository.stopTimeTracking()
                }
                TimeWidget.refresh(context)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_ACTIVITY_ID = "activityId"
        const val EXTRA_TASK_ID = "taskId"
    }
}

const val ACTION_START = "com.todocompanion.app.action.TIME_START"
const val ACTION_STOP = "com.todocompanion.app.action.TIME_STOP"
