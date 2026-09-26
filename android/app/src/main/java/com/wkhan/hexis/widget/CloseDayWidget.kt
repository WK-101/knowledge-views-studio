package com.wkhan.hexis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wkhan.hexis.App
import com.wkhan.hexis.MainActivity
import com.wkhan.hexis.R
import com.wkhan.hexis.domain.DailyQuestions
import com.wkhan.hexis.domain.ReviewCadence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * R106 — Close the Day. The evening shutdown ritual on the home screen: whether today is closed yet,
 * a nudge to reflect if not, and — once closed — tomorrow's one focus. Reads the local day log only;
 * a standalone journal can't tie the shutdown to the day's tasks and habits the way this app can.
 * Fully offline. Tap opens the close-the-day flow.
 */
class CloseDayWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                val log = runCatching { app.repository.dayLogFor(today) }.getOrNull()
                val closed = log != null && ReviewCadence.isReviewed(log)
                val answered = runCatching {
                    val qs = DailyQuestions.parseQuestions(app.repository.settingsSnapshot().dailyQuestionsJson)
                    val scores = DailyQuestions.parseScores(log?.dailyScoresJson ?: "")
                    qs.size to qs.count { scores[it.id] != null }
                }.getOrDefault(0 to 0)
                val tomorrow = log?.tomorrowFocus?.takeIf { it.isNotBlank() }

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_closeday)
                    WidgetStyle.applyListCard(views, R.id.cl_card, context, id)
                    views.setTextColor(R.id.cl_label, style.accentText)
                    views.setTextColor(R.id.cl_sub, style.textSecondary)

                    if (closed) {
                        views.setTextViewText(R.id.cl_glyph, "✅")
                        views.setTextViewText(R.id.cl_state, "Day closed")
                        views.setTextColor(R.id.cl_state, style.success)
                        views.setTextViewText(R.id.cl_sub, tomorrow?.let { "Tomorrow: $it" } ?: "Nicely done — rest up.")
                    } else {
                        views.setTextViewText(R.id.cl_glyph, "🌙")
                        views.setTextViewText(R.id.cl_state, "Not closed yet")
                        views.setTextColor(R.id.cl_state, style.textPrimary)
                        val (total, done) = answered
                        views.setTextViewText(R.id.cl_sub, when {
                            total > 0 && done > 0 -> "$done of $total reflections done — finish the day."
                            else -> "Take two minutes to reflect and set tomorrow."
                        })
                    }
                    views.setOnClickPendingIntent(R.id.cl_root, openClose(context))
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    private fun openClose(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_close_day")
        }
        return PendingIntent.getActivity(context, "open_close_day".hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) = Widgets.broadcastUpdate(context, CloseDayWidget::class.java)
    }
}
