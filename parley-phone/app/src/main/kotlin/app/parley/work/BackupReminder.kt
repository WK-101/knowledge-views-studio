package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.MainActivity
import app.parley.common.ux.BackupNudge
import app.parley.data.DataContainer

/**
 * C3: at most one quiet notification a month once a backup is overdue (the Settings and Backup banners do the rest).
 * Checked by the daily housekeeping run. The text names no one, but still stays off watches and hides on a locked
 * screen like Parley's other reminders.
 */
object BackupReminder {
    private const val CHANNEL = "backup_v1"
    private const val TAG = "backup_reminder"

    fun maybeNotify(context: Context, c: DataContainer, now: Long = System.currentTimeMillis()) {
        val state = c.backup.prefs.state.value
        val ux = c.ux.state.value
        val since = BackupNudge.since(state.lastBackupAt, c.ux.installedAt(context))
        if (!BackupNudge.mayNotify(since, now, ux.backupReminderDays, ux.backupNotifiedAt)) return
        // Notifications off: nothing is recorded, and the banners in Settings and Backup still show.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(app.parley.R.string.work_channel_backups), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 78, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_BACKUP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(app.parley.R.string.ux_backup_notify_title)
        val text = context.getString(if (state.lastBackupAt > 0) app.parley.R.string.ux_backup_notify_text else app.parley.R.string.ux_backup_notify_text_never)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle(context.getString(app.parley.R.string.work_backup_title))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, 0, n)
            c.ux.setBackupNotified(now)
        } catch (_: SecurityException) {
            // Notifications not allowed: the banners in Settings and Backup still show.
        }
    }
}
