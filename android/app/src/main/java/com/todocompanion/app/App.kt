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
        // R71 — capture ANY uncaught crash to a file you can retrieve without a PC, then defer to the
        // normal handler. If the app ever fails to start, open a file manager and read:
        //   Android/data/com.wkhan.kairo/files/last_crash.txt
        // and send it over — it contains the exact stack trace and line. Also mirrored to logcat (tag "KairoCrash").
        run {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, err ->
                runCatching {
                    val trace = android.util.Log.getStackTraceString(err)
                    android.util.Log.e("KairoCrash", "Uncaught on ${thread.name}", err)
                    val dir = getExternalFilesDir(null) ?: filesDir
                    java.io.File(dir, "last_crash.txt").writeText(
                        "Kairo crash @ ${java.util.Date()}\nthread=${thread.name}\n\n$trace"
                    )
                }
                prev?.uncaughtException(thread, err)
            }
        }
        Notifications.ensureChannel(this)
        // Warm the DB + settings on a background thread at process start so the first UI frame's
        // queries are already cached (opening happens off the main thread, before Compose asks).
        appScope.launch {
            val s0 = repository.settingsSnapshot(); repository.ensureSeed()
            // Seed the lock-screen-privacy flag so background notifications honour it even before any UI.
            Notifications.lockscreenPrivate = s0.lockscreenPrivacy
            // R59 — seed the snooze duration every notification's Snooze action uses.
            Notifications.snoozeMinutes = s0.defaultSnoozeMin
            // R81 — seed the chosen reminder sound so background notifications use the right channel.
            Notifications.reminderSoundSpec = s0.reminderSound
            Notifications.ensureChannel(this@App)
            // R59 (Wave 2) — seed quiet hours so background reminders defer to morning even before any UI.
            com.todocompanion.app.reminders.AlarmScheduler.quietEnabled = s0.quietHoursEnabled
            com.todocompanion.app.reminders.AlarmScheduler.quietStartHour = s0.quietStartHour
            com.todocompanion.app.reminders.AlarmScheduler.quietEndHour = s0.quietEndHour
            // (Re)arm per-habit reminder alarms for this device's current day. Cheap; self-healing.
            runCatching { com.todocompanion.app.reminders.AlarmScheduler.scheduleHabitReminders(this@App, repository) }
            // (Re)arm press-play routine daily nudges the same way.
            runCatching { com.todocompanion.app.reminders.AlarmScheduler.scheduleRoutineReminders(this@App, repository) }
            // R38 — (re)arm dedicated-calendar event alerts for the next upcoming occurrence of each event.
            runCatching { com.todocompanion.app.reminders.AlarmScheduler.rescheduleEventAlerts(this@App, repository) }
            // Track 3.4 — (re)arm the reveal notification for each still-sealed letter to your future self.
            runCatching { com.todocompanion.app.reminders.AlarmScheduler.rescheduleSealedLetters(this@App, repository) }
            // R105 — arm the daily midnight widget refresh so date-sensitive widgets roll over on time.
            runCatching { com.todocompanion.app.widget.Widgets.scheduleMidnight(this@App) }
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
                // R104 — these were previously only poll-refreshed and could go stale.
                com.todocompanion.app.widget.RecordWidget.refresh(this@App)
                com.todocompanion.app.widget.MomentumWidget.refresh(this@App)
                com.todocompanion.app.widget.DayWidget.refresh(this@App)
            }
        }
    }
}
