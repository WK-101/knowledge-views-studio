package app.parley.lists

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Scheduled downloads. By default only on an unmetered network while the phone is idle. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (Updater.run(applicationContext)) Result.success() else if (runAttemptCount < 2) Result.retry() else Result.failure()

    companion object {
        private const val PERIODIC = "parley-lists-periodic"
        private const val NOW = "parley-lists-now"

        /** Applies the schedule in [cfg] (called at start-up and whenever the settings change). */
        fun schedule(context: Context, cfg: UpdaterConfig) {
            val wm = WorkManager.getInstance(context)
            if (!cfg.auto) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (cfg.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresDeviceIdle(cfg.idleOnly)
                .setRequiresCharging(cfg.chargingOnly)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(cfg.intervalHours.coerceIn(12, 24 * 7).toLong(), TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** "Update now": no idle requirement; any network unless [unmeteredOnly]. */
        fun runNow(context: Context, unmeteredOnly: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
