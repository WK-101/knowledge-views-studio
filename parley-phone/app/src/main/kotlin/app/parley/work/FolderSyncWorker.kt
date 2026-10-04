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
import app.parley.common.sync.FolderSyncSchedule
import app.parley.common.sync.FolderSyncSchedule.Trigger
import app.parley.container
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Folder sync (when a sync folder is set and auto-sync is on) and shared labels, on three triggers
 * (what the auto-sync switch's summary promises):
 * - shortly after Parley starts ([runSoon]);
 * - a minute after this phone's address book settles (a content-URI trigger, which works while Parley isn't running);
 * - every hour while labels are shared (every four hours otherwise), for what no trigger can observe: the other
 *   phones' writes into the shared folders.
 * None runs on low battery, and a start-up or periodic run right after another one is skipped ([FolderSyncSchedule]).
 */
class FolderSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = when {
            inputData.getBoolean(KEY_CHANGE, false) -> Trigger.CHANGE
            inputData.getBoolean(KEY_START_UP, false) -> Trigger.START_UP
            else -> Trigger.PERIODIC
        }
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(K_LAST_RUN, 0L).takeIf { it > 0 }
        if (!FolderSyncSchedule.shouldRun(trigger, last, System.currentTimeMillis())) return Result.success()
        val sync = applicationContext.container.folderSync
        if (sync.status.value.folderUri != null && sync.status.value.auto) {
            runCatching { sync.syncNow() }
            // A paused run tells the user instead of waiting silently until they open the Sync screen.
            FolderSyncNotice.update(applicationContext, sync.status.value)
        }
        // Labels shared with other people's phones, each through its own folder (incremental; never deletes on a partial listing).
        val shared = applicationContext.container.sharedLabels
        if (shared.wantsRuns()) runCatching { shared.syncAll() }
        // A content trigger fires once: watch for the next change, after this run.
        if (trigger == Trigger.CHANGE && wanted(applicationContext, null)) watchChanges(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        prefs.edit().putLong(K_LAST_RUN, System.currentTimeMillis()).apply()
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "parley-folder-sync"
        private const val ONCE = "parley-folder-sync-once"
        private const val ON_CHANGE = "parley-folder-sync-change"
        private const val KEY_CHANGE = "change"
        private const val KEY_START_UP = "start_up"
        private const val PREFS = "folder_sync_runs"
        private const val K_LAST_RUN = "last_run"

        /** Background runs wait while the battery is low; a change trigger keeps its own content constraints too. */
        private fun batteryNotLow(): Constraints.Builder = Constraints.Builder().setRequiresBatteryNotLow(true)

        /** After folder sync's folder or switch changed (and at start-up). */
        fun reschedule(context: Context) {
            val st = context.container.folderSync.status.value
            schedule(context, st.folderUri != null && st.auto)
        }

        /** One run shortly after start-up, when anything wants runs. */
        fun runSoon(context: Context) {
            if (!wanted(context, null)) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FolderSyncWorker>().setInitialDelay(30, TimeUnit.SECONDS)
                    .setConstraints(batteryNotLow().build()).setInputData(workDataOf(KEY_START_UP to true)).build(),
            )
        }

        /** Folder sync ([on]) or shared labels want runs. */
        private fun wanted(context: Context, on: Boolean?): Boolean {
            val c = context.container
            val sync = on ?: c.folderSync.status.value.let { it.folderUri != null && it.auto }
            return sync || c.sharedLabels.wantsRuns()
        }

        /** [on]: folder sync wants runs; shared labels' own wish is added here. */
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!wanted(context, on)) {
                wm.cancelUniqueWork(PERIODIC)
                wm.cancelUniqueWork(ON_CHANGE)
                wm.cancelUniqueWork(ONCE)
                return
            }
            // Hourly while labels are shared: the other phones' changes arrive only through the folder. Otherwise this
            // phone's own changes have the content trigger, so the catch-up can wait longer. UPDATE applies a changed
            // period or constraint to an install that already has the work.
            val hours = FolderSyncSchedule.periodHours(context.container.sharedLabels.wantsRuns())
            wm.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<FolderSyncWorker>(hours, TimeUnit.HOURS).setConstraints(batteryNotLow().build()).build(),
            )
            watchChanges(context, ExistingWorkPolicy.KEEP)
        }

        private fun watchChanges(context: Context, policy: ExistingWorkPolicy) {
            val constraints = batteryNotLow()
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
