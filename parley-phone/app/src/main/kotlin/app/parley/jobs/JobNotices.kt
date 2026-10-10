package app.parley.jobs

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.work.NoticeChannels
import app.parley.work.PrivateNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The notifications of jobs started from a screen ([UserJobs]): one ongoing "Exporting…" while jobs run with Parley in
 * the background, and the end of a job that finished after its screen was gone ([UserJobs.Finished]).
 */
object JobNotices {
    private fun publicVersion(context: Context, text: Int): Notification =
        PrivateNotice.publicVersion(context, NotificationChannels.JOBS, app.parley.ui.R.drawable.ic_stat_call, context.getString(text))

    private fun openParley(context: Context): PendingIntent =
        PrivateNotice.open(context, NotificationRequests.JOB_OPEN, Intent(context, MainActivity::class.java))

    /**
     * Opens Parley and hands [opener]'s file to the share sheet or the print dialog, from the activity (a job never
     * starts another app itself). Parley's own entry, so no other app can send it.
     */
    fun openIntent(context: Context, opener: UserJobs.Opener): Intent = IntentRoutes.own(context)
        .setAction(IntentRoutes.ACTION_OPEN_EXPORT)
        .putExtra(IntentRoutes.EXTRA_FILE, opener.file)
        .putExtra(IntentRoutes.EXTRA_MIME, opener.mime)
        .putExtra(IntentRoutes.EXTRA_PRINT, opener.print)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The ongoing notification for [job] (the latest of the running ones). */
    fun progress(context: Context, job: UserJobs.Running, others: Int = 0): Notification {
        NoticeChannels.ensure(context, NotificationChannels.JOBS)
        val fraction = job.fraction
        val text = if (others > 0) context.resources.getQuantityString(R.plurals.job_more_running, others, others)
        else context.getString(R.string.job_running_away)
        return NotificationCompat.Builder(context, NotificationChannels.JOBS)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(job.label)
            .setContentText(text)
            .setProgress(job.total.coerceAtLeast(0), job.done.coerceAtLeast(0), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, R.string.job_public_running))
            .setContentIntent(openParley(context))
            .build()
    }

    /**
     * Shows the ongoing notification while jobs run and no Parley screen is showing; removes it otherwise. Started once,
     * with the job runner.
     */
    fun observe(context: Context, jobs: UserJobs, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            // In whole percent, so a job reporting every contact doesn't post thousands of updates.
            combine(jobs.running, jobs.inFront) { running, front -> if (front) emptyList() else running.map(::inPercent) }
                .distinctUntilChanged()
                .collect { running ->
                    val nm = NotificationManagerCompat.from(app)
                    val last = running.lastOrNull()
                    if (last == null) {
                        nm.cancel(NotificationIds.JOB_RUNNING)
                    } else {
                        try {
                            nm.notify(NotificationIds.JOB_RUNNING, progress(app, last, running.size - 1))
                        } catch (_: SecurityException) {
                            // Notifications not allowed: the job runs on either way.
                        }
                    }
                }
        }
    }

    private fun inPercent(r: UserJobs.Running): UserJobs.Running =
        r.fraction?.let { r.copy(done = (it * PERCENT).toInt(), total = PERCENT) } ?: r

    fun post(context: Context, f: UserJobs.Finished) {
        val title = context.getString(if (f.failed) R.string.job_failed_title else R.string.job_done_title)
        val tap = f.opener?.let {
            PendingIntent.getActivity(
                context, NotificationRequests.JOB_FILE + (f.id % NotificationRequests.JOB_FILES).toInt(), openIntent(context, it),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } ?: openParley(context)
        // The message can name a file or a count, never a contact; still, the lock screen shows only that Parley is done.
        val b = PrivateNotice.builder(
            context, NotificationChannels.JOBS, app.parley.ui.R.drawable.ic_stat_call, title, context.getString(R.string.job_public_text), f.message, tap,
        )
        // Notifications not allowed: the work itself is done either way.
        PrivateNotice.post(context, NotificationIds.TAG_JOBS, (f.id % Int.MAX_VALUE).toInt(), b)
    }

    private const val PERCENT = 100
}
