package app.parley.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.container
import java.util.concurrent.TimeUnit

/** Hourly folder sync (only when a sync folder is set and auto-sync is on), and the C5 Markdown export with it. */
class FolderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = applicationContext.container.folderSync
        if (sync.status.value.folderUri != null && sync.status.value.auto) runCatching { sync.syncNow() }
        // C5: one-way Markdown notes, when a folder is set and "Keep it up to date" is on.
        val md = applicationContext.container.markdown
        if (md.status.value.folderUri != null && md.status.value.auto) runCatching { md.exportNow(app.parley.ui.extras.MarkdownTexts.build(applicationContext)) }
        return Result.success()
    }

    companion object {
        /** C5: after the Markdown export's folder or switch changed. */
        fun reschedule(context: Context) {
            val st = context.container.folderSync.status.value
            schedule(context, st.folderUri != null && st.auto)
        }

        /** One sync shortly after start-up. */
        fun runSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "parley-folder-sync-once", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FolderSyncWorker>().setInitialDelay(30, TimeUnit.SECONDS).build(),
            )
        }

        /** [on]: folder sync wants the hourly run; the Markdown export's own wish is added here (C5). */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            val md = context.container.markdown.status.value
            if (!on && !(md.folderUri != null && md.auto)) wm.cancelUniqueWork("parley-folder-sync")
            else wm.enqueueUniquePeriodicWork("parley-folder-sync", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<FolderSyncWorker>(1, TimeUnit.HOURS).build())
        }
    }
}
