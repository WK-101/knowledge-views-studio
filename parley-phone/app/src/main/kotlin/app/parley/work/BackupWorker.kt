package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.MainActivity
import app.parley.container
import app.parley.data.backup.BackupSchedule
import java.util.concurrent.TimeUnit

/** Scheduled encrypted backup to the chosen folder. Uses only the public key: no passphrase stored. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val out = applicationContext.container.backup.backupNow(scheduled = true)
        if (!out.ok || out.rotationPaused) notify(applicationContext, out.message)
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

        fun notify(context: Context, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("backup_v1", context.getString(app.parley.R.string.work_channel_backups), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 77, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_BACKUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, "backup_v1")
                .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
                .setContentTitle(context.getString(app.parley.R.string.work_backup_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(4720, n)
            } catch (_: SecurityException) {
            }
        }
    }
}
