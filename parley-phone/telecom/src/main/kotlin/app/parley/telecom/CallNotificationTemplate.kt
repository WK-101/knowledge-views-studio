package app.parley.telecom

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import app.parley.ui.PhotoCache

/**
 * The one shape of a call notification, shared by real calls ([CallNotifier]) and rescue calls ([RescueNotifier]) so
 * the two can't drift: the ringing call with Decline and Answer that opens the call screen full screen, the ongoing
 * call with Hang up, the calling person and the lock-screen (public) version. Callers add their own extra actions,
 * delete intent and chronometer to the builder they get back.
 */
internal object CallNotificationTemplate {
    /** The calling person; [uri] lets Do Not Disturb match "starred contacts" / "contacts only". */
    fun person(name: String, uri: String?, photoUri: String?): Person {
        val b = Person.Builder().setName(name).setImportant(true)
        uri?.let { b.setUri(it) }
        photoUri?.let { photo -> PhotoCache.peek("$photo@256")?.let { b.setIcon(IconCompat.createWithBitmap(it)) } }
        return b.build()
    }

    /** What the lock screen shows when notification content is hidden: [title] (already masked) and [text], no labels. */
    fun publicVersion(context: Context, channel: String, title: String, text: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

    /** A call notification's common part: who, what, how it shows on the lock screen and where a tap goes. */
    private fun base(context: Context, channel: String, title: String, text: String, visibility: Int, public: Notification, open: PendingIntent) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(visibility)
            .setPublicVersion(public)
            .setContentIntent(open)

    /** The ringing call: heads-up or full screen, Decline and Answer. */
    @Suppress("LongParameterList") // reason: each part of a call notification is a separate choice of the caller
    fun incoming(
        context: Context,
        channel: String,
        title: String,
        text: String,
        person: Person,
        visibility: Int,
        public: Notification,
        open: PendingIntent,
        decline: PendingIntent,
        answer: PendingIntent,
    ): NotificationCompat.Builder = base(context, channel, title, text, visibility, public, open)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setFullScreenIntent(open, true)
        .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
        .addPerson(person)

    /** The answered (or dialling, or held) call, silent, with Hang up. */
    @Suppress("LongParameterList") // reason: each part of a call notification is a separate choice of the caller
    fun ongoing(
        context: Context,
        channel: String,
        title: String,
        text: String,
        person: Person,
        visibility: Int,
        public: Notification,
        open: PendingIntent,
        hangUp: PendingIntent,
    ): NotificationCompat.Builder = base(context, channel, title, text, visibility, public, open)
        .setSilent(true)
        // CallStyle needs a full-screen intent or a foreground service. The ongoing channel is not high-importance,
        // so this never pops up; it only satisfies the platform check.
        .setFullScreenIntent(open, false)
        .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp))
        .addPerson(person)
}
