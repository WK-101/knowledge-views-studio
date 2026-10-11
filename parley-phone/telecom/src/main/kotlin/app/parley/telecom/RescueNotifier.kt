package app.parley.telecom

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.common.calls.LockScreenCaller
import app.parley.telecom.ui.InCallActivity

/**
 * A rescue call's notifications ([RescueCall]): like a real call's (the incoming call with Decline and Answer that opens
 * the call screen full screen, then the ongoing call with Hang up), on the same channels, with ids and request codes of
 * their own so a real call's are never touched. The caller follows "Caller on the lock screen" as a real call does.
 */
// Posting is checked: a refused notification only means the call screen opens directly.
@SuppressLint("MissingPermission")
internal object RescueNotifier {
    /** Posts the ringing call. False when Android won't show it full screen: the caller opens the call screen itself. */
    fun incoming(context: Context, call: CallUi): Boolean {
        CallNotifier.createChannels(context)
        val person = person(context, call)
        val n = CallNotificationTemplate.incoming(
            context, CallNotifier.CH_INCOMING, shownTitle(context, call), context.getString(R.string.notif_incoming_call), person,
            VISIBILITY, publicVersion(context, call, CallNotifier.CH_INCOMING), open(context), decline(context, call.id), answer(context, call.id),
        ).build()
        val posted = notify(context, NotificationIds.RESCUE_INCOMING, n)
        return posted && fullScreenWorks(context)
    }

    /** The answered call, with Hang up and the running time. */
    fun ongoing(context: Context, call: CallUi) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(NotificationIds.RESCUE_INCOMING)
        val person = person(context, call)
        val n = CallNotificationTemplate.ongoing(
            context, CallNotifier.CH_ONGOING, shownTitle(context, call), context.getString(R.string.notif_ongoing_call), person,
            VISIBILITY, publicVersion(context, call, CallNotifier.CH_ONGOING), open(context), hangUp(context, call.id),
        )
            .setUsesChronometer(true)
            .setWhen(call.connectTimeMillis)
            .setShowWhen(true)
            .build()
        notify(context, NotificationIds.RESCUE_ONGOING, n)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NotificationIds.RESCUE_INCOMING)
        nm.cancel(NotificationIds.RESCUE_ONGOING)
    }

    private fun notify(context: Context, id: Int, n: Notification): Boolean {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return false
        return runCatching { nmc.notify(id, n) }.isSuccess
    }

    /** Whether a ringing call's notification can open the call screen by itself (as [CallNotifier] checks for real calls). */
    private fun fullScreenWorks(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val allowed = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        return allowed && CallNotifier.incomingChannelAlerts(context)
    }

    private fun lockMode(): LockScreenCaller =
        runCatching { TelecomGraph.dependencies.appearance.value.lockScreen }.getOrDefault(LockScreenCaller.NAME)

    /** The caller as the lock screen may show it right now. */
    private fun shownTitle(context: Context, call: CallUi): String {
        val mode = lockMode()
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
        return if (!locked || mode.showsName) call.title else call.forLockScreen(mode, placeholder(context, call)).title
    }

    private fun placeholder(context: Context, call: CallUi): String =
        context.getString(if (call.state == CallState.RINGING) R.string.notif_incoming_call else R.string.notif_ongoing_call)

    private fun publicVersion(context: Context, call: CallUi, channel: String): Notification =
        CallNotificationTemplate.publicVersion(context, channel, call.forLockScreen(lockMode(), placeholder(context, call)).title, placeholder(context, call))

    /**
     * No contact URI: a rescue call is never placed, so Do Not Disturb has nothing to match, and the made-up caller
     * must not point at a real contact.
     */
    private fun person(context: Context, call: CallUi): Person = CallNotificationTemplate.person(shownTitle(context, call), null, call.photoUri)

    /**
     * Always private: the rescue call's notification never shows in full on a lock screen that hides sensitive
     * content, whatever "Caller on the lock screen" says (its public version does follow that choice).
     */
    private const val VISIBILITY = NotificationCompat.VISIBILITY_PRIVATE

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, NotificationRequests.RESCUE_OPEN, InCallActivity.intent(context, false),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun answer(context: Context, id: String): PendingIntent = PendingIntent.getActivity(
        context, NotificationRequests.RESCUE_ANSWER,
        InCallActivity.intent(context, false).setAction(InCallActivity.ACTION_ANSWER).putExtra(CallActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun decline(context: Context, id: String) = action(context, CallActionReceiver.ACTION_DECLINE, id, NotificationRequests.RESCUE_DECLINE)

    private fun hangUp(context: Context, id: String) = action(context, CallActionReceiver.ACTION_HANGUP, id, NotificationRequests.RESCUE_HANG_UP)

    private fun action(context: Context, action: String, id: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
        context, req,
        Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
