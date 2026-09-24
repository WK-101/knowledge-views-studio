package app.parley.blocking

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.MainActivity
import app.parley.common.BlockReason
import app.parley.common.Decision
import app.parley.common.NotifyLevel
import app.parley.common.PhoneNumbers
import app.parley.common.VerdictKind
import app.parley.container
import app.parley.data.PhoneEnv
import app.parley.data.ScreenedCall
import kotlinx.coroutines.launch

/**
 * Per-verdict notification channels (B6): Blocked, Reported (a spam list blocked it) and Likely spam (a list
 * warned but the call rang), each switchable in system settings; plus the busy auto-reply (B27).
 * Rules can override the level (none / quiet / normal).
 */
object BlockingNotifier {
    const val GROUP = "screening"
    const val CH_BLOCKED = "screen_blocked_v1"
    const val CH_REPORTED = "screen_reported_v1"
    const val CH_LIKELY_SPAM = "screen_likely_spam_v1"
    const val CH_BUSY = "screen_busy_reply_v1"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, "Call screening"))
        fun ch(id: String, name: String, desc: String, importance: Int) = NotificationChannel(id, name, importance).apply {
            description = desc
            group = GROUP
            setShowBadge(false)
        }
        nm.createNotificationChannels(
            listOf(
                ch(CH_BLOCKED, "Blocked calls", "A call was rejected or silenced by your rules", NotificationManager.IMPORTANCE_LOW),
                ch(CH_REPORTED, "Reported by a spam list", "A call was blocked because a spam list you installed reports it", NotificationManager.IMPORTANCE_LOW),
                ch(CH_LIKELY_SPAM, "Likely spam", "A call rang but a spam list you installed warns about it", NotificationManager.IMPORTANCE_DEFAULT),
                ch(CH_BUSY, "Calls during quiet hours", "Someone you know called while off hours silenced them; reply in one tap", NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun onScreened(context: Context, e: ScreenedCall) {
        runCatching { post(context, e) }
    }

    private fun level(e: ScreenedCall, default: NotifyLevel): NotifyLevel {
        val own = e.result.rule?.notify ?: e.result.notify
        return if (own != NotifyLevel.DEFAULT) own else default
    }

    private fun post(context: Context, e: ScreenedCall) {
        val s = e.settings
        val number = e.request.number?.takeIf { it.isNotBlank() && !e.request.hidden }
        val who = e.contactName ?: number?.let { app.parley.ui.common.Format.number(it, PhoneEnv.countryIso(context)) } ?: "Private number"
        val decision = e.result.decision
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        channels(context)

        // B27: someone you know was silenced by off hours: offer a one-tap reply through the SMS app.
        if (decision is Decision.Block && decision.reason == BlockReason.OFF_HOURS && e.isContact && s.busyReply && number != null) {
            val id = ID_BUSY + (PhoneNumbers.matchKey(number).hashCode() and 0xFFF)
            val reply = PendingIntent.getActivity(
                context, id, BlockingActions.replyIntent(number, s.busyReplyText).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(context, CH_BUSY)
                .setSmallIcon(app.parley.ui.R.drawable.ic_stat_missed)
                .setContentTitle("$who called during quiet hours")
                .setContentText("Reply \"${s.busyReplyText}\"")
                .setContentIntent(reply)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .addAction(0, "Reply", reply)
            notify(nm, id, b)
            return
        }

        val kind = e.result.verdict?.kind
        val (channel, lvl) = when {
            decision is Decision.Block && decision.reason == BlockReason.LIST -> CH_REPORTED to level(e, s.notifyReported)
            decision is Decision.Block -> CH_BLOCKED to level(e, s.notifyBlocked)
            kind == VerdictKind.LIKELY_SPAM -> CH_LIKELY_SPAM to level(e, s.notifyLikelySpam)
            else -> return
        }
        if (lvl == NotifyLevel.NONE) return
        val blocked = decision is Decision.Block
        val title = when {
            !blocked -> "Likely spam: $who"
            (decision as Decision.Block).action == app.parley.common.BlockAction.SILENCE -> "Silenced call from $who"
            else -> "Blocked call from $who"
        }
        val open = PendingIntent.getActivity(
            context, 30,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_BLOCKING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val id = if (blocked) ID_BLOCKED else ID_LIKELY
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_block)
            .setContentTitle(title)
            .setContentText(e.result.verdict?.text ?: "")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(lvl == NotifyLevel.QUIET)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(if (blocked) 0 else 10 * 60_000L)
        if (blocked && number != null) {
            b.addAction(0, "Not spam", action(context, BlockingActionReceiver.ACTION_NOT_SPAM, number, e.result.listHit?.packId, 31))
        }
        if (blocked && !s.snoozeActive(System.currentTimeMillis())) {
            b.addAction(0, "Expecting a call (1 h)", action(context, BlockingActionReceiver.ACTION_SNOOZE, null, null, 32))
        }
        notify(nm, id, b)
    }

    private fun notify(nm: NotificationManagerCompat, id: Int, b: NotificationCompat.Builder) {
        try {
            nm.notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }

    private fun action(context: Context, action: String, number: String?, packId: String?, req: Int) = PendingIntent.getBroadcast(
        context, req,
        Intent(context, BlockingActionReceiver::class.java).setAction(action).putExtra(BlockingActionReceiver.EXTRA_NUMBER, number).putExtra(BlockingActionReceiver.EXTRA_PACK, packId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    const val ID_BLOCKED = 5101
    const val ID_LIKELY = 5102
    const val ID_BUSY = 5200
}

/** Notification actions: "Not spam" and "Expecting a call". Not exported. */
class BlockingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val pending = goAsync()
        c.scope.launch {
            try {
                when (intent.action) {
                    ACTION_NOT_SPAM -> intent.getStringExtra(EXTRA_NUMBER)?.let { BlockingActions.notSpam(c, it, intent.getStringExtra(EXTRA_PACK)) }
                    ACTION_SNOOZE -> BlockingActions.snooze(c, 60)
                }
                NotificationManagerCompat.from(context).cancel(BlockingNotifier.ID_BLOCKED)
                ExpectingCallTileService.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NOT_SPAM = "app.parley.blocking.NOT_SPAM"
        const val ACTION_SNOOZE = "app.parley.blocking.SNOOZE"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_PACK = "pack"
    }
}
