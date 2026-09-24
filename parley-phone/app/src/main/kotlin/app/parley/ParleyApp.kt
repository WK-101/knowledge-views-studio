package app.parley

import android.app.Application
import app.parley.data.DataContainer
import app.parley.telecom.TelecomGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ParleyApp : Application() {
    lateinit var container: DataContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DataContainer(this)
        TelecomGraph.install(AppTelecomDependencies(this, container))
        app.parley.blocking.BlockingSetup.install(this, container)
        // The process often starts for an incoming call: everything else runs off the main thread, and the parts the
        // call path reads synchronously are warmed first.
        container.scope.launch(Dispatchers.IO) {
            app.parley.blocking.BlockingSetup.warm(this@ParleyApp, container)
            app.parley.work.HousekeepingWorker.schedule(this@ParleyApp)
            app.parley.work.HistoryWorker.schedule(this@ParleyApp)
            // Plaintext call-history exports never outlive the next start.
            app.parley.ui.history.ExportFiles.cleanup(this@ParleyApp)
            // Sync later, off the call path (the daily housekeeping run takes the time-machine snapshot).
            val st = container.folderSync.status.value
            if (st.folderUri != null && st.auto) app.parley.work.FolderSyncWorker.runSoon(this@ParleyApp)
            app.parley.work.RemindersWorker.schedule(this@ParleyApp, container.settings.current().birthdayReminderHour)
        }
    }
}

val android.content.Context.container: DataContainer get() = (applicationContext as ParleyApp).container
