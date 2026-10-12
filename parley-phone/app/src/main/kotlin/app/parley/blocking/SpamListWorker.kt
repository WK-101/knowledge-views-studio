package app.parley.blocking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.common.catching
import app.parley.container
import app.parley.data.DataContainer

/**
 * Offline, when the subscribed spam-list folder changed: re-reads it, removes expired temporary allow rules
 * ("Allow for 24 h") and trims old screening traces, and copies newer lists from the optional Parley Lists app. No
 * network. The daily run is part of [app.parley.work.MaintenanceWorker].
 */
class SpamListWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        run(applicationContext.container)
        // Lists subscribed from the optional "Parley Lists" app (read through its provider, no network here).
        catching { ListsUpdaterClient.refresh(applicationContext, applicationContext.container.lists) }
        return Result.success()
    }

    companion object {
        private const val NOW = "parley-screening-now"

        fun runSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SpamListWorker>().build())
        }

        suspend fun run(c: DataContainer) {
            catching { c.blocks.deleteExpiredRules() }
            catching { c.blocks.pruneScreened() }
            catching { if (c.lists.state.value.folderUri != null) c.lists.refreshFolder() }
        }
    }
}
