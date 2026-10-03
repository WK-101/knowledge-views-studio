package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.backup.WatchEvent
import app.parley.data.DataContainer
import app.parley.ui.timemachine.WatchText

/**
 * The sync watchdog's daily step (I13): after the day's snapshot, one look for large unexplained losses, and one
 * notification for whatever it found that was never said before. The lock screen shows only "Contacts may be
 * missing"; the account appears after unlock, and no person is ever named. The card in the Contact health check
 * stays until it is answered.
 */
object SyncWatchdogNotice {
    private const val CHANNEL = NotificationChannels.CONTACTS_SAFETY

    suspend fun check(context: Context, c: DataContainer) {
        val fresh = c.syncWatch.run()
        if (fresh.isNotEmpty()) notify(context, fresh)
    }

    private fun notify(context: Context, events: List<WatchEvent>) {
        val nm = NotificationManagerCompat.from(context)
        // Notifications off: the card in the Contact health check still shows.
        if (!nm.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.watch_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 81, IntentRoutes.own(context).setAction(IntentRoutes.ACTION_OPEN_HEALTH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val res = context.resources
        val first = events.first()
        val title = WatchText.title(res, first)
        val more = events.size - 1
        val text = WatchText.body(res, first, context) +
            (if (more > 0) "\n" + res.getQuantityString(R.plurals.watch_notify_more, more, more) else "")
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle(context.getString(R.string.watch_notify_public))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setLocalOnly(true)
            .build()
        try {
            nm.notify(NotificationIds.TAG_SYNC_WATCHDOG, NotificationIds.SYNC_WATCHDOG_ID, n)
        } catch (_: SecurityException) {
            // Not allowed to notify: the card still shows.
        }
    }
}
