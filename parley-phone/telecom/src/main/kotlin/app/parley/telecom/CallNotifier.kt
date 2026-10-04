package app.parley.telecom

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationRequests
import app.parley.common.NotificationPrivacy
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.LockScreenCaller
import app.parley.common.calltime.CallChronometer
import app.parley.telecom.ui.InCallActivity
import app.parley.ui.PhotoCache
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Posts the incoming (full-screen) and ongoing call notifications. The incoming notification is
 * posted synchronously from the call-added path; the caller photo is added when it has loaded.
 */
// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
class CallNotifier internal constructor(
    private val context: Context,
    /** "Caller on the lock screen" and whether the phone is locked; tests pass their own. */
    private val lockModeOf: () -> LockScreenCaller,
    private val lockedOf: (() -> Boolean)?,
) {
    constructor(context: Context) : this(context, ::chosenLockMode, null)

    private val nm = context.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val directlyLaunched = HashSet<String>()
    private val photoLoaded = HashSet<String>()
    private var screenOff = false

    /**
     * Posts again when the phone locks or unlocks, so the caller's name follows "Caller on the lock screen": masked
     * while locked, in full once unlocked. Both are system broadcasts that need no permission.
     */
    private val lockWatcher = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // The keyguard may lock a little after the screen goes off: counted as locked from then until unlocked.
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            if (!lockMode().showsName && CallManager.state.value.any { it.isLive }) update(CallManager.state.value)
        }
    }

    init {
        instance = this
        val lockChanges = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
        runCatching { ContextCompat.registerReceiver(context, lockWatcher, lockChanges, ContextCompat.RECEIVER_NOT_EXPORTED) }
        createChannels(context)
    }

    fun canUseFullScreen(): Boolean =
        if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true

    private fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun lockMode(): LockScreenCaller = lockModeOf()

    private fun keyguardLocked(): Boolean =
        lockedOf?.invoke() ?: (screenOff || (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true))

    /** The call as a notification may show it now: with less about the caller while the phone is locked, if so chosen. */
    private fun shown(call: CallUi, mode: LockScreenCaller, locked: Boolean): CallUi =
        if (!locked || mode.showsName) call else call.forLockScreen(mode, placeholder(call))

    private fun placeholder(call: CallUi): String =
        context.getString(if (call.state == CallState.RINGING) R.string.notif_incoming_call else R.string.notif_ongoing_call)

    fun update(calls: List<CallUi>) {
        val mode = lockMode()
        val locked = !mode.showsName && keyguardLocked()
        val live = calls.filter { it.isLive }.map { shown(it, mode, locked) }
        if (live.isEmpty()) {
            cancelAll()
            return
        }
        // Incoming (ringing) and ongoing calls use separate notifications, so a call-waiting call
        // is a *new* notification that pops up (heads-up / full-screen) rather than a silent update.
        // Nothing is shown for a call still being screened (at most SCREEN_TIMEOUT_MS, then it fails open): a call
        // that is then blocked must never have had a notification, a name or an Answer button. The platform needs no
        // notification from an in-call service meanwhile.
        val ringing = live.firstOrNull { it.state == CallState.RINGING && !CallManager.isScreening(it.id) }
        val ongoing = live.firstOrNull { it.state == CallState.ACTIVE }
            ?: live.firstOrNull { it.state != CallState.RINGING }

        if (ringing == null || ringing.autoAnswerAt == 0L) autoAnswerTick?.cancel()
        if (ringing == null) {
            cancel(INCOMING_ID)
            dismissedIncoming = null
        } else if (ringing.blockingDecline) {
            // "Block & decline" is under way: nothing to answer or decline any more.
            cancel(INCOMING_ID)
        } else if (ringing.id == dismissedIncoming) {
            // The user swiped this call's ringing/"Ringing silently" notification away: it stays away.
        } else {
            postRinging(ringing, ongoing)
        }

        if (ongoing == null) {
            cancel(ONGOING_ID)
        } else {
            val a = CallManager.audio.value
            val timing = CallClock.timings.value[ongoing.id]?.shownFor(ongoing)
            // The chronometer counts by itself: the signature changes when the end time changes, not every second.
            val chrono = CallChronometer.display(ongoing.connectTimeMillis, timing?.countdown, SystemClock.elapsedRealtime(), System.currentTimeMillis())
            post(ONGOING_ID, ongoing, "o${a.muted}${a.current?.type}${chrono.signature}${timing?.canExtend}") { buildOngoing(ongoing, timing, chrono) }
        }
    }

    /** The notification of a ringing call nobody dismissed: silenced, still being screened, or ringing. */
    private fun postRinging(ringing: CallUi, ongoing: CallUi?) {
        if (ringing.silenced) {
            post(INCOMING_ID, ringing, "s") { buildSilenced(ringing) }
        } else {
            // Without a full-screen alert (permission, notifications, or the channel turned down) the call screen is
            // opened directly, so a ringing call always has a way to answer.
            if (ongoing == null && ringing.id !in directlyLaunched && (!canUseFullScreen() || !notificationsAllowed() || !incomingChannelAlerts(context))) {
                directlyLaunched += ringing.id
                context.startActivity(InCallActivity.intent(context, false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val left = autoAnswerLeft(ringing)
            post(INCOMING_ID, ringing, "i$left") { buildIncoming(ringing, left) }
            tickAutoAnswer(ringing)
        }
    }

    /** Whole seconds before [call] is answered on its own; 0 when it isn't armed (or the moment has come). */
    private fun autoAnswerLeft(call: CallUi): Int =
        if (call.autoAnswerAt == 0L) 0 else AutoAnswer.secondsLeft(call.autoAnswerAt, SystemClock.elapsedRealtime())

    private var autoAnswerTick: Job? = null

    /**
     * While a ringing call is armed for auto-answer, its notification (a heads-up while the phone is in use, with
     * no call screen showing) counts down once a second, so "Answering in 3 seconds" and its Cancel stay true there
     * too. Cancel or the answer itself republishes the call list, which posts it without the countdown.
     */
    private fun tickAutoAnswer(call: CallUi) {
        autoAnswerTick?.cancel()
        if (call.autoAnswerAt == 0L) return
        val untilNext = ((call.autoAnswerAt - SystemClock.elapsedRealtime()) % 1000).let { if (it <= 0) 1000 else it }
        autoAnswerTick = scope.launch {
            delay(untilNext + 20)
            update(CallManager.state.value)
        }
    }

    private val lastPosted = HashMap<Int, String>()

    /** The ringing call whose notification the user swiped away (not posted again while it rings). */
    private var dismissedIncoming: String? = null

    /**
     * The user swiped a call notification away (allowed for ongoing notifications since Android 14). Without it
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
        runCatching { context.unregisterReceiver(lockWatcher) }
        if (instance === this) instance = null
    }

    /** Posts unless the visible content is unchanged (NotificationManager rate-limits updates). */
    private fun post(id: Int, call: CallUi, variant: String, build: () -> Notification) {
        val photo = call.photoUri
        val photoReady = photo != null && PhotoCache.peek("$photo@256") != null
        val signature = listOf(variant, call.id, call.state, call.title, call.label, call.number, call.accountLabel, call.connectTimeMillis, photoReady, confirmDecline()).joinToString("|")
        if (lastPosted[id] == signature) return
        lastPosted[id] = signature
        val nmc = NotificationManagerCompat.from(context)
        try {
            nmc.notify(id, build())
            if (id == INCOMING_ID) CallManager.onNotificationShown(call.id)
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

    private fun plainFallback(call: CallUi): Notification {
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
        autoAnswerTick?.cancel()
        nm.cancel(INCOMING_ID)
        nm.cancel(ONGOING_ID)
        lastPosted.clear()
        directlyLaunched.clear()
        dismissedIncoming = null
    }

    private fun person(call: CallUi): Person {
        val b = Person.Builder().setName(call.title).setImportant(true)
        personUri(call)?.let { b.setUri(it) }
        call.photoUri?.let { uri -> PhotoCache.peek("$uri@256")?.let { b.setIcon(IconCompat.createWithBitmap(it)) } }
        return b.build()
    }

    /**
     * Who is calling, in the form Do Not Disturb matches against "starred contacts" / "contacts only": the contact's
     * lookup URI when known, else the number. Without it the system may hold back the full-screen answer UI while
     * Telecom rings for an allowed caller.
     */
    private fun personUri(call: CallUi): String? {
        val id = call.contactId
        val key = call.lookupKey
        if (id != null && id > 0 && !key.isNullOrBlank() && !ContactsContract.Contacts.isEnterpriseContactId(id)) {
            return ContactsContract.Contacts.getLookupUri(id, key).toString()
        }
        return call.number?.takeIf { !call.hidden && it.isNotBlank() }?.let { Uri.fromParts("tel", it, null).toString() }
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            context, NotificationRequests.CALL, InCallActivity.intent(context, false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(action: String, id: String, req: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, req,
            Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Delete intent: re-posts the notification if the user swipes it away while the call is live. */
    private fun dismissIntent(notificationId: Int, callId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, NotificationRequests.CALL_DISMISS + notificationId % 100,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DISMISSED)
                .putExtra(CallActionReceiver.EXTRA_ID, callId)
                .putExtra(CallActionReceiver.EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The vault's "Private" label never goes into a call notification: notifications can be shown on the lock
     * screen (and read by notification listeners), and the label would reveal that the caller is a private contact.
     * A call masked for the lock screen shows no number either (it stays in the call only for Reply and Block).
     */
    private fun subtitle(call: CallUi): String = listOfNotNull(
        NotificationPrivacy.shownLabel(call.label),
        call.number?.takeIf { call.name != null && !call.lockMasked },
        call.accountLabel,
    ).joinToString(context.getString(R.string.tc_separator))

    /** "Confirm before declining" (simple mode) covers the notification's Decline too. */
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
                context, NotificationRequests.CALL_ASK_DECLINE + req,
                InCallActivity.intent(context, false).setAction(InCallActivity.ACTION_ASK_DECLINE).putExtra(CallActionReceiver.EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    private fun answerIntent(call: CallUi): PendingIntent = PendingIntent.getActivity(
        context, NotificationRequests.CALL_ANSWER,
        InCallActivity.intent(context, false).setAction(InCallActivity.ACTION_ANSWER).putExtra(CallActionReceiver.EXTRA_ID, call.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * What the lock screen shows when notification content is hidden: who (as on the call screen, or as little as
     * "Caller on the lock screen" allows), no labels.
     */
    private fun publicVersion(call: CallUi, channel: String, text: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.forLockScreen(lockMode(), placeholder(call)).title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()

    /**
     * Public, as before, while the name may show on the lock screen; otherwise private, so a lock screen that hides
     * sensitive content shows [publicVersion] (and [update] already posts the masked call while the phone is locked).
     */
    private fun callVisibility(): Int =
        if (lockMode().showsName) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE

    private fun buildIncoming(call: CallUi, autoAnswerLeft: Int = 0): Notification {
        val answer = answerIntent(call)
        // Armed for auto-answer: the countdown replaces the subtitle, and its Cancel comes first among the extra
        // actions (a call notification shows only a few next to Decline and Answer).
        val countdown = autoAnswerLeft.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.call_auto_answer_in, it, it) }
        val b = NotificationCompat.Builder(context, CH_INCOMING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.title)
            .setContentText(countdown ?: subtitle(call).ifEmpty { context.getString(R.string.notif_incoming_call) })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(callVisibility())
            .setPublicVersion(publicVersion(call, CH_INCOMING, context.getString(R.string.notif_incoming_call)))
            .setContentIntent(contentIntent())
            .setFullScreenIntent(contentIntent(), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(call), declineIntent(call.id, 3), answer))
            .addPerson(person(call))
        if (countdown != null) {
            b.addAction(0, context.getString(R.string.notif_auto_answer_cancel), action(CallActionReceiver.ACTION_CANCEL_AUTO_ANSWER, call.id, 13))
        }
        return b.addAction(0, context.getString(R.string.notif_ignore), action(CallActionReceiver.ACTION_IGNORE, call.id, 10))
            .setDeleteIntent(dismissIntent(INCOMING_ID, call.id))
            .build()
    }

    private fun buildSilenced(call: CallUi): Notification =
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

    private fun buildOngoing(call: CallUi, timing: CallTiming?, chrono: CallChronometer.Display): Notification {
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
            .setVisibility(callVisibility())
            .setPublicVersion(publicVersion(call, CH_ONGOING, context.getString(R.string.notif_ongoing_call)))
            .setContentIntent(contentIntent())
            .setDeleteIntent(dismissIntent(ONGOING_ID, call.id))
            // CallStyle needs a full-screen intent or a foreground service. The ongoing channel is not
            // high-importance, so this never pops up; it only satisfies the platform check.
            .setFullScreenIntent(contentIntent(), false)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(call), action(CallActionReceiver.ACTION_HANGUP, call.id, 6)))
            .addPerson(person(call))
            .addAction(0, context.getString(if (audio.muted) R.string.notif_unmute else R.string.notif_mute), action(CallActionReceiver.ACTION_MUTE, call.id, 7))
        if (limited && timing.canExtend) {
            // Wrap-up actions replace Speaker while a limit runs.
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
        val at = DateFormat.getTimeFormat(context).format(Date(chrono.whenMillis))
        return listOfNotNull(timing.source, context.getString(R.string.notif_ends_at, at)).joinToString(context.getString(R.string.tc_separator)).replaceFirstChar { it.uppercase() }
    }

    companion object {
        private fun chosenLockMode(): LockScreenCaller =
            runCatching { TelecomGraph.dependencies.appearance.value.lockScreenCaller }.getOrDefault(LockScreenCaller.NAME)

        const val CH_INCOMING = NotificationChannels.INCOMING_CALLS
        const val CH_ONGOING = NotificationChannels.ONGOING_CALLS
        const val CH_SILENCED = NotificationChannels.SILENCED_CALLS
        const val INCOMING_ID = NotificationIds.CALL_INCOMING
        const val ONGOING_ID = NotificationIds.CALL_ONGOING

        /**
         * Whether the incoming-calls channel can still pop up (heads-up / full screen). False when the user turned it
         * down to silent or off; true while it doesn't exist yet (it's created at high importance).
         */
        fun incomingChannelAlerts(context: Context): Boolean = runCatching {
            val ch = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CH_INCOMING)
            ch == null || ch.importance >= NotificationManager.IMPORTANCE_HIGH
        }.getOrDefault(true)

        /** The call channels (also used by a rescue call, which can ring before any real call made them). */
        internal fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CH_INCOMING, context.getString(R.string.channel_incoming_calls), NotificationManager.IMPORTANCE_HIGH).apply {
                    // Telecom plays the ringtone and vibration; the channel itself stays silent.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

        /** The live notifier while the in-call service runs, for the dismiss intent (main thread only). */
        internal var instance: CallNotifier? = null
    }
}
