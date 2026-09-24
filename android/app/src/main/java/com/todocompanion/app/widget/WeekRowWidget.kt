package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
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

/**
 * One habit's current week as seven tappable cells (Mon–Sun): tap any day up to today to check it
 * off or clear it, right from the home screen — the "don't break the chain" surface. Shows the top
 * habit; its streak rides along. Offline; reads and writes the local DB.
 */
class WeekRowWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, manager, id) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        render(context, manager, id)
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val style = WidgetStyle.resolve(context, id)
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                // Honour a pinned habit from the config screen; fall back to the first build habit.
                val eligible = app.repository.wsHabitsOnce().filter { !it.archived && !it.paused && it.habitType != "break" }
                val pinned = WidgetPrefs.habitId(context, id)
                val habit = pinned?.let { p -> eligible.firstOrNull { it.id == p } } ?: eligible.firstOrNull()
                val views = RemoteViews(context.packageName, R.layout.widget_weekrow)
                WidgetStyle.applyListCard(views, R.id.wr_card, context, id)

                val cellIds = intArrayOf(R.id.wr_c0, R.id.wr_c1, R.id.wr_c2, R.id.wr_c3, R.id.wr_c4, R.id.wr_c5, R.id.wr_c6)
                val lblIds = intArrayOf(R.id.wr_l0, R.id.wr_l1, R.id.wr_l2, R.id.wr_l3, R.id.wr_l4, R.id.wr_l5, R.id.wr_l6)

                if (habit == null) {
                    views.setTextViewText(R.id.wr_title, "No habits yet")
                    views.setTextColor(R.id.wr_title, style.textPrimary)
                    views.setTextViewText(R.id.wr_streak, "")
                    val edge = (WidgetBitmaps.dp(context, 26f) * 2f).toInt()
                    cellIds.forEach { views.setImageViewBitmap(it, WidgetBitmaps.checkCircle(edge, style.chip, false)) }
                    lblIds.forEach { views.setTextColor(it, style.textTertiary) }
                    views.setOnClickPendingIntent(R.id.wr_root, openHabits(context, "open_habit_add"))
                    manager.updateAppWidget(id, views)
                    return@launch
                }

                val checkins = app.repository.getHabitCheckinsOnce().filter { it.habitId == habit.id }
                val ds = HabitStats.daySets(habit, checkins)
                val streak = HabitStats.currentStreak(habit, ds.done, ds.skip, ds.relapse, today)

                views.setTextViewText(R.id.wr_title, (habit.emoji?.plus(" ") ?: "") + habit.name)
                views.setTextColor(R.id.wr_title, style.textPrimary)
                views.setTextViewText(R.id.wr_streak, if (streak >= 2) "🔥 $streak" else "")
                views.setTextColor(R.id.wr_streak, style.warning)

                val dow = LocalDate.ofEpochDay(today).dayOfWeek.value // 1..7 (Mon..Sun)
                val monday = today - (dow - 1)
                val edge = (WidgetBitmaps.dp(context, 30f) * 2f).toInt()
                val labels = arrayOf("M", "T", "W", "T", "F", "S", "S")
                for (i in 0..6) {
                    val day = monday + i
                    val future = day > today
                    val done = day in ds.done
                    val color = if (day in ds.skip) style.textTertiary else (habit.colorArgb?.toInt() ?: style.teal)
                    views.setTextViewText(lblIds[i], labels[i])
                    views.setTextColor(lblIds[i], if (day == today) style.accentText else style.textTertiary)
                    views.setImageViewBitmap(cellIds[i], WidgetBitmaps.checkCircle(edge, if (future) style.chip else color, done))
                    if (future) {
                        views.setOnClickPendingIntent(cellIds[i], null)
                    } else {
                        views.setOnClickPendingIntent(cellIds[i], toggleIntent(context, id, habit.id, day, i))
                    }
                }
                views.setOnClickPendingIntent(R.id.wr_title, openHabits(context, "open_habits"))
                manager.updateAppWidget(id, views)
            } catch (_: Throwable) {
            } finally { pending.finish() }
        }
    }

    private fun openHabits(context: Context, action: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(context, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun toggleIntent(context: Context, widgetId: Int, habitId: String, day: Long, slot: Int): PendingIntent {
        val i = Intent(context, WeekRowReceiver::class.java).setAction(WeekRowReceiver.ACTION_TOGGLE).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(WeekRowReceiver.EXTRA_HABIT, habitId)
            putExtra(WeekRowReceiver.EXTRA_DAY, day)
        }
        val code = (widgetId * 31 + slot)
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) = Widgets.broadcastUpdate(context, WeekRowWidget::class.java)

        fun updateOne(context: Context, id: Int) {
            val m = AppWidgetManager.getInstance(context) ?: return
            WeekRowWidget().render(context, m, id)
        }
    }
}

/** Toggles one day's check-in for the week-row widget's habit, then refreshes. */
class WeekRowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) return
        val habitId = intent.getStringExtra(EXTRA_HABIT) ?: return
        val day = intent.getLongExtra(EXTRA_DAY, -1L); if (day < 0) return
        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val h = app.repository.getHabitsOnce().firstOrNull { it.id == habitId } ?: return@launch
                // Numeric/timed → the value popup for that day; yes/no → toggle the cell in place.
                val timed = h.unit?.startsWith("min") == true
                val numeric = !timed && (h.targetPerDay > 1 || h.unit != null || h.clickIncrement > 1)
                if (timed || numeric) {
                    context.startActivity(HabitQuickLogActivity.intent(context, habitId, day))
                } else {
                    val current = app.repository.getHabitCheckinsOnce().firstOrNull { it.habitId == habitId && it.epochDay == day }?.count ?: 0
                    app.repository.cycleCheckin(habitId, day, h.targetPerDay, current)
                    Widgets.refreshHabitWidgets(context)
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.todocompanion.app.action.WEEKROW_TOGGLE"
        const val EXTRA_HABIT = "habitId"
        const val EXTRA_DAY = "day"
    }
}
