package app.parley

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Trace
import androidx.core.content.ContextCompat
import app.parley.blocking.BlockingSetup
import app.parley.calls.PrivateCallLogSweep
import app.parley.common.catching
import app.parley.common.suspendRunCatching
import app.parley.data.DataContainer
import app.parley.data.people.CrashStore
import app.parley.data.security.LockTransitions
import app.parley.jobs.JobNotices
import app.parley.jobs.UserJobWorker
import app.parley.jobs.UserJobs
import app.parley.security.AppLock
import app.parley.security.VaultSession
import app.parley.shortcuts.CircleWidget
import app.parley.shortcuts.FavoritesWidget
import app.parley.shortcuts.WidgetLockRefresh
import app.parley.situations.SituationTriggers
import app.parley.telecom.TelecomGraph
import app.parley.ui.AppLocale
import app.parley.ui.common.ImageExport
import app.parley.ui.contact.CallerTunes
import app.parley.ui.contact.ContactCamera
import app.parley.ui.history.ExportFiles
import app.parley.work.FolderSyncWorker
import app.parley.work.MaintenanceWorker
import app.parley.work.NoticeChannels
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

    /**
     * Exports, imports and backups started from screens, in the app's scope and kept running by WorkManager when Parley
     * goes to the background (see [UserJobs]).
     */
    val jobs: UserJobs by lazy {
        UserJobs(container.scope, UserJobWorker.AppHost(this)) { JobNotices.post(this, it) }.also { JobNotices.observe(this, it, container.scope) }
    }

    override fun onCreate() {
        super.onCreate()
        DebugStrictMode.install(this)
        StartTimings.install(this)
        // Stores the last crash on this phone when "Keep crash reports" is on (it reads that flag at crash time).
        CrashStore(this).install()
        container = DataContainer(this)
        // The privacy view knows when Parley's own lock is engaged (case files and the call path read it).
        container.privacy.bindAppLock(AppLock.locked)
        // Parley is English-only: a language picked in an older version is dropped once, off the main thread.
        container.scope.launch(Dispatchers.IO) { suspendRunCatching { AppLocale.reset(this@ParleyApp) } }
        TelecomGraph.install(AppTelecomDependencies(this, container))
        BlockingSetup.install(this, container)
        // Keeps the Circle widget current while Parley runs (from the full start on, and only while one is placed).
        CircleWidget.observe(this, container)
        FavoritesWidget.observe(this, container)
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
        // Ringtones made from names: System UI's ringtone player needs its read grant again after a reboot (a directory
        // listing). Unused ones go (with their grants) only once the full app is up and its first screens are drawn:
        // the sweep reads every ringtone in use, private contacts' included, and never competes with a ringing call.
        container.scope.launch(Dispatchers.IO) {
            suspendRunCatching { CallerTunes.regrant(this@ParleyApp) }
            CallerTunes.afterStart(container.fullStart) { suspendRunCatching { CallerTunes.sweep(container) } }
        }
        AppLock.onLock = {
            container.vault.forgetOpened()
            // A private contact's picture shared from the cache (decrypted) goes with the rest.
            container.scope.launch(Dispatchers.IO) { ImageExport.forgetPrivate(this@ParleyApp) }
        }
        // Locking ends a duress session (its settings changes are forgotten); what it hides stays hidden until the
        // real Parley PIN.
        AppLock.onEngaged = { LockTransitions.locked(container) }
        // Widgets hide names when Parley's lock delay runs out after leaving it, not only at the next screen-on.
        AppLock.onAway = { delay -> WidgetLockRefresh.schedule(this, delay) }
        AppLock.onBack = { WidgetLockRefresh.cancel(this) }
        // The lock screen asks for a Parley PIN or the fingerprint: which one is read as the UI starts (the lock screen
        // reads it itself if it comes first). Not in a process started for a ringing call: it is a Keystore operation.
        container.scope.launch(Dispatchers.IO) {
            container.fullStart.await()
            suspendRunCatching { container.appPin.load() }
        }
        // The screen going off forgets opened private details (and the Contacts search's docs made from them),
        // whether or not the app lock is on: the vault never keeps them while the phone is locked.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    AppLock.onScreenOff()
                    container.vault.forgetOpened()
                }
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
            // A private contact's call the last process couldn't take out of the system call log goes first.
            suspendRunCatching { PrivateCallLogSweep.recheck(this@ParleyApp, container) }
            // Plaintext call-history exports and shared files an hour old or more; never younger, as this process may
            // have been started by another app opening one of them.
            ExportFiles.cleanup(this@ParleyApp)
            // Camera shots and framed avatars that a closed editor or an unfinished save left in the cache.
            runCatching { ContactCamera.sweep(this@ParleyApp) }
            // Pictures shared before: no share from an earlier run is still being read.
            ImageExport.sweep(this@ParleyApp, all = true)
        }
        // Situations: this phone's signals now (memory only); the listeners and the first look on IO.
        SituationTriggers.provide(this, container)
        container.scope.launch(Dispatchers.IO) { SituationTriggers.install(this@ParleyApp, container) }
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
            catching { NoticeChannels.regroupExisting(this@ParleyApp) }
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
