package com.todocompanion.app

import android.app.Application
import com.todocompanion.app.data.AppDatabase
import com.todocompanion.app.data.AppRepository
import com.todocompanion.app.reminders.Notifications
import com.todocompanion.app.widget.AgendaWidget
import com.todocompanion.app.widget.TodayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Application-scoped singletons. Doubles as a tiny service locator (no DI framework yet). */
class App : Application() {

    val database by lazy { AppDatabase.get(this) }
    val repository by lazy { AppRepository(database) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        // Warm the DB + settings on a background thread at process start so the first UI frame's
        // queries are already cached (opening happens off the main thread, before Compose asks).
        appScope.launch {
            val s0 = repository.settingsSnapshot(); repository.ensureSeed()
            // Seed the lock-screen-privacy flag so background notifications honour it even before any UI.
            Notifications.lockscreenPrivate = s0.lockscreenPrivacy
            // (Re)arm per-habit reminder alarms for this device's current day. Cheap; self-healing.
            runCatching { com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(this@App, repository) }
            // R38 — (re)arm dedicated-calendar event alerts for the next upcoming occurrence of each event.
            runCatching { com.todocompanion.app.reminders.AlarmScheduler.rescheduleEventAlerts(this@App, repository) }
        }
        // Keep any placed home-screen widget in sync with task changes. Delayed so this full
        // table read doesn't compete with the DB queries the first UI frame needs.
        appScope.launch {
            kotlinx.coroutines.delay(2_000)
            repository.allTasks.debounce(400).collect {
                TodayWidget.refresh(this@App); AgendaWidget.refresh(this@App)
                com.todocompanion.app.widget.StatsWidget.refresh(this@App)
                com.todocompanion.app.widget.MatrixWidget.refresh(this@App)
                com.todocompanion.app.widget.DoNextWidget.refresh(this@App)
                com.todocompanion.app.widget.Next7Widget.refresh(this@App)
            }
        }
    }
}
