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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Home-screen widget for the nearest pinned countdown (else the next upcoming one). Offline. */
class CountdownWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = LocalDate.now(ZoneId.systemDefault())
                val all = app.repository.allCountdownsOnce()
                // Prefer a pinned upcoming one; otherwise the soonest future; otherwise the latest past.
                val pick = all.filter { it.pinned }.minByOrNull { it.targetMillis }
                    ?: all.filter { Instant.ofEpochMilli(it.targetMillis).atZone(ZoneId.systemDefault()).toLocalDate() >= today }.minByOrNull { it.targetMillis }
                    ?: all.maxByOrNull { it.targetMillis }
                val views = RemoteViews(context.packageName, R.layout.widget_countdown)
                if (pick == null) {
                    views.setTextViewText(R.id.cd_title, "No countdowns")
                    views.setTextViewText(R.id.cd_days, "–")
                    views.setTextViewText(R.id.cd_label, "tap to add")
                } else {
                    val d = Instant.ofEpochMilli(pick.targetMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                    val days = ChronoUnit.DAYS.between(today, d)
                    views.setTextViewText(R.id.cd_title, (pick.emoji?.plus(" ") ?: "") + pick.title)
                    views.setTextViewText(R.id.cd_days, if (days == 0L) "TODAY" else kotlin.math.abs(days).toString())
                    views.setTextViewText(R.id.cd_label, when { days == 0L -> "today"; days > 0 -> "days left"; else -> "days ago" })
                    pick.colorArgb?.let { views.setTextColor(R.id.cd_days, it.toInt()) }
                }
                views.setOnClickPendingIntent(R.id.cd_root, openIntent(context))
                ids.forEach { manager.updateAppWidget(it, views) }
            } finally { pending.finish() }
        }
    }

    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_countdowns")
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, CountdownWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(Intent(context, CountdownWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            })
        }
    }
}
