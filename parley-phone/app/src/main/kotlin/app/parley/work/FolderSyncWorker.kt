package app.parley.work

import android.content.Context
import android.provider.ContactsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.parley.container
import app.parley.ui.extras.MarkdownTexts
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Folder sync (when a sync folder is set and auto-sync is on) and the Markdown export with it, on three triggers
 * (what the auto-sync switch's summary promises):
 * - shortly after Parley starts ([runSoon]);
 * - a minute after this phone's address book settles (a content-URI trigger, which works while Parley isn't running);
 * - every hour, for what no trigger can observe: the other phone's writes into the shared folder, and notes.
 */
class FolderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = applicationContext.container.folderSync
        if (sync.status.value.folderUri != null && sync.status.value.auto) runCatching { sync.syncNow() }
        // One-way Markdown notes, when a folder is set and "Keep it up to date" is on.
        val md = applicationContext.container.markdown
        if (md.status.value.folderUri != null && md.status.value.auto) runCatching { md.exportNow(MarkdownTexts.build(applicationContext)) }
        // A content trigger fires once: watch for the next change, after this run.
        if (inputData.getBoolean(KEY_CHANGE, false) && wanted(applicationContext, null)) watchChanges(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "parley-folder-sync"
        private const val ONCE = "parley-folder-sync-once"
        private const val ON_CHANGE = "parley-folder-sync-change"
        private const val KEY_CHANGE = "change"

        /** After the Markdown export's folder or switch changed (and at start-up). */
        fun reschedule(context: Context) {
            val st = context.container.folderSync.status.value
            schedule(context, st.folderUri != null && st.auto)
        }

        /** One run shortly after start-up, when anything wants runs. */
        fun runSoon(context: Context) {
            if (!wanted(context, null)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FolderSyncWorker>().setInitialDelay(30, TimeUnit.SECONDS).build(),
            )
        }

        /** Folder sync ([on]) or the Markdown export wants runs. */
        private fun wanted(context: Context, on: Boolean?): Boolean {
            val c = context.container
            val sync = on ?: c.folderSync.status.value.let { it.folderUri != null && it.auto }
            val md = c.markdown.status.value
            return sync || (md.folderUri != null && md.auto)
        }

        /** [on]: folder sync wants runs; the Markdown export's own wish is added here. */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!wanted(context, on)) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(ON_CHANGE)
                wm.cancelUniqueWork(ONCE)
                return
            }
            // Hourly: the other phone's changes arrive only through the folder. UPDATE brings back the hourly period on
            // installs that had the briefly daily one.
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<FolderSyncWorker>(1, TimeUnit.HOURS).build())
            watchChanges(context, ExistingWorkPolicy.KEEP)
        }

        private fun watchChanges(context: Context, policy: ExistingWorkPolicy) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(ContactsContract.Contacts.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(Duration.ofMinutes(1))
                .setTriggerContentMaxDelay(Duration.ofMinutes(15))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_CHANGE, policy,
                OneTimeWorkRequestBuilder<FolderSyncWorker>().setConstraints(constraints).setInputData(workDataOf(KEY_CHANGE to true)).build(),
            )
        }
    }
}
