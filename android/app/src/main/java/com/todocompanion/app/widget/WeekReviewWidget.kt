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
import com.todocompanion.app.domain.WeeklyReviews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields

/**
 * R107 — Weekly Review status. The weekly analogue of Close the Day: whether this ISO week's review is
 * done, and how many wins you've logged this week. Reads the local review store + day logs only; the
 * weekly ritual belongs on the home screen next to the daily one. Fully offline. Tap opens the review.
 */
class WeekReviewWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val settings = runCatching { app.repository.settingsSnapshot() }.getOrNull()
                val week = WeeklyReviews.isoWeekKey(today)
                val reviewed = settings != null && WeeklyReviews.isReviewed(settings.weeklyReviewsJson, week)

                // Wins logged this ISO week (Mon..Sun) — non-blank "three good things" across day logs.
                val monday = today.with(WeekFields.ISO.dayOfWeek(), 1)
                val startDay = monday.toEpochDay(); val endDay = monday.plusDays(6).toEpochDay()
                val wins = runCatching {
                    app.repository.dayLogsOnce().filter { it.epochDay in startDay..endDay }
                        .sumOf { l -> listOf(l.good1, l.good2, l.good3).count { it.isNotBlank() } }
                }.getOrDefault(0)

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_weekreview)
                    WidgetStyle.applyListCard(views, R.id.wkr_card, context, id)
                    views.setTextColor(R.id.wkr_label, style.accentText)
                    views.setTextColor(R.id.wkr_sub, style.textSecondary)

                    val winLine = when (wins) {
                        0 -> null
                        1 -> "1 win logged this week"
                        else -> "$wins wins logged this week"
                    }
                    if (reviewed) {
                        views.setTextViewText(R.id.wkr_glyph, "✅")
                        views.setTextViewText(R.id.wkr_state, "Reviewed")
                        views.setTextColor(R.id.wkr_state, style.success)
                        views.setTextViewText(R.id.wkr_sub, winLine ?: "Your week is wrapped up.")
                    } else {
                        views.setTextViewText(R.id.wkr_glyph, "📋")
                        views.setTextViewText(R.id.wkr_state, "Review due")
                        views.setTextColor(R.id.wkr_state, style.textPrimary)
                        views.setTextViewText(R.id.wkr_sub, winLine?.plus(" — look back") ?: "Look back on the week and set the next.")
                    }
                    views.setOnClickPendingIntent(R.id.wkr_root, openReview(context))
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    private fun openReview(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_weekreview")
        }
        return PendingIntent.getActivity(context, "open_weekreview".hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context) ?: return
            val ids = m.getAppWidgetIds(ComponentName(context, WeekReviewWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, WeekReviewWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
