package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.domain.habit.HabitStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's scheduled habits with their check state; tap a row to check it off (cycles toward the
 * target, then resets), straight from the home screen. Offline — reads and writes the local DB only.
 */
class HabitsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_habits)
            val svc = Intent(context, HabitsWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.hb_list, svc)
            views.setEmptyView(R.id.hb_list, R.id.hb_empty)
            views.setOnClickPendingIntent(R.id.hb_header, openHabits(context))
            views.setPendingIntentTemplate(R.id.hb_list, checkTemplate(context))
            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.hb_list)
        }
    }

    private fun openHabits(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_habits")
        }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun checkTemplate(context: Context): PendingIntent {
        val i = Intent(context, HabitCheckReceiver::class.java).setAction(HabitCheckReceiver.ACTION_CHECK)
        return PendingIntent.getBroadcast(context, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, HabitsWidget::class.java))
            if (ids.isEmpty()) return
            m.notifyAppWidgetViewDataChanged(ids, R.id.hb_list)
            context.sendBroadcast(Intent(context, HabitsWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}

/** Handles a tap on a habit row: cycle today's check-in, then refresh the widget. */
class HabitCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK) return
        val habitId = intent.getStringExtra(EXTRA_HABIT_ID) ?: return
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val h = app.repository.getHabitsOnce().firstOrNull { it.id == habitId } ?: return@launch
                val current = app.repository.getHabitCheckinsOnce().firstOrNull { it.habitId == habitId && it.epochDay == today }?.count ?: 0
                app.repository.cycleCheckin(habitId, today, h.targetPerDay, current)
                HabitsWidget.refresh(context)
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.todocompanion.app.action.HABIT_WIDGET_CHECK"
        const val EXTRA_HABIT_ID = "habitId"
    }
}

class HabitsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = HabitsFactory(applicationContext)
}

private class HabitsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val name: String, val done: Boolean, val progress: String)
    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}
    override fun getCount() = rows.size
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = false
    override fun getLoadingView(): RemoteViews? = null

    override fun onDataSetChanged() {
        val app = context.applicationContext as App
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val habits = runBlocking { app.repository.wsHabitsOnce() }.filter { !it.archived }
        val checkins = runBlocking { app.repository.getHabitCheckinsOnce() }
        // Respect the full frequency model (interval / times-per-week/month), skip paused habits, and
        // omit break habits (a tap here would mislog a relapse). "Done" uses meetsGoal so a target of 0
        // isn't perpetually shown as checked.
        rows = habits.filter { !it.paused && it.habitType != "break" && HabitStats.isExpectedDay(it, today) }
            .map { h ->
                val count = checkins.firstOrNull { it.habitId == h.id && it.epochDay == today }?.count ?: 0
                Row(h.id, (h.emoji?.plus(" ") ?: "") + h.name, HabitStats.meetsGoal(h, count),
                    if (h.targetPerDay > 1) "$count/${h.targetPerDay}" else "")
            }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows[position]
        return RemoteViews(context.packageName, R.layout.widget_habit_item).apply {
            setTextViewText(R.id.hi_check, if (r.done) "☑" else "☐")
            setTextViewText(R.id.hi_name, r.name)
            setTextViewText(R.id.hi_progress, r.progress)
            val fill = Intent().putExtra(HabitCheckReceiver.EXTRA_HABIT_ID, r.id)
            setOnClickFillInIntent(R.id.hi_root, fill)
        }
    }
}
