package com.todocompanion.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        runCatching { TodayWidget.refresh(context) }
        runCatching { AgendaWidget.refresh(context) }
        runCatching { DayWidget.refresh(context) }
        runCatching { DoNextWidget.refresh(context) }
        runCatching { Next7Widget.refresh(context) }
        runCatching { RecordWidget.refresh(context) }
        runCatching { StatsWidget.refresh(context) }
        runCatching { MatrixWidget.refresh(context) }
        runCatching { MomentumWidget.refresh(context) }
        runCatching { CountdownWidget.refresh(context) }
        runCatching { HabitsWidget.refresh(context) }
        runCatching { HabitStatsWidget.refresh(context) }
        runCatching { TimeWidget.refresh(context) }
    }

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
