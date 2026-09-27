package app.parley

import android.app.Application
import android.content.Context
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
        // Keeps the Circle widget current while Parley runs.
        CircleWidget.observe(this, container)
        // The process often starts for an incoming call: everything else runs off the main thread, and the parts the
        // call path reads synchronously are warmed first.
        container.scope.launch(Dispatchers.IO) {
            BlockingSetup.warm(this@ParleyApp, container)
            HousekeepingWorker.schedule(this@ParleyApp)
            HistoryWorker.schedule(this@ParleyApp)
            // Plaintext call-history exports never outlive the next start.
            ExportFiles.cleanup(this@ParleyApp)
            // Sync later, off the call path (the daily housekeeping run takes the time-machine snapshot).
            val st = container.folderSync.status.value
            if (st.folderUri != null && st.auto) FolderSyncWorker.runSoon(this@ParleyApp)
            RemindersWorker.schedule(this@ParleyApp, container.settings.current().birthdayReminderHour)
        }
        // Well after start-up (never on the call path): stored number keys move to the line key once.
        container.scope.launch(Dispatchers.IO) {
            delay(30_000)
            if (!container.phoneKeys.done) container.phoneKeys.runIfNeeded()
        }
    }
}

val Context.container: DataContainer get() = (applicationContext as ParleyApp).container
