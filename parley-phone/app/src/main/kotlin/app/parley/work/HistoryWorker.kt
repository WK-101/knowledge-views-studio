package app.parley.work

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.history.PlanUsage
import app.parley.container
import app.parley.ui.history.ExportFiles
import app.parley.ui.history.HistoryText
import java.util.concurrent.TimeUnit

/**
 * Call history upkeep shortly after each call, entirely local: incremental archive sync and plan-meter check (80%
 * warning). The daily catch-up, retention and export cleanup run in [MaintenanceWorker].
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
        private const val NAME_SOON = "parley-history-after-call"
        private const val KEY_FULL = "full"
        const val CHANNEL = NotificationChannels.PLAN

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
            for (u in due) {
                notify(context, u, sims[u.config.simId] ?: context.getString(R.string.hist_filter_sim))
                c.history.markWarned(u)
            }
        }

        private fun notify(context: Context, u: PlanUsage, simLabel: String) {
            val id = NotificationIds.plan(u.config.simId)
            val open = PrivateNotice.open(context, id, Intent(context, MainActivity::class.java), update = true)
            val title = if (u.isOver) context.getString(R.string.work_plan_used_up, simLabel)
            else context.getString(R.string.work_plan_used_percent, simLabel, (u.fraction * 100).toInt())
            val b = PrivateNotice.builder(context, CHANNEL, R.drawable.ic_stat_timer, title, context.getString(R.string.work_channel_plan), open = open)
                .setContentText(HistoryText.planSummary(context.resources, u))
            PrivateNotice.post(context, NotificationIds.TAG_PLAN, id, b)
        }
    }
}
