package app.parley.work

import android.content.Context
import androidx.core.app.NotificationCompat
import app.parley.IntentRoutes
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests

/**
 * Said once, when the notes folder export that kept a folder up to date is removed: that folder stops changing, and
 * Export contacts is where notes are exported now. Names no one; quiet, in housekeeping.
 */
object FolderExportNotice {
    fun post(context: Context) {
        val title = context.getString(R.string.export_folder_replaced_title)
        val b = PrivateNotice.builder(
            context, NotificationChannels.HOUSEKEEPING, app.parley.ui.R.drawable.ic_stat_block, title, title,
            context.getString(R.string.export_folder_replaced_text),
            PrivateNotice.route(context, NotificationRequests.FOLDER_EXPORT, IntentRoutes.ACTION_EXPORT_CONTACTS),
        ).setPriority(NotificationCompat.PRIORITY_LOW)
        // Notifications not allowed: nothing else to do, the folder keeps what was written.
        PrivateNotice.post(context, NotificationIds.TAG_FOLDER_EXPORT, NotificationIds.FOLDER_EXPORT_ID, b)
    }
}
