package app.parley.telecom

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import app.parley.common.calltime.CallChronometer
import app.parley.telecom.ui.InCallActivity
import app.parley.ui.PhotoCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Posts the incoming (full-screen) and ongoing call notifications. The incoming notification is
 * posted synchronously from the call-added path; the caller photo is added when it has loaded.
 */
// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
class CallNotifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directlyLaunched = HashSet<String>()
    private val photoLoaded = HashSet<String>()

    init {
        instance = this
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, context.getString(R.string.channel_incoming_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                // Telecom plays the ringtone and vibration; the channel itself stays silent.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, context.getString(R.string.channel_ongoing_calls), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SILENCED, context.getString(R.string.channel_silenced_calls), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
        )
    }

    fun canUseFullScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true

    private fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun update(calls: List<CallUi>) {
        val live = calls.filter { it.isLive }
        if (live.isEmpty()) {
            cancelAll()
            return
        }
        // Incoming (ringing) and ongoing calls use separate notifications, so a call-waiting call
        // is a *new* notification that pops up (heads-up / full-screen) rather than a silent update.
        val ringing = live.firstOrNull { it.state == CallState.RINGING && !CallManager.isScreening(it.id) }
        val ongoing = live.firstOrNull { it.state == CallState.ACTIVE }
            ?: live.firstOrNull { it.state != CallState.RINGING }

        if (ringing == null) {
            cancel(INCOMING_ID)
            dismissedIncoming = null
        } else if (ringing.blockingDecline) {
            // P2: "Block & decline" is under way: nothing to answer or decline any more.
            cancel(INCOMING_ID)
        } else if (ringing.id == dismissedIncoming) {
            // The user swiped this call's ringing/"Ringing silently" notification away: it stays away.
        } else if (ringing.silenced) {
            post(INCOMING_ID, ringing, "s") { buildSilenced(ringing) }
        } else {
            if ((!canUseFullScreen() || !notificationsAllowed()) && ongoing == null && directlyLaunched.add(ringing.id)) {
                context.startActivity(InCallActivity.intent(context, false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            post(INCOMING_ID, ringing, "i") { buildIncoming(ringing) }
        }

        if (ongoing == null) {
            cancel(ONGOING_ID)
        } else {
            val a = CallManager.audio.value
            val timing = CallClock.timings.value[ongoing.id]
            // The chronometer counts by itself: the signature changes when the end time changes, not every second (T3).
            val chrono = CallChronometer.display(ongoing.connectTimeMillis, timing?.countdown, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis())
            post(ONGOING_ID, ongoing, "o${a.muted}${a.current?.type}${chrono.signature}${timing?.canExtend}") { buildOngoing(ongoing, timing, chrono) }
        }
    }

    private val lastPosted = HashMap<Int, String>()

    /** The ringing call whose notification the user swiped away (not posted again while it rings). */
    private var dismissedIncoming: String? = null

    /**
     * F6: the user swiped a call notification away (allowed for ongoing notifications since Android 14). Without it
     * there's no way back to an active, held or dialling call or its hang-up button, so that one is posted again
     * straight away — but only while the call it belonged to is still live. A ringing or "Ringing silently"
     * notification the user dismissed stays dismissed (the call screen and the system ringer still work).
     */
    fun onDismissed(id: Int, callId: String?) {
        if (id != ONGOING_ID) {
            if (callId != null) dismissedIncoming = callId
            return
        }
        lastPosted.remove(id)
        val live = CallManager.state.value.filter { it.isLive }
        if (live.isEmpty() || (callId != null && live.none { it.id == callId })) return
        update(CallManager.state.value)
    }

    /** Called when the in-call service goes away: no more re-posting from dismiss intents. */
    fun release() {
        cancelAll()
        if (instance === this) instance = null
    }

    /** Posts unless the visible content is unchanged (NotificationManager rate-limits updates). */
    private fun post(id: Int, call: CallUi, variant: String, build: () -> android.app.Notification) {
        val photo = call.photoUri
        val photoReady = photo != null && PhotoCache.peek("$photo@256") != null
        val signature = listOf(variant, call.id, call.state, call.title, call.label, call.number, call.accountLabel, call.connectTimeMillis, photoReady, confirmDecline()).joinToString("|")
        if (lastPosted[id] == signature) return
        lastPosted[id] = signature
        val nmc = NotificationManagerCompat.from(context)
        try {
            nmc.notify(id, build())
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
            // Some Android versions reject CallStyle outside a foreground service: fall back to a plain notification.
            try {
                nmc.notify(id, plainFallback(call))
            } catch (_: Exception) {
            }
        }
        if (photo != null && !photoReady && photoLoaded.add(call.id + photo)) {
            scope.launch {
                PhotoCache.load(context, photo, 256)
                update(CallManager.state.value)
            }
        }
    }

    private fun cancel(id: Int) {
        if (lastPosted.remove(id) != null) nm.cancel(id)
    }

    private fun plainFallback(call: CallUi): android.app.Notification {
        val ringing = call.state == CallState.RINGING
        return NotificationCompat.Builder(context, if (ringing) CH_INCOMING else CH_ONGOING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.title)
            .setContentText(context.getString(if (ringing) R.string.notif_incoming_call else R.string.notif_ongoing_call))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .apply { if (ringing) setFullScreenIntent(contentIntent(), true) }
            .addAction(0, context.getString(if (ringing) R.string.notif_decline else R.string.notif_hang_up), if (ringing) declineIntent(call.id, 9) else action(CallActionReceiver.ACTION_HANGUP, call.id, 9))
            .setDeleteIntent(dismissIntent(if (ringing) INCOMING_ID else ONGOING_ID, call.id))
            .build()
    }

    fun cancelAll() {
        nm.cancel(INCOMING_ID)
        nm.cancel(ONGOING_ID)
        lastPosted.clear()
        directlyLaunched.clear()
        dismissedIncoming = null
    }

    private fun person(call: CallUi): Person {
        val b = Person.Builder().setName(call.title).setImportant(true)
        call.photoUri?.let { uri -> PhotoCache.peek("$uri@256")?.let { b.setIcon(IconCompat.createWithBitmap(it)) } }
        return b.build()
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(context, 1, InCallActivity.intent(context, false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(action: String, id: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, req,
            Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Delete intent: re-posts the notification if the user swipes it away while the call is live (F6). */
    private fun dismissIntent(notificationId: Int, callId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, 20 + notificationId % 100,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DISMISSED)
                .putExtra(CallActionReceiver.EXTRA_ID, callId)
                .putExtra(CallActionReceiver.EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * F14: the vault's "Private" label never goes into a call notification: notifications can be shown on the lock
     * screen (and read by notification listeners), and the label would reveal that the caller is a private contact.
     */
    private fun subtitle(call: CallUi): String = listOfNotNull(
        app.parley.common.NotificationPrivacy.shownLabel(call.label),
        call.number?.takeIf { call.name != null },
        call.accountLabel,
    ).joinToString(context.getString(R.string.tc_separator))

    /** X4: "Confirm before declining" (simple mode) covers the notification's Decline too. */
    private fun confirmDecline(): Boolean = runCatching { TelecomGraph.dependencies.appearance.value.confirmDecline }.getOrDefault(false)

    /**
     * Decline in a call notification: straight away, or, with "Confirm before declining" on, the call screen asking
     * "Decline this call?" (a stray tap on a heads-up notification never sends a call away).
     */
    private fun declineIntent(id: String, req: Int): PendingIntent =
        if (!confirmDecline()) {
            action(CallActionReceiver.ACTION_DECLINE, id, req)
        } else {
            PendingIntent.getActivity(
                context, 30 + req,
                InCallActivity.intent(context, false).setAction(InCallActivity.ACTION_ASK_DECLINE).putExtra(CallActionReceiver.EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private fun answerIntent(call: CallUi): PendingIntent = PendingIntent.getActivity(
        context, 2,
        InCallActivity.intent(context, false).setAction(InCallActivity.ACTION_ANSWER).putExtra(CallActionReceiver.EXTRA_ID, call.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What the lock screen shows when notification content is hidden: who (as on the call screen), no labels. */
    private fun publicVersion(call: CallUi, channel: String, text: String): android.app.Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

    private fun buildIncoming(call: CallUi): android.app.Notification {
        val answer = answerIntent(call)
        return NotificationCompat.Builder(context, CH_INCOMING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.title)
            .setContentText(subtitle(call).ifEmpty { context.getString(R.string.notif_incoming_call) })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion(call, CH_INCOMING, context.getString(R.string.notif_incoming_call)))
            .setContentIntent(contentIntent())
            .setFullScreenIntent(contentIntent(), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(call), declineIntent(call.id, 3), answer))
            .addAction(0, context.getString(R.string.notif_ignore), action(CallActionReceiver.ACTION_IGNORE, call.id, 10))
            .setDeleteIntent(dismissIntent(INCOMING_ID, call.id))
            .build()
    }

    private fun buildSilenced(call: CallUi): android.app.Notification =
        NotificationCompat.Builder(context, CH_SILENCED)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle(context.getString(R.string.notif_silenced_call_title, call.title))
            .setContentText(context.getString(if (CallManager.isScreening(call.id)) R.string.notif_checking else R.string.notif_ringing_silently))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .addAction(0, context.getString(R.string.notif_decline), declineIntent(call.id, 4))
            .addAction(0, context.getString(R.string.notif_answer), answerIntent(call))
            .setDeleteIntent(dismissIntent(INCOMING_ID, call.id))
            .build()

    private fun buildOngoing(call: CallUi, timing: CallTiming?, chrono: CallChronometer.Display): android.app.Notification {
        val audio = CallManager.audio.value
        val limited = timing?.countdown?.hasEnd == true
        val b = NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.title)
            .setContentText(
                when (call.state) {
                    CallState.DIALING, CallState.CONNECTING -> context.getString(R.string.incall_status_calling)
                    CallState.HOLDING -> context.getString(R.string.incall_status_on_hold)
                    else -> if (limited) endsText(timing, chrono) else subtitle(call).ifEmpty { context.getString(R.string.notif_ongoing_call) }
                },
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(publicVersion(call, CH_ONGOING, context.getString(R.string.notif_ongoing_call)))
            .setContentIntent(contentIntent())
            .setDeleteIntent(dismissIntent(ONGOING_ID, call.id))
            // CallStyle needs a full-screen intent or a foreground service. The ongoing channel is not
            // high-importance, so this never pops up; it only satisfies the platform check.
            .setFullScreenIntent(contentIntent(), false)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(call), action(CallActionReceiver.ACTION_HANGUP, call.id, 6)))
            .addAction(0, context.getString(if (audio.muted) R.string.notif_unmute else R.string.notif_mute), action(CallActionReceiver.ACTION_MUTE, call.id, 7))
        if (limited && timing.canExtend) {
            // Wrap-up actions replace Speaker while a limit runs (T3).
            b.addAction(0, context.getString(R.string.notif_plus_5_min), action(CallActionReceiver.ACTION_EXTEND, call.id, 11))
            b.addAction(0, context.getString(R.string.notif_dont_end), action(CallActionReceiver.ACTION_KEEP_GOING, call.id, 12))
        } else {
            b.addAction(0, context.getString(if (audio.current?.type == RouteType.SPEAKER) R.string.notif_speaker_off else R.string.notif_speaker), action(CallActionReceiver.ACTION_SPEAKER, call.id, 8))
        }
        if ((call.state == CallState.ACTIVE || call.state == CallState.HOLDING) && call.connectTimeMillis > 0) {
            b.setUsesChronometer(true).setChronometerCountDown(chrono.countDown).setWhen(chrono.whenMillis).setShowWhen(true)
        }
        return b.build()
    }

    private fun endsText(timing: CallTiming, chrono: CallChronometer.Display): String {
        val at = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(chrono.whenMillis))
        return listOfNotNull(timing.source, context.getString(R.string.notif_ends_at, at)).joinToString(context.getString(R.string.tc_separator)).replaceFirstChar { it.uppercase() }
    }

    companion object {
        const val CH_INCOMING = "incoming_calls_v1"
        const val CH_ONGOING = "ongoing_calls_v1"
        const val CH_SILENCED = "silenced_calls_v1"
        const val INCOMING_ID = 4711
        const val ONGOING_ID = 4713

        /** The live notifier while the in-call service runs, for the dismiss intent (main thread only). */
        internal var instance: CallNotifier? = null
    }
}
