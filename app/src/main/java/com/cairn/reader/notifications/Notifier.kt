package com.cairn.reader.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cairn.reader.MainActivity
import com.cairn.reader.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One new article worth notifying about. */
data class NewArticle(val id: String, val title: String, val source: String, val excerpt: String?)

/**
 * Posts new-article notifications, grouped, each with Mark-read and Save quick actions and a
 * tap target that opens the article. All local — nothing leaves the device.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    private fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_NEW, NotificationManager.IMPORTANCE_DEFAULT)
            .setName("New articles")
            .setDescription("New items from feeds you've turned notifications on for")
            .build()
        manager.createNotificationChannel(channel)
    }

    fun notifyNewArticles(items: List<NewArticle>) {
        if (items.isEmpty()) return
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val shown = items.take(MAX_SHOWN)
        shown.forEach { post(it) }
        // A group summary ties the individual notifications together on Android 7+.
        val summary = NotificationCompat.Builder(context, CHANNEL_NEW)
            .setSmallIcon(R.drawable.ic_stat_cairn)
            .setContentTitle("New articles")
            .setContentText(if (items.size > MAX_SHOWN) "${items.size} new — showing $MAX_SHOWN" else "${items.size} new")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(SUMMARY_ID, summary) }
    }

    /** A once-daily nudge that the focus-ranked brief is ready. Opens the app when tapped. */
    fun notifyBrief(count: Int, headline: String?) {
        if (count <= 0) return
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val open = PendingIntent.getActivity(
            context, BRIEF_ID,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_OPEN_BRIEF, true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_NEW)
            .setSmallIcon(R.drawable.ic_stat_cairn)
            .setContentTitle("Your daily brief is ready")
            .setContentText(headline?.takeIf { it.isNotBlank() }?.let { "$count picks · $it" } ?: "$count picks worth your time")
            .setAutoCancel(true)
            .setContentIntent(open)
        runCatching { manager.notify(BRIEF_ID, builder.build()) }
    }

    private fun post(item: NewArticle) {
        val notifId = item.id.hashCode()
        val open = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_OPEN_ITEM, item.id)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_NEW)
            .setSmallIcon(R.drawable.ic_stat_cairn)
            .setContentTitle(item.title)
            .setContentText(item.source)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Mark read", action(item.id, notifId, NotificationActionReceiver.ACTION_MARK_READ))
            .addAction(0, "Save", action(item.id, notifId, NotificationActionReceiver.ACTION_SAVE))
        item.excerpt?.takeIf { it.isNotBlank() }?.let {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(it))
        }
        runCatching { manager.notify(notifId, builder.build()) }
    }

    private fun action(itemId: String, notifId: Int, act: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = act
            putExtra(NotificationActionReceiver.EXTRA_ITEM, itemId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF, notifId)
        }
        return PendingIntent.getBroadcast(
            context, (act + itemId).hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_NEW = "new_articles"
        private const val GROUP = "com.cairn.reader.NEW_ARTICLES"
        private const val SUMMARY_ID = -1000
        private const val BRIEF_ID = -2000
        private const val MAX_SHOWN = 8
    }
}
