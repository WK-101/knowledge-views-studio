package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
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

/** Your live streaks, longest first — the "don't break the chain" leaderboard. Offline. */
class StreaksWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val habits = app.repository.wsHabitsOnce().filter { !it.archived && !it.paused }
                val checkins = app.repository.getHabitCheckinsOnce()
                val byHabit = checkins.groupBy { it.habitId }
                data class Row(val name: String, val streak: Int, val quit: Boolean)
                val rows = habits.mapNotNull { h ->
                    val hc = byHabit[h.id] ?: emptyList()
                    val doneDays = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                    val skipDays = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
                    val relapse = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                    val s = HabitStats.currentStreak(h, doneDays, skipDays, relapse, today)
                    if (s >= 1) Row((h.emoji?.plus(" ") ?: "") + h.name, s, h.habitType == "break") else null
                }.sortedByDescending { it.streak }.take(6)

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_streaks)
                    WidgetStyle.applyListCard(views, R.id.st_card, context, id)
                    views.setTextColor(R.id.st_title, style.textPrimary)
                    val rowIds = intArrayOf(R.id.st_r0, R.id.st_r1, R.id.st_r2, R.id.st_r3, R.id.st_r4, R.id.st_r5)
                    for (i in rowIds.indices) {
                        val r = rows.getOrNull(i)
                        if (r == null) {
                            views.setViewVisibility(rowIds[i], if (i == 0 && rows.isEmpty()) View.VISIBLE else View.GONE)
                            if (i == 0 && rows.isEmpty()) {
                                views.setTextViewText(rowIds[0], "No active streaks yet — check one off today.")
                                views.setTextColor(rowIds[0], style.textSecondary)
                            }
                        } else {
                            views.setViewVisibility(rowIds[i], View.VISIBLE)
                            val flame = if (r.quit) "🧊" else "🔥"
                            val unit = if (r.streak == 1) "day" else "days"
                            views.setTextViewText(rowIds[i], "$flame ${r.streak} $unit   ${r.name}")
                            views.setTextColor(rowIds[i], style.textPrimary)
                        }
                    }
                    views.setOnClickPendingIntent(R.id.st_root, openHabits(context))
                    manager.updateAppWidget(id, views)
                }
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
            val ids = m.getAppWidgetIds(ComponentName(context, StreaksWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, StreaksWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
