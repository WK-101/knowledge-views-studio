package app.parley.blocking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.container
import app.parley.data.DataContainer
import java.util.concurrent.TimeUnit

/**
 * Daily, offline: re-reads the subscribed spam-list folder, removes expired temporary allow rules
 * ("Allow for 24 h") and trims old screening traces, and copies newer lists from the optional Parley Lists
 * app. No network.
 */
class SpamListWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        run(applicationContext.container)
        // Lists subscribed from the optional "Parley Lists" app (read through its provider, no network here).
        runCatching { ListsUpdaterClient.refresh(applicationContext, applicationContext.container.lists) }
        return Result.success()
    }

    companion object {
        private const val NAME = "parley-screening-daily"
        private const val NOW = "parley-screening-now"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<SpamListWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        fun runSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SpamListWorker>().build())
        }

        suspend fun run(c: DataContainer) {
            runCatching { c.blocks.deleteExpiredRules() }
            runCatching { c.blocks.pruneScreened() }
            runCatching { if (c.lists.state.value.folderUri != null) c.lists.refreshFolder() }
        }
    }
}
