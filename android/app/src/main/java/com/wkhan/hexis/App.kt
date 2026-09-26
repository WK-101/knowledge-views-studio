package com.wkhan.hexis

import android.app.Application
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.reminders.Notifications
import com.wkhan.hexis.widget.AgendaWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Application-scoped singletons. Doubles as a tiny service locator (no DI framework yet). */
class App : Application() {

    val database by lazy { AppDatabase.get(this) }
    val repository by lazy { AppRepository(database, this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // R71 / SEC (R2-C) — mirror ANY uncaught crash to logcat (tag "HexisCrash"), then defer to the
        // platform handler. We deliberately do NOT persist the trace to disk: a stack trace can
        // incidentally carry user-derived strings (a note title, an attachment path), so it stays only in
        // the live logcat stream (`adb logcat`) and is never written to a file that could linger on-device.
        run {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, err ->
                runCatching { android.util.Log.e("HexisCrash", "Uncaught on ${thread.name}", err) }
                prev?.uncaughtException(thread, err)
            }
        }
        Notifications.ensureChannel(this)
        // Warm the DB + settings on a background thread at process start so the first UI frame's
        // queries are already cached (opening happens off the main thread, before Compose asks).
        appScope.launch {
            // SEC (R2-A/H2) — one-time move of any legacy cleartext sync passphrase out of the DB settings
            // table into the KeyStore-wrapped SecurePrefs, before the first snapshot reads it.
            runCatching { repository.migrateSyncPassToSecurePrefs() }
            val s0 = repository.settingsSnapshot(); repository.ensureSeed()
            // W3 (goals→Room, Increment 2) — one-time, idempotent safety net for the JSON→table flip: adopt any
            // goal/review still living only in the legacy settings-JSON into the table (e.g. one created on an
            // Increment-1 build before the flip). Additive; a no-op once everything's in the table.
            runCatching { repository.reconcileGoalsFromLegacyJson(s0.goalsJson, s0.goalReviewsJson) }
            // W3 (routines→Room, Increment 2) — same idempotent safety net for the routines flip.
            runCatching { repository.reconcileRoutinesFromLegacyJson(s0.routinesJson, s0.routineRunsJson) }
            // Seed the lock-screen-privacy flag so background notifications honour it even before any UI.
            Notifications.lockscreenPrivate = s0.lockscreenPrivacy
            // R59 — seed the snooze duration every notification's Snooze action uses.
            Notifications.snoozeMinutes = s0.defaultSnoozeMin
            // R81 — seed the chosen reminder sound so background notifications use the right channel.
            Notifications.reminderSoundSpec = s0.reminderSound
            Notifications.ensureChannel(this@App)
            // R59 (Wave 2) — seed quiet hours so background reminders defer to morning even before any UI.
            com.wkhan.hexis.reminders.AlarmScheduler.quietEnabled = s0.quietHoursEnabled
            com.wkhan.hexis.reminders.AlarmScheduler.quietStartHour = s0.quietStartHour
            com.wkhan.hexis.reminders.AlarmScheduler.quietEndHour = s0.quietEndHour
            // (Re)arm per-habit reminder alarms for this device's current day. Cheap; self-healing.
            runCatching { com.wkhan.hexis.reminders.AlarmScheduler.scheduleHabitReminders(this@App, repository) }
            // (Re)arm press-play routine daily nudges the same way.
            runCatching { com.wkhan.hexis.reminders.AlarmScheduler.scheduleRoutineReminders(this@App, repository) }
            // R38 — (re)arm dedicated-calendar event alerts for the next upcoming occurrence of each event.
            runCatching { com.wkhan.hexis.reminders.AlarmScheduler.rescheduleEventAlerts(this@App, repository) }
            // Track 3.4 — (re)arm the reveal notification for each still-sealed letter to your future self.
            runCatching { com.wkhan.hexis.reminders.AlarmScheduler.rescheduleSealedLetters(this@App, repository) }
            // R105 — arm the daily midnight widget refresh so date-sensitive widgets roll over on time.
            runCatching { com.wkhan.hexis.widget.Widgets.scheduleMidnight(this@App) }
        }
        // SEC-1 — one-shot, idempotent upgrade of any pre-existing plaintext attachment / habit-photo
        // files to encrypted-at-rest. Cheap on steady state (a 4-byte header check per file skips the
        // already-encrypted ones); the tolerant reader means nothing breaks whatever state a file is in.
        appScope.launch {
            runCatching {
                com.wkhan.hexis.data.security.FileVault.migrateLegacyPlaintext(
                    listOf(
                        java.io.File(filesDir, "attachments"),
                        java.io.File(filesDir, "habit_photos"),
                    )
                )
            }
        }
        // Keep any placed home-screen widget in sync with task changes. Delayed so this full
        // table read doesn't compete with the DB queries the first UI frame needs.
        appScope.launch {
            kotlinx.coroutines.delay(2_000)
            repository.allTasks.debounce(400).collect {
                AgendaWidget.refresh(this@App)
                com.wkhan.hexis.widget.MatrixWidget.refresh(this@App)
                com.wkhan.hexis.widget.DoNextWidget.refresh(this@App)
                com.wkhan.hexis.widget.Next7Widget.refresh(this@App)
                // R104 — these were previously only poll-refreshed and could go stale.
                com.wkhan.hexis.widget.RecordWidget.refresh(this@App)
                com.wkhan.hexis.widget.MomentumWidget.refresh(this@App)
                com.wkhan.hexis.widget.DayWidget.refresh(this@App)
            }
        }
    }
}
