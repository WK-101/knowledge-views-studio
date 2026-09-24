package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.parley.MainActivity
import app.parley.common.history.PlanUsage
import app.parley.container
import app.parley.ui.history.ExportFiles
import java.util.concurrent.TimeUnit

/**
 * Call history upkeep, entirely local:
 * - daily: full archive catch-up, archive retention (numbers kept forever are exempt), trash pruning,
 *   old export files removed, plan-meter check;
 * - shortly after each call: incremental archive sync and plan-meter check (80% warning).
 */
class HistoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val full = inputData.getBoolean(KEY_FULL, true)
        runCatching { c.history.sync(full) }
        if (full) {
            runCatching { c.history.applyRetention(c.settings.current().callLogRetentionDays) }
            ExportFiles.cleanup(applicationContext, olderThanMillis = TimeUnit.HOURS.toMillis(1))
        }
        runCatching { warnPlans(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val NAME = "parley-history"
        private const val NAME_SOON = "parley-history-after-call"
        private const val KEY_FULL = "full"
        const val CHANNEL = "plan_v1"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HistoryWorker>(1, TimeUnit.DAYS).setInputData(workDataOf(KEY_FULL to true)).build(),
            )
        }

        /** After a call ends: the call log is written a moment later, so check in half a minute. */
        fun checkSoon(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME_SOON, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HistoryWorker>().setInitialDelay(30, TimeUnit.SECONDS).setInputData(workDataOf(KEY_FULL to false)).build(),
            )
        }

        suspend fun warnPlans(context: Context) {
            val c = context.container
            val due = c.history.plansToWarn()
            if (due.isEmpty()) return
            val sims = c.sims.accounts().associate { it.id to it.label }
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Plan minutes", NotificationManager.IMPORTANCE_DEFAULT))
            for (u in due) {
                notify(context, u, sims[u.config.simId] ?: "SIM")
                c.history.markWarned(u)
            }
        }

        private fun notify(context: Context, u: PlanUsage, simLabel: String) {
            val id = 20_000 + (u.config.simId.hashCode() and 0xFFFF)
            val open = PendingIntent.getActivity(
                context, id,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = if (u.isOver) "$simLabel: plan minutes used up" else "$simLabel: ${(u.fraction * 100).toInt()}% of plan minutes used"
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(app.parley.R.drawable.ic_stat_timer)
                .setContentTitle(title)
                .setContentText(u.summary())
                .setContentIntent(open)
                .setAutoCancel(true)
            try {
                NotificationManagerCompat.from(context).notify("plan", id, b.build())
            } catch (_: SecurityException) {
            }
        }
    }
}
