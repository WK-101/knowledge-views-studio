package app.parley.rescue

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.R
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.common.catching
import app.parley.shortcuts.Shortcuts
import app.parley.work.PrivateNotice

/** The helper a check-in offers, as it may be named now. */
data class CheckInHelper(val name: String, val number: String)

/**
 * A check-in that went unanswered ([app.parley.common.calls.CheckIns]): "Your check-in wasn't answered", with Text and
 * Call for the helper. Nothing is sent or dialled by itself: Text opens the messages app with the words ready for the
 * user to send, Call places the call from that tap. The lock screen shows only "Check-in", with the buttons named
 * "Text helper" and "Call helper" (never who the helper is). With no helper left it still says the check-in wasn't
 * answered, and opens Rescue call's screen.
 */
object CheckInNotice {
    fun post(context: Context, helper: CheckInHelper?) {
        val open = PrivateNotice.route(context, NotificationRequests.CHECK_IN_OPEN, IntentRoutes.ACTION_RESCUE_CALL)
        val title = context.getString(R.string.checkin_missed_title)
        val publicTitle = context.getString(R.string.checkin_missed_public)
        val text = helper?.let { context.getString(R.string.checkin_missed_text, it.name) }
        val b = PrivateNotice.builder(context, NotificationChannels.CHECK_IN, app.parley.ui.R.drawable.ic_stat_call, title, publicTitle, text, open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (helper != null) {
            val sms = text(context, helper.number)
            val call = call(context, helper)
            b.addAction(0, context.getString(R.string.checkin_text_named, helper.name), sms)
                .addAction(0, context.getString(R.string.checkin_call_named, helper.name), call)
                .setPublicVersion(
                    NotificationCompat.Builder(context, NotificationChannels.CHECK_IN)
                        .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
                        .setContentTitle(publicTitle)
                        .addAction(0, context.getString(R.string.checkin_text_helper), sms)
                        .addAction(0, context.getString(R.string.checkin_call_helper), call)
                        .build(),
                )
        }
        PrivateNotice.post(context, NotificationIds.TAG_CHECK_IN, NotificationIds.CHECK_IN_ID, b)
    }

    fun cancel(context: Context) {
        catching { NotificationManagerCompat.from(context).cancel(NotificationIds.TAG_CHECK_IN, NotificationIds.CHECK_IN_ID) }
    }

    /** The messages app, open on the helper with the words ready; the user sends them. */
    internal fun textIntent(context: Context, number: String): Intent =
        Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).putExtra("sms_body", context.getString(R.string.checkin_sms_body))

    private fun text(context: Context, number: String): PendingIntent =
        PrivateNotice.open(context, NotificationRequests.CHECK_IN_TEXT, textIntent(context, number), update = true)

    private fun call(context: Context, h: CheckInHelper): PendingIntent =
        PrivateNotice.open(context, NotificationRequests.CHECK_IN_CALL, Shortcuts.intent(context, Shortcuts.Kind.CALL, h.number, null, h.name), update = true)
}
