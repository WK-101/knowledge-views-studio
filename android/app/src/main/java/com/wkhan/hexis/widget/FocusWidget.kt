package com.wkhan.hexis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.wkhan.hexis.App
import com.wkhan.hexis.MainActivity
import com.wkhan.hexis.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * R106 — Focus. A one-tap start for a focus session and a live count-up while one runs. Focus here is
 * a mode of the offline time timeline (a running interval tagged kind="focus") driven by [FocusTimer] —
 * no foreground service, no new permission. The running interval stores only its start, so the widget
 * shows elapsed (a self-counting Chronometer needs no live timer on our side); the completion chime and
 * countdown live in FocusTimer's own notification + alarm. Fully offline.
 */
class FocusWidget : BaseWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as App
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val running = runCatching { app.repository.runningTimeEntry() }.getOrNull()
                    ?.takeIf { it.kind == "focus" }

                ids.forEach { id ->
                    val style = WidgetStyle.resolve(context, id)
                    val views = RemoteViews(context.packageName, R.layout.widget_focus)
                    WidgetStyle.applyListCard(views, R.id.fw_card, context, id)
                    views.setTextColor(R.id.fw_label, style.accentText)

                    if (running != null) {
                        // Running: elapsed count-up from the interval start; a Stop button.
                        views.setViewVisibility(R.id.fw_chrono, View.VISIBLE)
                        views.setViewVisibility(R.id.fw_idle, View.GONE)
                        views.setViewVisibility(R.id.fw_presets, View.GONE)
                        views.setViewVisibility(R.id.fw_stop, View.VISIBLE)
                        views.setTextColor(R.id.fw_chrono, style.textPrimary)
                        val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - running.startMillis)
                        views.setChronometer(R.id.fw_chrono, base, null, true)
                        views.setOnClickPendingIntent(R.id.fw_stop, action(context, FocusTimer.ACTION_STOP, 0, running.id))
                    } else {
                        // Idle: three tap-to-start presets.
                        views.setViewVisibility(R.id.fw_chrono, View.GONE)
                        views.setViewVisibility(R.id.fw_idle, View.VISIBLE)
                        views.setViewVisibility(R.id.fw_presets, View.VISIBLE)
                        views.setViewVisibility(R.id.fw_stop, View.GONE)
                        views.setTextColor(R.id.fw_idle, style.textSecondary)
                        views.setOnClickPendingIntent(R.id.fw_p1, action(context, FocusTimer.ACTION_START, 25, null))
                        views.setOnClickPendingIntent(R.id.fw_p2, action(context, FocusTimer.ACTION_START, 50, null))
                        views.setOnClickPendingIntent(R.id.fw_p3, action(context, FocusTimer.ACTION_START, 90, null))
                    }
                    views.setOnClickPendingIntent(R.id.fw_root, openFocus(context))
                    manager.updateAppWidget(id, views)
                }
            } finally { pending.finish() }
        }
    }

    /** A broadcast to FocusTimerReceiver: start a preset (targetMin) or stop the running session. */
    private fun action(context: Context, act: String, targetMin: Int, entryId: String?): PendingIntent {
        val i = Intent(context, FocusTimerReceiver::class.java).setAction(act)
        if (act == FocusTimer.ACTION_START) i.putExtra(FocusTimer.EXTRA_TARGET, targetMin).putExtra(FocusTimer.EXTRA_LABEL, "Focus · ${targetMin}m")
        if (entryId != null) i.putExtra(FocusTimer.EXTRA_ENTRY, entryId)
        return PendingIntent.getBroadcast(context, ("fw:$act:$targetMin").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openFocus(context: Context): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ACTION, "open_focus")
        }
        return PendingIntent.getActivity(context, "open_focus".hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun refresh(context: Context) = Widgets.broadcastUpdate(context, FocusWidget::class.java)
    }
}
