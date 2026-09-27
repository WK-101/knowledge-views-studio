package app.parley

import android.app.Application
import android.content.Context
import android.os.Trace
import app.parley.blocking.BlockingSetup
import app.parley.data.DataContainer
import app.parley.data.people.CrashStore
import app.parley.shortcuts.CircleWidget
import app.parley.telecom.TelecomGraph
import app.parley.ui.AppLocale
import app.parley.ui.history.ExportFiles
import app.parley.work.FolderSyncWorker
import app.parley.work.HistoryWorker
import app.parley.work.HousekeepingWorker
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

    // On Android 10-12 the in-app language also applies to notifications and toasts.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        DebugStrictMode.install(this)
        // Stores the last crash on this phone when "Keep crash reports" is on (it reads that flag at crash time).
        CrashStore(this).install()
        container = DataContainer(this)
        TelecomGraph.install(AppTelecomDependencies(this, container))
        BlockingSetup.install(this, container)
        // Keeps the Circle widget current while Parley runs (from the full start on, and only while one is placed).
        CircleWidget.observe(this, container)
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
            HousekeepingWorker.schedule(this@ParleyApp)
            HistoryWorker.schedule(this@ParleyApp)
            // Sync later, off the call path (the daily housekeeping run takes the time-machine snapshot).
            val st = container.folderSync.status.value
            if (st.folderUri != null && st.auto) FolderSyncWorker.runSoon(this@ParleyApp)
            RemindersWorker.schedule(this@ParleyApp, container.settings.current().birthdayReminderHour)
            // Well after that: stored number keys move to the line key once.
            delay(30_000)
            if (!container.phoneKeys.done) container.phoneKeys.runIfNeeded()
        }
    }
}

private const val TRACE_WARM = "Parley.warmCallPath"

val Context.container: DataContainer get() = (applicationContext as ParleyApp).container
