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
import androidx.core.graphics.drawable.IconCompat
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.common.calls.LockScreenCaller
import app.parley.telecom.ui.InCallActivity
import app.parley.ui.PhotoCache

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
        val n = NotificationCompat.Builder(context, CallNotifier.CH_INCOMING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(shownTitle(context, call))
            .setContentText(context.getString(R.string.notif_incoming_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, call, CallNotifier.CH_INCOMING))
            .setContentIntent(open(context))
            .setFullScreenIntent(open(context), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline(context, call.id), answer(context, call.id)))
            .addPerson(person)
            .build()
        val posted = notify(context, NotificationIds.RESCUE_INCOMING, n)
        return posted && fullScreenWorks(context)
    }

    /** The answered call, with Hang up and the running time. */
    fun ongoing(context: Context, call: CallUi) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm?.cancel(NotificationIds.RESCUE_INCOMING)
        val person = person(context, call)
        val n = NotificationCompat.Builder(context, CallNotifier.CH_ONGOING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(shownTitle(context, call))
            .setContentText(context.getString(R.string.notif_ongoing_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, call, CallNotifier.CH_ONGOING))
            .setContentIntent(open(context))
            // CallStyle needs a full-screen intent; the ongoing channel never pops up, so this only satisfies the check.
            .setFullScreenIntent(open(context), false)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUp(context, call.id)))
            .addPerson(person)
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
        runCatching { TelecomGraph.dependencies.appearance.value.lockScreenCaller }.getOrDefault(LockScreenCaller.NAME)

    /** The caller as the lock screen may show it right now. */
    private fun shownTitle(context: Context, call: CallUi): String {
        val mode = lockMode()
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
        return if (!locked || mode.showsName) call.title else call.forLockScreen(mode, placeholder(context, call)).title
    }

    private fun placeholder(context: Context, call: CallUi): String =
        context.getString(if (call.state == CallState.RINGING) R.string.notif_incoming_call else R.string.notif_ongoing_call)

    private fun publicVersion(context: Context, call: CallUi, channel: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.forLockScreen(lockMode(), placeholder(context, call)).title)
            .setContentText(placeholder(context, call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

    private fun person(context: Context, call: CallUi): Person {
        val b = Person.Builder().setName(shownTitle(context, call)).setImportant(true)
        call.photoUri?.let { uri -> PhotoCache.peek("$uri@256")?.let { b.setIcon(IconCompat.createWithBitmap(it)) } }
        return b.build()
    }

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
