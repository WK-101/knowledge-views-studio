package com.todocompanion.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.R
import com.todocompanion.app.domain.Goal
import com.todocompanion.app.domain.GoalScore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * R106 — Goal Sprint. The one goal to keep in view: its completion ring, the days left in its sprint,
 * and whether you're on pace. Reads the on-device goal model (key-result / milestone completion and the
 * 12-week cycle); no standalone tracker links a goal to its live progress this way. Fully offline.
 *
 * "Top" goal = the nearest real deadline, else the first active goal.
 */
class GoalWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val goals = runCatching { app.repository.wsGoalsOnce() }.getOrDefault(emptyList())
                    .filter { !it.archived }

                // Lead with the nearest real deadline, else the first active goal.
                val goal = goals.filter { it.targetEpochDay > 0 }.minByOrNull { it.targetEpochDay }
                    ?: goals.firstOrNull()
                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    renderGoal(context, manager, id, style, goal, today)
                }
            } finally { pending.finish() }
        }
    }

    private fun renderGoal(
        context: Context, manager: AppWidgetManager, id: Int, style: WidgetStyle,
        goal: Goal?, today: Long,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_goal)
        WidgetStyle.applyListCard(views, R.id.gw_card, context, id)
        views.setTextColor(R.id.gw_label, style.accentText)
        views.setTextColor(R.id.gw_name, style.textPrimary)
        views.setTextColor(R.id.gw_sub, style.textSecondary)
        views.setTextColor(R.id.gw_pct, style.textPrimary)

        val edge = WidgetBitmaps.dp(context, 64f).toInt()
        val stroke = WidgetBitmaps.dp(context, 7f)

        if (goal == null) {
            views.setImageViewBitmap(R.id.gw_ring, WidgetBitmaps.ring(edge, stroke, 0f, style.chip, style.chip))
            views.setTextViewText(R.id.gw_pct, "🎯")
            views.setTextViewText(R.id.gw_name, "Set a goal")
            views.setTextViewText(R.id.gw_sub, "Pick one thing to sprint toward — its progress and pace show here.")
            views.setOnClickPendingIntent(R.id.gw_root, openGoals(context))
            manager.updateAppWidget(id, views)
            return
        }

        val fraction = (goal.keyResultFraction
            ?: if (goal.milestones.isEmpty()) 0.0 else goal.milestonesDone.toDouble() / goal.milestones.size)
            .coerceIn(0.0, 1.0)
        val percent = (fraction * 100).roundToInt()

        val cycle = runCatching { GoalScore.cycle(goal, today) }.getOrNull()
        val onTrack = cycle?.let { GoalScore.onTrack(fraction, it.elapsedFraction) }
        val daysLeft = when {
            goal.targetEpochDay > 0 -> (goal.targetEpochDay - today).toInt()
            cycle != null -> cycle.daysLeft
            else -> null
        }

        val fill = when {
            fraction >= 1.0 -> style.success
            onTrack == false -> style.warning
            else -> style.accent
        }
        views.setImageViewBitmap(R.id.gw_ring, WidgetBitmaps.ring(edge, stroke, fraction.toFloat(), style.chip, fill))
        views.setTextViewText(R.id.gw_pct, "$percent%")
        views.setTextViewText(R.id.gw_name, (goal.emoji.ifBlank { "🎯" }) + "  " + goal.name)

        val pace = when {
            fraction >= 1.0 -> "done ✓"
            onTrack == true -> "on pace"
            onTrack == false -> "behind pace"
            else -> null
        }
        val time = when {
            daysLeft == null -> null
            daysLeft < 0 -> "${-daysLeft}d overdue"
            daysLeft == 0 -> "due today"
            else -> "$daysLeft days left"
        }
        views.setTextViewText(R.id.gw_sub, listOfNotNull(time, pace).joinToString(" · ").ifBlank { "$percent% complete" })

        views.setOnClickPendingIntent(R.id.gw_root, openGoals(context))
        manager.updateAppWidget(id, views)
    }

    private fun openGoals(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_goals")
        }
        return PendingIntent.getActivity(context, "open_goals".hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, GoalWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, GoalWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
