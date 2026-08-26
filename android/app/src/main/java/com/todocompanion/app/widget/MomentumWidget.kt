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
import com.todocompanion.app.domain.task.TaskReliability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * R1: the unified "Momentum" on the home screen — one score blending habit strength, task reliability
 * and focus, the same number the Momentum dashboard shows. Offline; reads the local DB only.
 */
class MomentumWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone).toEpochDay()
                val habits = app.repository.getHabitsOnce().filter { !it.archived }
                val checkins = app.repository.getHabitCheckinsOnce()
                val strengths = habits.map { h ->
                    val d = checkins.filter { it.habitId == h.id && it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
                    val s = checkins.filter { it.habitId == h.id && it.status == "skip" }.map { it.epochDay }.toSet()
                    val r = checkins.filter { it.habitId == h.id && HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
                    HabitStats.strength(h, d, s, r, today)
                }
                val habitStrength = if (strengths.isEmpty()) null else strengths.average().toInt()
                val now = System.currentTimeMillis()
                val activities = app.repository.getActivitiesOnce()
                val relVals = app.repository.allTasksOnce().filter { !it.rrule.isNullOrBlank() && !it.trashed }
                    .mapNotNull { TaskReliability.score(it, activities, now, zone)?.score }
                val taskRel = if (relVals.isEmpty()) null else relVals.average().toInt()
                val weekDays = (0 until 7).map { today - it }.toSet()
                val focusWeek = app.repository.getFocusSessionsOnce().filter { it.epochDay in weekDays }.sumOf { it.minutes }
                val parts = buildList {
                    habitStrength?.let { add(it.toDouble() to 0.5) }
                    taskRel?.let { add(it.toDouble() to 0.35) }
                    add((focusWeek.coerceAtMost(300) / 300.0 * 100) to 0.15)
                }
                val wsum = parts.sumOf { it.second }
                val momentum = if (wsum == 0.0) 0 else (parts.sumOf { it.first * it.second } / wsum).toInt()
                val sub = buildString {
                    habitStrength?.let { append("habits $it") }
                    taskRel?.let { if (isNotEmpty()) append(" · "); append("reliab $it%") }
                }.ifBlank { "start building momentum" }

                val views = RemoteViews(context.packageName, R.layout.widget_momentum)
                views.setTextViewText(R.id.mo_score, "$momentum")
                views.setTextViewText(R.id.mo_sub, sub)
                views.setOnClickPendingIntent(R.id.mo_root, openMomentum(context))
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    private fun openMomentum(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_momentum")
        }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, MomentumWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, MomentumWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
