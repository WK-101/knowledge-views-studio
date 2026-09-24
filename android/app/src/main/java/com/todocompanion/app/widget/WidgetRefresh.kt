package com.todocompanion.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.todocompanion.app.MainActivity
import java.time.LocalDate
import java.time.ZoneId

/**
 * R105 — keeps the date-sensitive widgets correct across the midnight rollover and manual
 * time / timezone / locale changes, without leaning on the (battery-unfriendly) update poll.
 * A single daily alarm re-renders every widget at local midnight and re-arms itself; the manifest
 * receiver also catches time/timezone/locale changes. Fully offline, no new dependency.
 */
object Widgets {
    /** Re-render every placed widget. Safe to call from any thread; each is guarded. */
    fun refreshAll(context: Context) {
        runCatching { AgendaWidget.refresh(context) }
        runCatching { DayWidget.refresh(context) }
        runCatching { DoNextWidget.refresh(context) }
        runCatching { Next7Widget.refresh(context) }
        runCatching { RecordWidget.refresh(context) }
        runCatching { MatrixWidget.refresh(context) }
        runCatching { MomentumWidget.refresh(context) }
        runCatching { CountdownWidget.refresh(context) }
        runCatching { HabitsWidget.refresh(context) }
        runCatching { HabitStatsWidget.refresh(context) }
        runCatching { HabitZeroWidget.refresh(context) }
        runCatching { WeekRowWidget.refresh(context) }
        runCatching { HabitInsightWidget.refresh(context) }
        runCatching { TimeWidget.refresh(context) }
    }

    /** Re-render every habit widget after a check-in / habit change. Safe from any thread. */
    fun refreshHabitWidgets(context: Context) {
        runCatching { HabitsWidget.refresh(context) }
        runCatching { HabitStatsWidget.refresh(context) }
        runCatching { HabitZeroWidget.refresh(context) }
        runCatching { WeekRowWidget.refresh(context) }
        runCatching { HabitInsightWidget.refresh(context) }
        runCatching { MomentumWidget.refresh(context) }
    }

    // ---- shared plumbing (used by providers/receivers instead of copy-pasting) ----

    /** Re-render every placed instance of [cls]; optionally poke a collection view to reload first. */
    fun broadcastUpdate(ctx: Context, cls: Class<out AppWidgetProvider>, listViewId: Int? = null) {
        val m = AppWidgetManager.getInstance(ctx) ?: return
        val ids = m.getAppWidgetIds(ComponentName(ctx, cls))
        if (ids.isEmpty()) return
        listViewId?.let { m.notifyAppWidgetViewDataChanged(ids, it) }
        ctx.sendBroadcast(Intent(ctx, cls).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        })
    }

    /** A PendingIntent that opens MainActivity, optionally with an EXTRA_ACTION deep-link. */
    fun appIntent(ctx: Context, action: String?, reqCode: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
        }
        return PendingIntent.getActivity(ctx, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A PendingIntent that opens the translucent quick-capture popup (the whole app never comes forward). */
    fun captureIntent(ctx: Context, reqCode: Int): PendingIntent {
        val i = Intent(ctx, QuickCaptureActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return PendingIntent.getActivity(ctx, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The widget's current min-height in dp (0 = unknown) — the input to size-responsive layouts. */
    fun minHeightDp(mgr: AppWidgetManager, id: Int): Int =
        runCatching { mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) }.getOrDefault(0)

    /** (Re)arm the next local-midnight refresh. Idempotent — safe to call on every app start. */
    fun scheduleMidnight(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val zone = ZoneId.systemDefault()
        val nextMidnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            context, 0xDA17,
            Intent(context, WidgetRefreshReceiver::class.java).setAction(WidgetRefreshReceiver.ACTION_MIDNIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact, non-wakeup: a rollover a few minutes late (or when the device next wakes) is
        // invisible, and it costs no battery overnight.
        runCatching { am.set(AlarmManager.RTC, nextMidnight, pi) }
    }
}

/** Fires the midnight refresh (and re-arms it) and catches time / timezone / locale changes. */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Widgets.refreshAll(context)
        Widgets.scheduleMidnight(context)
    }

    companion object {
        const val ACTION_MIDNIGHT = "com.todocompanion.app.action.WIDGET_MIDNIGHT"
    }
}
