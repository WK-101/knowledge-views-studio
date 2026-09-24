package app.parley.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.container
import java.util.concurrent.TimeUnit

/** Hourly folder sync (only when a sync folder is set and auto-sync is on). */
class FolderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = applicationContext.container.folderSync
        if (sync.status.value.folderUri != null && sync.status.value.auto) runCatching { sync.syncNow() }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) wm.cancelUniqueWork("parley-folder-sync")
            else wm.enqueueUniquePeriodicWork("parley-folder-sync", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<FolderSyncWorker>(1, TimeUnit.HOURS).build())
        }
    }
}
