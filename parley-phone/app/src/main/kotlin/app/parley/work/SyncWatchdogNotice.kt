package app.parley.work

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.common.backup.WatchEvent
import app.parley.data.DataContainer
import app.parley.ui.timemachine.WatchText

/**
 * The sync watchdog's daily step: after the day's snapshot, one look for large unexplained losses, and one
 * notification for whatever it found that was never said before. The lock screen shows only "Contacts may be
 * missing"; the account appears after unlock, and no person is ever named. The card in the Contact health check
 * stays until it is answered.
 */
object SyncWatchdogNotice {
    suspend fun check(context: Context, c: DataContainer) {
        val fresh = c.syncWatch.run()
        if (fresh.isNotEmpty()) notify(context, fresh)
    }

    private fun notify(context: Context, events: List<WatchEvent>) {
        val nm = NotificationManagerCompat.from(context)
        // Notifications off: the card in the Contact health check still shows.
        if (!nm.areNotificationsEnabled()) return
        val res = context.resources
        val first = events.first()
        val more = events.size - 1
        val text = WatchText.body(res, first, context) +
            (if (more > 0) "\n" + res.getQuantityString(R.plurals.watch_notify_more, more, more) else "")
        val open = PrivateNotice.route(context, NotificationRequests.SYNC_WATCHDOG, IntentRoutes.ACTION_OPEN_HEALTH)
        val b = PrivateNotice.builder(
            context, NotificationChannels.CONTACTS_SAFETY, app.parley.ui.R.drawable.ic_stat_block, WatchText.title(res, first),
            context.getString(R.string.watch_notify_public), text, open,
        ).setOnlyAlertOnce(true)
        // Not allowed to notify: the card still shows.
        PrivateNotice.post(context, NotificationIds.TAG_SYNC_WATCHDOG, NotificationIds.SYNC_WATCHDOG_ID, b)
    }
}
