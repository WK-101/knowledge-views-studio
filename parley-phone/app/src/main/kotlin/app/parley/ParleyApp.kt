package app.parley

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Trace
import androidx.core.content.ContextCompat
import app.parley.blocking.BlockingSetup
import app.parley.common.suspendRunCatching
import app.parley.data.DataContainer
import app.parley.data.people.CrashStore
import app.parley.security.AppLock
import app.parley.data.security.LockTransitions
import app.parley.security.VaultSession
import app.parley.shortcuts.CircleWidget
import app.parley.telecom.TelecomGraph
import app.parley.ui.AppLocale
import app.parley.ui.contact.CallerTunes
import app.parley.ui.history.ExportFiles
import app.parley.work.FolderSyncWorker
import app.parley.work.MaintenanceWorker
import app.parley.work.ReminderChannels
import app.parley.work.RemindersWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ParleyApp : Application() {
    lateinit var container: DataContainer
        private set

    /**
     * The container, or null while the app is still starting. For content providers: they can be queried on a
     * binder thread before onCreate has built it, and must answer at once (nothing) rather than wait.
     */
    val containerOrNull: DataContainer? get() = if (::container.isInitialized) container else null

    override fun onCreate() {
        super.onCreate()
        DebugStrictMode.install(this)
        // Stores the last crash on this phone when "Keep crash reports" is on (it reads that flag at crash time).
        CrashStore(this).install()
        container = DataContainer(this)
        // Parley is English-only: a language picked in an older version is dropped once, off the main thread (L7).
        container.scope.launch(Dispatchers.IO) { suspendRunCatching { AppLocale.reset(this@ParleyApp) } }
        TelecomGraph.install(AppTelecomDependencies(this, container))
        BlockingSetup.install(this, container)
        // Keeps the Circle widget current while Parley runs (from the full start on, and only while one is placed).
        CircleWidget.observe(this, container)
        // Right after the user authenticates, the vault moves to an authentication-bound key if it isn't on one yet.
        VaultSession.onAuthenticated = {
            container.scope.launch(Dispatchers.IO) {
                suspendRunCatching { container.vault.upgradeDetailKey() }
                // Entries from before the caller-ID copy kept the star, labels, ringtone and voicemail get them now.
                suspendRunCatching { container.vault.migrateCallerChoices() }
                // Entries sealed as one blob are split, so their pages open only the small part (VaultCrypto.sealDetailParts).
                suspendRunCatching { container.vault.splitDetails() }
            }
        }
        // Ringtones made from names: unused ones go (with their grants), and System UI's ringtone player needs its read
        // grant again for the rest after a reboot.
        container.scope.launch(Dispatchers.IO) {
            suspendRunCatching { CallerTunes.sweep(container) }
            suspendRunCatching { CallerTunes.regrant(this@ParleyApp) }
        }
        AppLock.onLock = { container.vault.forgetOpened() }
        // I21: locking ends a duress session (its settings changes are forgotten); what it hides stays hidden until the
        // real Parley PIN.
        AppLock.onEngaged = { LockTransitions.locked(container) }
        // The lock screen asks for a Parley PIN or the fingerprint: which one is read before it shows.
        container.scope.launch(Dispatchers.IO) { suspendRunCatching { container.appPin.load() } }
        // With the app lock on, the screen going off forgets opened private details too (not only a lock).
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = AppLock.onScreenOff()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The process often starts for an incoming call: only what the call path reads synchronously is warmed here,
        // off the main thread.
        container.scope.launch(Dispatchers.IO) {
            Trace.beginAsyncSection(TRACE_WARM, 0)
            try {
                BlockingSetup.warm(this@ParleyApp, container)
            } finally {
                Trace.endAsyncSection(TRACE_WARM, 0)
            }
            // Plaintext call-history exports never outlive the next start.
            ExportFiles.cleanup(this@ParleyApp)
        }
        // Alongside: the preference-backed stores the call screen and the first screens read, built on IO so their
        // first read never parses a file on the main thread (the view model touches several as it is created).
        container.scope.launch(Dispatchers.IO) { container.warmStores() }
        // Everything else waits for the UI or a settled call (see DataContainer.fullStart), and never runs more than
        // two things at a time.
        container.scope.launch(container.warmDispatcher) {
            container.fullStart.await()
            BlockingSetup.warmLater(this@ParleyApp, container)
            MaintenanceWorker.schedule(this@ParleyApp)
            // Folder sync runs soon after start, after contact changes and hourly; the maintenance run takes the
            // time-machine snapshot.
            FolderSyncWorker.reschedule(this@ParleyApp)
            FolderSyncWorker.runSoon(this@ParleyApp)
            RemindersWorker.schedule(this@ParleyApp, container.settings.current().birthdayReminderHour)
            // Reminder channels made by an older version join the "Reminders" group, keeping their settings.
            runCatching { ReminderChannels.regroupExisting(this@ParleyApp) }
            // Well after that: stored number keys move to the line key once.
            delay(30_000)
            if (!container.phoneKeys.done) container.phoneKeys.runIfNeeded()
            // Notes, screened names, the journal and snapshots from older versions are sealed at rest once.
            if (!container.recordSealing.done) suspendRunCatching { container.recordSealing.runIfNeeded() }
        }
    }
}

private const val TRACE_WARM = "Parley.warmCallPath"

val Context.container: DataContainer get() = (applicationContext as ParleyApp).container
