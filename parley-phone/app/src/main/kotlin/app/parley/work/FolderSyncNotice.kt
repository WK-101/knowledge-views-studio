package app.parley.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.MainActivity
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
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
        val text = context.resources.getQuantityString(R.plurals.sync_paused_notify_text, count, count)
        val b = PrivateNotice.builder(
            context, CHANNEL, app.parley.ui.R.drawable.ic_stat_block, context.getString(R.string.sync_paused_notify_title),
            context.getString(R.string.sync_paused_notify_public), text,
            PrivateNotice.route(context, NotificationRequests.FOLDER_SYNC, MainActivity.ACTION_OPEN_SYNC),
        ).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW)
        // Notifications not allowed: the Sync screen still shows the pause.
        if (PrivateNotice.post(context, TAG, NotificationIds.FOLDER_SYNC_ID, b)) prefs.edit().putInt(KEY_POSTED, count).apply()
    }
}
