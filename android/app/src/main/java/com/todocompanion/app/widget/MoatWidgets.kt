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
import com.todocompanion.app.domain.habit.HabitInsights
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.domain.habit.InsightAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The moat, on the home screen. These widgets surface on-device cross-module analysis — a keystone
 * habit and a plain-language correlation ("days you meditate, +40% tasks") — that a standalone
 * tracker structurally cannot compute. All offline; no network, no LLM.
 */
class KeystoneWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone).toEpochDay()
                val habits = app.repository.wsHabitsOnce()
                val checkins = app.repository.getHabitCheckinsOnce()
                val tasks = app.repository.wsTasksOnce()
                val insights = runCatching { HabitInsights.compute(habits, checkins, tasks, today, zone, max = 8) }.getOrDefault(emptyList())
                val ksInsight = insights.firstOrNull { it.emoji == "🗝️" }
                // Fall back to the strongest current streak among build habits when there isn't enough
                // data yet for a keystone verdict, so the widget always names one habit to lead with.
                val ksId = (ksInsight?.action as? InsightAction.Open)?.habitId
                    ?: habits.filter { !it.archived && !it.paused && it.habitType != "break" }
                        .maxByOrNull { h ->
                            val hc = checkins.filter { it.habitId == h.id }
                            HabitStats.currentStreak(h,
                                hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet(),
                                hc.filter { it.status == "skip" }.map { it.epochDay }.toSet(),
                                hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet(), today)
                        }?.id
                val habit = habits.firstOrNull { it.id == ksId }
                val doneToday = habit != null && (checkins.firstOrNull { it.habitId == habit.id && it.epochDay == today }?.let { HabitStats.meetsGoal(habit, it.count) } == true)

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_keystone)
                    WidgetStyle.applyListCard(views, R.id.ks_card, context, id)
                    views.setTextColor(R.id.ks_label, style.accentText)
                    views.setTextColor(R.id.ks_name, style.textPrimary)
                    views.setTextColor(R.id.ks_body, style.textSecondary)
                    if (habit == null) {
                        views.setTextViewText(R.id.ks_name, "Add a couple of habits")
                        views.setTextViewText(R.id.ks_body, "Your keystone — the one that best predicts a good day — appears here as you log.")
                        views.setImageViewBitmap(R.id.ks_check, WidgetBitmaps.checkCircle((WidgetBitmaps.dp(context, 30f) * 2f).toInt(), style.chip, false))
                        views.setOnClickPendingIntent(R.id.ks_root, openHabits(context, "open_habit_add"))
                    } else {
                        views.setTextViewText(R.id.ks_name, (habit.emoji?.plus(" ") ?: "") + habit.name)
                        views.setTextViewText(R.id.ks_body, ksInsight?.text ?: "Start your day with this one — it sets the tone for the rest.")
                        val color = habit.colorArgb?.toInt() ?: style.accent
                        views.setImageViewBitmap(R.id.ks_check, WidgetBitmaps.checkCircle((WidgetBitmaps.dp(context, 30f) * 2f).toInt(), color, doneToday))
                        views.setOnClickPendingIntent(R.id.ks_check, checkIntent(context, id, habit.id))
                        views.setOnClickPendingIntent(R.id.ks_root, openHabits(context, "open_habits"))
                    }
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    private fun checkIntent(context: Context, widgetId: Int, habitId: String): PendingIntent {
        val i = Intent(context, HabitCheckReceiver::class.java).setAction(HabitCheckReceiver.ACTION_CHECK)
            .putExtra(HabitCheckReceiver.EXTRA_HABIT_ID, habitId)
        return PendingIntent.getBroadcast(context, ("ks:$habitId").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openHabits(context: Context, action: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(context, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, KeystoneWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, KeystoneWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}

/** A plain-language cross-module correlation the on-device coach found. Read-only; taps open Habits. */
class CorrelationWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone).toEpochDay()
                val habits = app.repository.wsHabitsOnce()
                val checkins = app.repository.getHabitCheckinsOnce()
                val tasks = app.repository.wsTasksOnce()
                val insights = runCatching { HabitInsights.compute(habits, checkins, tasks, today, zone, max = 8) }.getOrDefault(emptyList())
                val corr = insights.firstOrNull { it.emoji in setOf("🔗", "⚡", "📉", "🗝️") }

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_correlation)
                    WidgetStyle.applyListCard(views, R.id.co_card, context, id)
                    views.setTextColor(R.id.co_label, style.accentText)
                    views.setTextColor(R.id.co_body, style.textPrimary)
                    views.setTextViewText(R.id.co_emoji, corr?.emoji ?: "🔗")
                    views.setTextViewText(R.id.co_body, corr?.text
                        ?: "Keep logging habits and tasks — the links between them (“days you meditate, +N% tasks”) appear here.")
                    views.setOnClickPendingIntent(R.id.co_root, openHabits(context))
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
            val ids = m.getAppWidgetIds(ComponentName(context, CorrelationWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, CorrelationWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
