package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
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
 * Today's scheduled habits with their check state; tap the circle to check it off (cycles toward the
 * target, then resets), straight from the home screen. Each row carries the habit's own colour, its
 * current streak, and numeric progress. Offline — reads and writes the local DB only.
 */
class HabitsWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> render(context, manager, id) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle) {
        render(context, manager, id)
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val style = WidgetStyle.resolve(context, id)
        val views = RemoteViews(context.packageName, R.layout.widget_habits)
        val svc = Intent(context, HabitsWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.hb_list, svc)
        views.setEmptyView(R.id.hb_list, R.id.hb_empty)
        views.setPendingIntentTemplate(R.id.hb_list, checkTemplate(context))

        // Card theme + opacity, and forced-theme text colours for the chrome the list can't reach.
        WidgetStyle.applyListCard(views, R.id.hb_card, context, id)
        views.setTextColor(R.id.hb_title, style.textPrimary)
        views.setTextColor(R.id.hb_count, style.accentText)
        views.setTextColor(R.id.hb_empty_msg, style.textSecondary)
        views.setTextColor(R.id.hb_empty_cta, style.accentText)

        views.setOnClickPendingIntent(R.id.hb_title, activity(context, id * 10 + 1, "open_habits"))
        views.setOnClickPendingIntent(R.id.hb_add, activity(context, id * 10 + 2, "open_habit_add"))
        views.setOnClickPendingIntent(R.id.hb_empty, activity(context, id * 10 + 3, "open_habit_add"))

        // Size-responsive: on a short (≈1-row-tall) placement, drop the header so the habit rows get
        // every pixel and the widget reads as a clean check-off strip.
        val minH = runCatching { manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) }.getOrDefault(0)
        views.setViewVisibility(R.id.hb_header, if (minH in 1..99) android.view.View.GONE else android.view.View.VISIBLE)

        manager.updateAppWidget(id, views)
        manager.notifyAppWidgetViewDataChanged(id, R.id.hb_list)

        // The header "done / total" tally needs a DB read; fill it in asynchronously so the list
        // itself never waits on it.
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val habits = app.repository.wsHabitsOnce().filter { !it.archived && !it.paused && it.habitType != "break" && HabitStats.isExpectedDay(it, today) }
                val checkins = app.repository.getHabitCheckinsOnce()
                var done = 0
                habits.forEach { h ->
                    val count = checkins.firstOrNull { it.habitId == h.id && it.epochDay == today }?.count ?: 0
                    if (HabitStats.meetsGoal(h, count)) done++
                }
                val countViews = RemoteViews(context.packageName, R.layout.widget_habits)
                countViews.setTextViewText(R.id.hb_count, if (habits.isEmpty()) "" else "$done/${habits.size}")
                manager.partiallyUpdateAppWidget(id, countViews)
            } catch (_: Throwable) {
            } finally { pending.finish() }
        }
    }

    private fun activity(context: Context, code: Int, action: String): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

        fun updateOne(context: Context, id: Int) {
            val m = AppWidgetManager.getInstance(context) ?: return
            HabitsWidget().render(context, m, id)
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
                // A numeric/timed habit opens the value popup (never a blind +1); a yes/no habit toggles in place.
                val timed = h.unit?.startsWith("min") == true
                val numeric = !timed && (h.targetPerDay > 1 || h.unit != null || h.clickIncrement > 1)
                if (timed || numeric) {
                    context.startActivity(HabitQuickLogActivity.intent(context, habitId))
                } else {
                    val current = app.repository.getHabitCheckinsOnce().firstOrNull { it.habitId == habitId && it.epochDay == today }?.count ?: 0
                    app.repository.cycleCheckin(habitId, today, h.targetPerDay, current)
                    Widgets.refreshHabitWidgets(context)
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_CHECK = "com.todocompanion.app.action.HABIT_WIDGET_CHECK"
        const val EXTRA_HABIT_ID = "habitId"
    }
}

class HabitsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return HabitsFactory(applicationContext, id)
    }
}

private class HabitsFactory(private val context: Context, private val widgetId: Int) : RemoteViewsService.RemoteViewsFactory {
    private data class Row(val id: String, val name: String, val color: Int, val done: Boolean, val progress: String, val streak: Int)
    private var rows: List<Row> = emptyList()
    private var style: WidgetStyle = WidgetStyle.resolve(context)

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
        style = WidgetStyle.resolve(context, widgetId)
        val habits = runBlocking { app.repository.wsHabitsOnce() }.filter { !it.archived }
        val checkins = runBlocking { app.repository.getHabitCheckinsOnce() }
        // Respect the full frequency model (interval / times-per-week/month), skip paused habits, and
        // omit break habits (a tap here would mislog a relapse). "Done" uses meetsGoal so a target of 0
        // isn't perpetually shown as checked.
        rows = habits.filter { !it.paused && it.habitType != "break" && HabitStats.isExpectedDay(it, today) }
            .map { h ->
                val hc = checkins.filter { it.habitId == h.id }
                val count = hc.firstOrNull { it.epochDay == today }?.count ?: 0
                val streak = HabitStats.streakFor(h, hc, today)
                val unit = h.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                Row(
                    id = h.id,
                    name = (h.emoji?.plus(" ") ?: "") + h.name,
                    color = h.colorArgb?.toInt() ?: style.teal,
                    done = HabitStats.meetsGoal(h, count),
                    progress = if (h.targetPerDay > 1) "$count/${h.targetPerDay}$unit" else "",
                    streak = streak,
                )
            }
    }

    override fun getViewAt(position: Int): RemoteViews {
        val r = rows[position]
        return RemoteViews(context.packageName, R.layout.widget_habit_item).apply {
            val edge = (WidgetBitmaps.dp(context, 26f) * 2f).toInt()
            setImageViewBitmap(R.id.hi_check, WidgetBitmaps.checkCircle(edge, r.color, r.done))
            setTextViewText(R.id.hi_name, r.name)
            setTextColor(R.id.hi_name, if (r.done) style.textSecondary else style.textPrimary)
            setTextViewTextSize(R.id.hi_name, TypedValue.COMPLEX_UNIT_SP, style.sp(14f))
            setTextViewText(R.id.hi_streak, if (r.streak >= 2) "🔥 ${r.streak}" else "")
            setTextColor(R.id.hi_streak, style.warning)
            setTextViewText(R.id.hi_progress, r.progress)
            setTextColor(R.id.hi_progress, style.textSecondary)
            val vpad = ((if (style.compact) 3 else 6) * context.resources.displayMetrics.density).toInt()
            setViewPadding(R.id.hi_root, 0, vpad, 0, vpad)
            setContentDescription(R.id.hi_check, (if (r.done) "Done: " else "Check off: ") + r.name)
            setOnClickFillInIntent(R.id.hi_root, Intent().putExtra(HabitCheckReceiver.EXTRA_HABIT_ID, r.id))
        }
    }
}
