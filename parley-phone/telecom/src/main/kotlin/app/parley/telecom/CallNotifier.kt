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
        val ringing = live.firstOrNull { it.state == CallState.RINGING && !CallManager.isScreening(it.id) }
        val ringingPending = live.any { it.state == CallState.RINGING && CallManager.isScreening(it.id) }
        val primary = ringing ?: live.firstOrNull { it.state == CallState.ACTIVE } ?: live.firstOrNull { it.state != CallState.RINGING }
        if (primary == null) {
            if (!ringingPending) cancelAll()
            return
        }
        if (primary.state == CallState.RINGING && primary.silenced) {
            post(buildSilenced(primary), primary)
            return
        }
        if (primary.state == CallState.RINGING) {
            val otherActive = live.any { it.id != primary.id && it.state != CallState.RINGING }
            if ((!canUseFullScreen() || !notificationsAllowed()) && !otherActive && directlyLaunched.add(primary.id)) {
                context.startActivity(InCallActivity.intent(context, false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            post(buildIncoming(primary), primary)
        } else {
            post(buildOngoing(primary), primary)
        }
    }

    private fun post(n: android.app.Notification, call: CallUi) {
        val nmc = NotificationManagerCompat.from(context)
        try {
            nmc.notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
            // Some Android versions reject CallStyle outside a foreground service: fall back to a plain notification.
            try {
                nmc.notify(NOTIFICATION_ID, plainFallback(call))
            } catch (_: Exception) {
            }
        }
        val photo = call.photoUri
        if (photo != null && photoLoaded.add(call.id + photo)) {
            scope.launch {
                PhotoCache.load(context, photo, 256)
                update(CallManager.state.value)
            }
        }
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
        nm.cancel(NOTIFICATION_ID)
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
            .build()
    }

    private fun buildSilenced(call: CallUi): android.app.Notification =
        NotificationCompat.Builder(context, CH_SILENCED)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle("Silenced call · ${call.title}")
            .setContentText("Matched one of your blocking rules")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .addAction(0, "Decline", action(CallActionReceiver.ACTION_DECLINE, call.id, 4))
            .addAction(0, "Answer", answerIntent(call))
            .build()

    private fun buildOngoing(call: CallUi): android.app.Notification {
        val audio = CallManager.audio.value
        val b = NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_call)
            .setContentTitle(call.title)
            .setContentText(
                when (call.state) {
                    CallState.DIALING, CallState.CONNECTING -> "Calling…"
                    CallState.HOLDING -> "On hold"
                    else -> subtitle(call).ifEmpty { "Ongoing call" }
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
            .addAction(0, if (audio.current?.type == RouteType.SPEAKER) "Speaker off" else "Speaker", action(CallActionReceiver.ACTION_SPEAKER, call.id, 8))
        if (call.state == CallState.ACTIVE && call.connectTimeMillis > 0) {
            b.setUsesChronometer(true).setWhen(call.connectTimeMillis).setShowWhen(true)
        }
        return b.build()
    }

    companion object {
        const val CH_INCOMING = "incoming_calls_v1"
        const val CH_ONGOING = "ongoing_calls_v1"
        const val CH_SILENCED = "silenced_calls_v1"
        const val NOTIFICATION_ID = 4711
    }
}
