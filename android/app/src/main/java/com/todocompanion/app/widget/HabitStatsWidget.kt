package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.domain.habit.HabitStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** At-a-glance habits: how many are done today and your best current streak. Offline; reads local DB. */
class HabitStatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val habits = app.repository.getHabitsOnce().filter { !it.archived && !it.paused }
                val checkins = app.repository.getHabitCheckinsOnce()
                var due = 0; var done = 0; var bestStreak = 0
                habits.forEach { h ->
                    val hc = checkins.filter { it.habitId == h.id }
                    val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                    val skipDays = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
                    val relapse = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                    val todayCount = hc.firstOrNull { it.epochDay == today }?.count ?: 0
                    val scheduled = HabitStats.isExpectedDay(h, today) || h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH
                    if (scheduled) { due++; if (!HabitStats.dueToday(h, today, doneDays, todayCount)) done++ }
                    bestStreak = maxOf(bestStreak, HabitStats.currentStreak(h, doneDays, skipDays, relapse, today))
                }
                val views = RemoteViews(context.packageName, R.layout.widget_habitstats)
                views.setTextViewText(R.id.hs_done, "$done/$due")
                views.setTextViewText(R.id.hs_streak, if (bestStreak > 0) "🔥 $bestStreak best streak" else "Start a streak")
                views.setOnClickPendingIntent(R.id.hs_root, openHabits(context))
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    private fun openHabits(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_habits")
        }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, HabitStatsWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, HabitStatsWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
