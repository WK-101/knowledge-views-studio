package app.parley.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.container
import app.parley.data.backup.BackupSchedule
import app.parley.data.security.Concealment
import java.util.concurrent.TimeUnit

/** Scheduled encrypted backup to the chosen folder. Uses only the public key: no passphrase stored. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // I21: after a duress unlock, notes read as none; a scheduled backup then would be a poorer copy that could
        // rotate a good one out. The next run after the real Parley PIN backs up as usual.
        if (Concealment.hiding) return Result.success()
        val out = applicationContext.container.backup.backupNow(scheduled = true)
        if (!out.ok || out.rotationPaused || out.failedSections.isNotEmpty()) notify(applicationContext, out.message)
        return Result.success()
    }

    companion object {
        private const val NAME = "parley-backup"

        fun schedule(context: Context, schedule: BackupSchedule) {
            val wm = WorkManager.getInstance(context)
            if (schedule == BackupSchedule.OFF) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val days = if (schedule == BackupSchedule.DAILY) 1L else 7L
            wm.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(days, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                    .build(),
            )
        }

        /**
         * A failed scheduled backup or paused rotation. Its channel stays outside the Reminders group, so muting
         * reminders never hides it.
         */
        fun notify(context: Context, text: String) {
            val b = PrivateNotice.builder(
                context, NotificationChannels.BACKUPS, app.parley.ui.R.drawable.ic_stat_block, context.getString(R.string.work_backup_title),
                context.getString(R.string.work_backup_title), text,
                PrivateNotice.route(context, NotificationRequests.BACKUP_FAILED, MainActivity.ACTION_OPEN_BACKUP),
            )
            PrivateNotice.post(context, NotificationIds.TAG_BACKUP_FAILED, NotificationIds.BACKUP_ID, b)
        }
    }
}
