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
 * Folder sync (when a sync folder is set and auto-sync is on) and the Markdown export with it. Driven by changes: a
 * run follows a minute after the address book settles (a content-URI trigger, which works while Parley isn't
 * running), plus one daily run for changes it can't observe (the folder's own files, notes).
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
        private const val DAILY = "parley-folder-sync"
        private const val ON_CHANGE = "parley-folder-sync-change"
        private const val KEY_CHANGE = "change"

        /** After the Markdown export's folder or switch changed (and at start-up). */
        fun reschedule(context: Context) {
            val st = context.container.folderSync.status.value
            schedule(context, st.folderUri != null && st.auto)
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
                wm.cancelUniqueWork(DAILY)
                wm.cancelUniqueWork(ON_CHANGE)
                return
            }
            // UPDATE turns the hourly job of earlier versions into the daily one.
            wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<FolderSyncWorker>(1, TimeUnit.DAYS).build())
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
