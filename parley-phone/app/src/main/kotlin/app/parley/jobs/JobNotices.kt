package app.parley.jobs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds

/** The notification for a job that ended after its screen was gone ([UserJobs.Finished]). */
object JobNotices {
    fun post(context: Context, f: UserJobs.Finished) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(NotificationChannels.JOBS, context.getString(R.string.job_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.job_channel_desc) },
        )
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(if (f.failed) R.string.job_failed_title else R.string.job_done_title)
        // The message can name a file or a count, never a contact; still, the lock screen shows only that Parley is done.
        val public = NotificationCompat.Builder(context, NotificationChannels.JOBS)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.job_public_text))
            .build()
        val n = NotificationCompat.Builder(context, NotificationChannels.JOBS)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(f.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(f.message))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NotificationIds.TAG_JOBS, (f.id % Int.MAX_VALUE).toInt(), n)
        } catch (_: SecurityException) {
            // Notifications not allowed: the work itself is done either way.
        }
    }

    private const val REQUEST_OPEN = 7_340
}
