package app.parley.work

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
import app.parley.common.StoredStatus
import app.parley.data.sync.SyncStatus

/**
 * Tells the user when a background folder sync paused for deletions only they can confirm, so the sync never waits
 * silently. Posted once per pause (a changed count posts again) and removed once nothing waits. The text names no
 * one, but stays off watches and hides on a locked screen like Parley's other notices.
 */
object FolderSyncNotice {
    private const val CHANNEL = NotificationChannels.HOUSEKEEPING
    private const val TAG = NotificationIds.TAG_FOLDER_SYNC
    private const val PREFS = "folder_sync_notice"
    private const val KEY_POSTED = "posted"

    /** After a run: posts the notice for a pause ([post]: background runs only), or removes it when nothing waits. */
    fun update(context: Context, status: SyncStatus, post: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val paused = StoredStatus.decode(status.lastResult)?.kind == SyncStatus.PAUSED && status.pendingDeletions > 0
        val nm = NotificationManagerCompat.from(context)
        if (!paused) {
            if (prefs.getInt(KEY_POSTED, 0) != 0) {
                nm.cancel(TAG, NotificationIds.FOLDER_SYNC_ID)
                prefs.edit().remove(KEY_POSTED).apply()
            }
            return
        }
        val count = status.pendingDeletions
        if (!post || prefs.getInt(KEY_POSTED, 0) == count || !nm.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.work_channel_housekeeping), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context, 79, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SYNC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.resources.getQuantityString(R.plurals.sync_paused_notify_text, count, count)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle(context.getString(R.string.sync_paused_notify_public))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle(context.getString(R.string.sync_paused_notify_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            nm.notify(TAG, NotificationIds.FOLDER_SYNC_ID, n)
            prefs.edit().putInt(KEY_POSTED, count).apply()
        } catch (_: SecurityException) {
            // Notifications not allowed: the Sync screen still shows the pause.
        }
    }
}
