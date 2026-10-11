package app.parley.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes

/**
 * Parley's one shape for a notice that isn't a call: its channel made on the way, the full text only after unlocking
 * (a neutral [publicTitle] stands in on the lock screen), never mirrored to a watch, gone once tapped.
 */
object PrivateNotice {
    fun builder(
        context: Context,
        channel: String,
        @DrawableRes icon: Int,
        title: CharSequence,
        publicTitle: CharSequence,
        text: CharSequence? = null,
        open: PendingIntent? = null,
    ): NotificationCompat.Builder {
        NoticeChannels.ensure(context, channel)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, channel, icon, publicTitle))
            .setLocalOnly(true)
    }

    /**
     * What the lock screen shows in place of a private notice: a neutral [title] only, never a name or a number.
     * [category] and [count] let the system group it (missed calls) without saying more.
     */
    fun publicVersion(
        context: Context,
        channel: String,
        @DrawableRes icon: Int,
        title: CharSequence,
        category: String? = null,
        count: Int = 0,
    ): Notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(icon)
        .setContentTitle(title)
        .apply { if (category != null) setCategory(category) }
        .apply { if (count > 0) setNumber(count) }
        .build()

    /** Posts [b]; false when notifications aren't allowed (the screens still show what it said). */
    fun post(context: Context, tag: String?, id: Int, b: NotificationCompat.Builder): Boolean = try {
        NotificationManagerCompat.from(context).notify(tag, id, b.build())
        true
    } catch (_: SecurityException) {
        false
    }

    /** Opens [intent] (an activity) from a notice; [update] lets a newer notice replace the extras. */
    fun open(context: Context, request: Int, intent: Intent, update: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context, request, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or (if (update) PendingIntent.FLAG_UPDATE_CURRENT else 0),
    )

    /** Opens Parley's own entry with [action]. */
    fun route(context: Context, request: Int, action: String): PendingIntent = open(context, request, IntentRoutes.own(context).setAction(action))
}
