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
        nm.createNotificationChannel(
            NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                // Telecom plays the ringtone and vibration; the channel itself stays silent.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ONGOING, "Ongoing calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SILENCED, "Silenced calls", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
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

    /** Posts unless the visible content is unchanged (NotificationManager rate-limits updates). */
    private fun post(id: Int, call: CallUi, variant: String, build: () -> android.app.Notification) {
        val photo = call.photoUri
        val photoReady = photo != null && PhotoCache.peek("$photo@256") != null
        val signature = listOf(variant, call.id, call.state, call.title, call.label, call.number, call.accountLabel, call.connectTimeMillis, photoReady).joinToString("|")
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
            .setContentText(if (ringing) "Incoming call" else "Ongoing call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .apply { if (ringing) setFullScreenIntent(contentIntent(), true) }
            .addAction(0, if (ringing) "Decline" else "Hang up", action(if (ringing) CallActionReceiver.ACTION_DECLINE else CallActionReceiver.ACTION_HANGUP, call.id, 9))
            .build()
    }

    fun cancelAll() {
        nm.cancel(INCOMING_ID)
        nm.cancel(ONGOING_ID)
        lastPosted.clear()
        directlyLaunched.clear()
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

    private fun subtitle(call: CallUi): String = listOfNotNull(
        call.label,
        call.number?.takeIf { call.name != null },
        call.accountLabel,
    ).joinToString(" · ")

    private fun answerIntent(call: CallUi): PendingIntent = PendingIntent.getActivity(
        context, 2,
        InCallActivity.intent(context, false).setAction(InCallActivity.ACTION_ANSWER).putExtra(CallActionReceiver.EXTRA_ID, call.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildIncoming(call: CallUi): android.app.Notification {
        val answer = answerIntent(call)
        return NotificationCompat.Builder(context, CH_INCOMING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.title)
            .setContentText(subtitle(call).ifEmpty { "Incoming call" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent())
            .setFullScreenIntent(contentIntent(), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person(call), action(CallActionReceiver.ACTION_DECLINE, call.id, 3), answer))
            .addAction(0, "Ignore", action(CallActionReceiver.ACTION_IGNORE, call.id, 10))
            .build()
    }

    private fun buildSilenced(call: CallUi): android.app.Notification =
        NotificationCompat.Builder(context, CH_SILENCED)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle("Silenced call · ${call.title}")
            .setContentText(if (CallManager.isScreening(call.id)) "Checking…" else "Ringing silently")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .addAction(0, "Decline", action(CallActionReceiver.ACTION_DECLINE, call.id, 4))
            .addAction(0, "Answer", answerIntent(call))
            .build()

    private fun buildOngoing(call: CallUi, timing: CallTiming?, chrono: CallChronometer.Display): android.app.Notification {
        val audio = CallManager.audio.value
        val limited = timing?.countdown?.hasEnd == true
        val b = NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.title)
            .setContentText(
                when (call.state) {
                    CallState.DIALING, CallState.CONNECTING -> "Calling…"
                    CallState.HOLDING -> "On hold"
                    else -> if (limited) endsText(timing, chrono) else subtitle(call).ifEmpty { "Ongoing call" }
                },
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent())
            // CallStyle needs a full-screen intent or a foreground service. The ongoing channel is not
            // high-importance, so this never pops up; it only satisfies the platform check.
            .setFullScreenIntent(contentIntent(), false)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person(call), action(CallActionReceiver.ACTION_HANGUP, call.id, 6)))
            .addAction(0, if (audio.muted) "Unmute" else "Mute", action(CallActionReceiver.ACTION_MUTE, call.id, 7))
        if (limited && timing.canExtend) {
            // Wrap-up actions replace Speaker while a limit runs (T3).
            b.addAction(0, "+5 min", action(CallActionReceiver.ACTION_EXTEND, call.id, 11))
            b.addAction(0, "Don't end", action(CallActionReceiver.ACTION_KEEP_GOING, call.id, 12))
        } else {
            b.addAction(0, if (audio.current?.type == RouteType.SPEAKER) "Speaker off" else "Speaker", action(CallActionReceiver.ACTION_SPEAKER, call.id, 8))
        }
        if ((call.state == CallState.ACTIVE || call.state == CallState.HOLDING) && call.connectTimeMillis > 0) {
            b.setUsesChronometer(true).setChronometerCountDown(chrono.countDown).setWhen(chrono.whenMillis).setShowWhen(true)
        }
        return b.build()
    }

    private fun endsText(timing: CallTiming, chrono: CallChronometer.Display): String {
        val at = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(chrono.whenMillis))
        return listOfNotNull(timing.source, "ends at $at").joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    companion object {
        const val CH_INCOMING = "incoming_calls_v1"
        const val CH_ONGOING = "ongoing_calls_v1"
        const val CH_SILENCED = "silenced_calls_v1"
        const val INCOMING_ID = 4711
        const val ONGOING_ID = 4713
    }
}
