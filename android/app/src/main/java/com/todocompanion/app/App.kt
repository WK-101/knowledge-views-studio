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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Application-scoped singletons. Doubles as a tiny service locator (no DI framework yet). */
class App : Application() {

    val database by lazy { AppDatabase.get(this) }
    val repository by lazy { AppRepository(database, this) }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.todocompanion.app.util.Diag.attach(this) // TEMP-DIAG — set up the copyable diag log before the DB opens
        // R71 — capture ANY uncaught crash to a file, then defer to the normal handler.
        // SEC (R2-C) — write it to INTERNAL app-private storage (filesDir/last_crash.txt), not the
        // app-EXTERNAL dir it used to use: the external files dir is reachable by other apps that hold
        // storage access and survives uninstall on some OEMs, so a stack trace (with your file paths /
        // note titles in it) shouldn't sit there. Retrieve it with `adb` or a future in-app viewer.
        // Still mirrored to logcat (tag "KairoCrash") for a live `adb logcat` session.
        run {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, err ->
                runCatching {
                    val trace = android.util.Log.getStackTraceString(err)
                    android.util.Log.e("KairoCrash", "Uncaught on ${thread.name}", err)
                    java.io.File(filesDir, "last_crash.txt").writeText(
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
            // SEC (R2-A/H2) — one-time move of any legacy cleartext sync passphrase out of the DB settings
            // table into the KeyStore-wrapped SecurePrefs, before the first snapshot reads it.
            runCatching { repository.migrateSyncPassToSecurePrefs() }
            val s0 = repository.settingsSnapshot(); repository.ensureSeed()
            // W3 (goals→Room, Increment 2) — one-time, idempotent safety net for the JSON→table flip: adopt any
            // goal/review still living only in the legacy settings-JSON into the table (e.g. one created on an
            // Increment-1 build before the flip). Additive; a no-op once everything's in the table.
            runCatching { repository.reconcileGoalsFromLegacyJson(s0.goalsJson, s0.goalReviewsJson) }
            // TEMP-DIAG — confirm the shipped W2/W3 work on-device, all gathered at startup so the in-app
            // DiagDialog shows the full picture with no navigation. Covers migrated + fresh-install schema.
            runCatching {
                val rdb = database.openHelper.readableDatabase
                fun idx(t: String): List<String> {
                    val c = rdb.query("PRAGMA index_list(`$t`)")
                    return buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }.also { c.close() }
                }
                com.todocompanion.app.util.Diag.log("schema", "dbVersion=${rdb.version} notes_indices=${idx("notes")}")
                val ws = s0.activeWorkspaceId
                // W2 — the index-backed SQL notes query the VM now uses for the live list.
                val liveNotes = repository.observeNotesByWorkspace(ws, false).first()
                com.todocompanion.app.util.Diag.log("notes", "SQL live notes in active workspace = ${liveNotes.size}")
                // W2b — the index-backed SQL active-workspace task set the VM now uses for the main list.
                val wsTasks = repository.observeTasksByWorkspace(ws).first()
                com.todocompanion.app.util.Diag.log("tasks", "SQL workspace tasks = ${wsTasks.size}")
                // W3 — the unified reminder model over real data. Raw counts too, so a 0 total is confirmed as
                // "no reminders configured" rather than a parse miss.
                val R = com.todocompanion.app.domain.reminders.UnifiedReminders
                val habits = repository.allHabits.first()
                val events = repository.allEvents.first()
                val countdowns = repository.allCountdowns.first()
                val routines = com.todocompanion.app.domain.Routines.parse(s0.routinesJson)
                com.todocompanion.app.util.Diag.log(
                    "reminders-raw",
                    "habits=${habits.size}(withTimes=${habits.count { it.reminderTimes.isNotBlank() }}) " +
                        "events=${events.size}(withAlerts=${events.count { it.alertsMinutes.isNotBlank() }}) " +
                        "notes=${liveNotes.size}(withReminder=${liveNotes.count { it.reminderAt != null || it.reminderExtra.isNotBlank() }}) " +
                        "countdowns=${countdowns.size}(prep/keep=${countdowns.count { it.prepLeadDays > 0 || it.keepInTouchDays > 0 }}) " +
                        "routines=${routines.size}(withReminder=${routines.count { it.whenReminderMin != null }})",
                )
                val unified = R.ordered(
                    habits.flatMap { R.fromHabit(it.id, it.name, it.reminderTimes) },
                    events.flatMap { R.fromEvent(it.id, it.title, it.alertsMinutes) },
                    liveNotes.flatMap { R.fromNote(it.id, it.title, it.reminderAt, it.reminderExtra, it.reminderRrule) },
                    countdowns.flatMap { R.fromOccasion(it.id, it.title, it.prepLeadDays, it.keepInTouchDays) },
                    routines.flatMap { R.fromRoutine(it.id, it.name, it.whenReminderMin) },
                )
                com.todocompanion.app.util.Diag.log("reminders", "unified reminders = ${unified.size}, by source = ${unified.groupingBy { it.source }.eachCount()}")
                // W3 (goals→Room, Increment 2) — the table is now the runtime source of truth; the legacy JSON is
                // frozen at the Increment-1 migration value and used only as backup transport + the reconciliation
                // fallback above. Report the live table counts (what the UI now reads) alongside the legacy JSON so
                // a create-a-goal-on-device spot-check shows the table growing.
                val tableGoals = repository.goalsFromTableOnce()
                val tableReviews = repository.goalReviewsFromTableOnce()
                com.todocompanion.app.util.Diag.log(
                    "goals",
                    "table goals=${tableGoals.size} reviews=${tableReviews.size} (live source) · " +
                        "legacy JSON goals=${com.todocompanion.app.domain.Goals.parse(s0.goalsJson).size}",
                )
            }
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
        // SEC-1 — one-shot, idempotent upgrade of any pre-existing plaintext attachment / habit-photo
        // files to encrypted-at-rest. Cheap on steady state (a 4-byte header check per file skips the
        // already-encrypted ones); the tolerant reader means nothing breaks whatever state a file is in.
        appScope.launch {
            runCatching {
                com.todocompanion.app.data.security.FileVault.migrateLegacyPlaintext(
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
