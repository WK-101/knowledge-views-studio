package app.parley.blocking

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.IntentRoutes
import app.parley.MainActivity
import app.parley.R
import app.parley.calls.NoticeCaller
import app.parley.common.BlockAction
import app.parley.common.BlockReason
import app.parley.common.Decision
import app.parley.common.NotificationChannels
import app.parley.common.NotificationIds
import app.parley.common.NotificationPrivacy
import app.parley.common.NotificationRequests
import app.parley.common.NotifyLevel
import app.parley.common.PhoneIdentity
import app.parley.common.VerdictKind
import app.parley.common.catching
import app.parley.common.suspendRunCatching
import app.parley.container
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.ScreenedCall
import app.parley.ui.common.Format
import app.parley.ui.settings.bidiLtr
import app.parley.work.PrivateNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Per-verdict notification channels: Blocked, Reported (a spam list blocked it) and Likely spam (a list
 * warned but the call rang), each switchable in system settings; plus the busy auto-reply.
 * Rules can override the level (none / quiet / normal).
 */
object BlockingNotifier {
    const val GROUP = NotificationChannels.SCREENING_GROUP
    const val CH_BLOCKED = NotificationChannels.SCREEN_BLOCKED
    const val CH_REPORTED = NotificationChannels.SCREEN_REPORTED
    const val CH_LIKELY_SPAM = NotificationChannels.SCREEN_LIKELY_SPAM
    const val CH_BUSY = NotificationChannels.SCREEN_BUSY_REPLY

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP, context.getString(R.string.blk_ch_group)))
        fun ch(id: String, name: String, desc: String, importance: Int) = NotificationChannel(id, name, importance).apply {
            description = desc
            group = GROUP
            setShowBadge(false)
        }
        nm.createNotificationChannels(
            listOf(
                ch(CH_BLOCKED, context.getString(R.string.blk_ch_blocked), context.getString(R.string.blk_ch_blocked_desc), NotificationManager.IMPORTANCE_LOW),
                ch(
                    CH_REPORTED,
                    context.getString(R.string.blk_notify_reported),
                    context.getString(R.string.blk_ch_reported_desc),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                ch(
                    CH_LIKELY_SPAM,
                    context.getString(R.string.blk_ch_likely),
                    context.getString(R.string.blk_ch_likely_desc),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                ch(CH_BUSY, context.getString(R.string.blk_ch_busy), context.getString(R.string.blk_ch_busy_desc), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    /**
     * Posts what screening decided, off the call path: finding who called may open the private contacts' key. Calls
     * share a notification, and finding the first can take longer than the next: each is numbered as it arrives, and
     * one found after a newer one was posted is dropped ([PostOrder]).
     */
    fun onScreened(context: Context, c: DataContainer, e: ScreenedCall) {
        val app = context.applicationContext
        val turn = order.next()
        c.scope.launch(Dispatchers.IO) { suspendRunCatching { post(app, c, e, turn) } }
    }

    private val order = PostOrder()

    /** Notices posted in the order their calls arrived: an older one never replaces a newer one under the same id. */
    internal class PostOrder {
        private var issued = 0L
        private val posted = HashMap<Int, Long>()

        /** The next call's number, in arrival order. */
        @Synchronized
        fun next(): Long = ++issued

        /** Whether the notice of call [turn] may be posted as [id] now (nothing newer was); runs [post] if so, in order. */
        @Synchronized
        fun claim(id: Int, turn: Long, post: () -> Unit): Boolean {
            if ((posted[id] ?: 0L) > turn) return false
            posted[id] = turn
            post()
            return true
        }
    }

    private fun level(e: ScreenedCall, default: NotifyLevel): NotifyLevel {
        val own = e.result.rule?.notify ?: e.result.notify
        return if (own != NotifyLevel.DEFAULT) own else default
    }

    /**
     * The notification for [e], or null when there is none. Who called is found as for a missed call ([NoticeCaller]):
     * a private contact is named only while private contacts show, and while the phone is [locked] the name follows
     * "Caller on the lock screen". The lock screen's own version names nobody and shows no number. When the settings
     * can't be read, private contacts count as hidden and the lock screen shows nothing about the caller.
     */
    internal suspend fun build(context: Context, c: DataContainer, e: ScreenedCall, locked: Boolean): Built? {
        val number = e.request.number?.takeIf { it.isNotBlank() && !e.request.hidden }
        val region = PhoneEnv.countryIso(context, e.request.simId)
        // One view for the whole notice; closed (nothing private, nothing on the lock screen) when it can't be read.
        val privacy = c.privacy.now()
        val lockScreen = NoticeCaller.lockScreenRule(privacy)
        val found = NoticeCaller.find(c, number, region, privacy)
        // Screening knew the caller as one of yours, but the notice may not name them: a private contact while private
        // contacts are hidden, or one whose status couldn't be read. Their notice reads like a stranger's, without the
        // rule that caught them (it can name their label) and without the quiet-hours reply only contacts get.
        val unnamed = e.isContact && !found.isContact
        val shownNumber = number?.let { bidiLtr(Format.number(it, region)) } ?: context.getString(R.string.blk_private_number)
        val who = NotificationPrivacy.screenedCallName(found.savedName, found.vaultName, privacy.privateHidden, found.network, shownNumber, lockScreen, locked)
        val decision = e.result.decision
        val quietHours = decision is Decision.Block && decision.reason == BlockReason.OFF_HOURS
        val replies = e.isContact && !unnamed && e.settings.busyReply
        if (quietHours && replies && number != null) return quietHoursReply(context, e, number, who)
        val why = NotificationPrivacy.screenedCallReason(BlockingText.verdict(context, e.result.verdict?.text), unnamed, lockScreen, locked)
        return screened(context, e, number, who, why)
    }

    /** Someone you know was silenced by off hours: a one-tap reply through the SMS app. */
    private fun quietHoursReply(context: Context, e: ScreenedCall, number: String, who: String?): Built {
        val s = e.settings
        val id = NotificationIds.screenBusy(PhoneIdentity.key(number, null))
        val reply = PendingIntent.getActivity(
            context, id, BlockingActions.replyIntent(number, s.busyReplyText).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val plain = context.getString(R.string.blk_n_quiet_hours_plain)
        val b = NotificationCompat.Builder(context, CH_BUSY)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_missed)
            .setContentTitle(who?.let { context.getString(R.string.blk_n_quiet_hours_title, it) } ?: plain)
            .setContentText(context.getString(R.string.blk_n_reply_text, s.busyReplyText))
            .setContentIntent(reply)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .addAction(0, context.getString(R.string.blk_n_reply), reply)
            .private(context, CH_BUSY, app.parley.ui.R.drawable.ic_stat_missed, plain, NotificationCompat.CATEGORY_MISSED_CALL)
        return Built(id, b)
    }

    /** Blocked, silenced, reported or likely spam, with the reason it may give ([why]: see [build]); null when the level says nothing. */
    private fun screened(context: Context, e: ScreenedCall, number: String?, who: String?, why: String?): Built? {
        val decision = e.result.decision
        val (channel, lvl) = channelOf(e) ?: return null
        if (lvl == NotifyLevel.NONE) return null
        val blocked = decision is Decision.Block
        val (named, plain) = when {
            !blocked -> R.string.blk_n_likely_title to R.string.blk_n_likely_plain
            (decision as Decision.Block).action == BlockAction.SILENCE -> R.string.blk_n_silenced_title to R.string.blk_n_silenced_plain
            else -> R.string.blk_n_blocked_title to R.string.blk_n_blocked_plain
        }
        val open = PendingIntent.getActivity(
            context, NotificationRequests.SCREEN_OPEN,
            IntentRoutes.own(context).setAction(MainActivity.ACTION_OPEN_BLOCKING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = app.parley.ui.R.drawable.ic_stat_block
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(who?.let { context.getString(named, it) } ?: context.getString(plain))
            .setContentText(why.orEmpty())
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(lvl == NotifyLevel.QUIET)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(if (blocked) 0 else 10 * 60_000L)
            .private(context, channel, icon, context.getString(plain), NotificationCompat.CATEGORY_STATUS)
        if (blocked) addBlockedActions(context, e, number, b)
        return Built(if (blocked) ID_BLOCKED else ID_LIKELY, b)
    }

    /** The channel for [e] and how loud, or null when nothing is said about it. */
    private fun channelOf(e: ScreenedCall): Pair<String, NotifyLevel>? {
        val s = e.settings
        val decision = e.result.decision
        return when {
            decision is Decision.Block && decision.reason == BlockReason.LIST -> CH_REPORTED to level(e, s.notifyReported)
            decision is Decision.Block -> CH_BLOCKED to level(e, s.notifyBlocked)
            e.result.verdict?.kind == VerdictKind.LIKELY_SPAM -> CH_LIKELY_SPAM to level(e, s.notifyLikelySpam)
            else -> null
        }
    }

    /** "Not spam" (with a number) and "Expecting a call" (unless already on). */
    private fun addBlockedActions(context: Context, e: ScreenedCall, number: String?, b: NotificationCompat.Builder) {
        if (number != null) {
            val notSpam = context.getString(R.string.blk_not_spam)
            val pack = e.result.listHit?.packId
            b.addAction(action(context, notSpam, BlockingActionReceiver.ACTION_NOT_SPAM, number, pack, NotificationRequests.SCREEN_NOT_SPAM))
        }
        if (!e.settings.snoozeActive(System.currentTimeMillis())) {
            val snooze = context.getString(R.string.blk_n_expecting_1h)
            b.addAction(action(context, snooze, BlockingActionReceiver.ACTION_SNOOZE, null, null, NotificationRequests.SCREEN_SNOOZE))
        }
    }

    /** A notification to post: its id and its builder. */
    internal class Built(val id: Int, val builder: NotificationCompat.Builder)

    private suspend fun post(context: Context, c: DataContainer, e: ScreenedCall, turn: Long) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        channels(context)
        val built = build(context, c, e, keyguardLocked(context)) ?: return
        order.claim(built.id, turn) { notify(nm, built.id, built.builder) }
    }

    /** Whether the phone is locked now; when it can't be told, it counts as locked. */
    private fun keyguardLocked(context: Context): Boolean =
        catching { context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }.getOrNull() ?: true

    /**
     * Full text only after unlocking: a lock screen that hides sensitive content shows [publicTitle] alone (no name,
     * no number); never mirrored to a watch.
     */
    private fun NotificationCompat.Builder.private(context: Context, channel: String, icon: Int, publicTitle: String, category: String) = apply {
        setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        setPublicVersion(PrivateNotice.publicVersion(context, channel, icon, publicTitle, category))
        setLocalOnly(true)
    }

    private fun notify(nm: NotificationManagerCompat, id: Int, b: NotificationCompat.Builder) {
        try {
            nm.notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * A notification action that changes screening. It needs the phone unlocked: on Android 12+ the system asks for
     * it before sending the broadcast; before that, the action opens a (non-exported, invisible) activity, which the
     * lock screen only starts after unlocking.
     */
    private fun action(context: Context, title: String, action: String, number: String?, packId: String?, req: Int): NotificationCompat.Action {
        if (Build.VERSION.SDK_INT >= 31) {
            val pi = PendingIntent.getBroadcast(
                context, req,
                Intent(
                    context, BlockingActionReceiver::class.java,
                ).setAction(action).putExtra(BlockingActionReceiver.EXTRA_NUMBER, number).putExtra(BlockingActionReceiver.EXTRA_PACK, packId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Action.Builder(0, title, pi).setAuthenticationRequired(true).build()
        }
        val pi = PendingIntent.getActivity(
            context, req,
            Intent(
                context, BlockingActionActivity::class.java,
            ).setAction(action).putExtra(BlockingActionReceiver.EXTRA_NUMBER, number).putExtra(BlockingActionReceiver.EXTRA_PACK, packId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action.Builder(0, title, pi).build()
    }

    const val ID_BLOCKED = NotificationIds.SCREEN_BLOCKED
    const val ID_LIKELY = NotificationIds.SCREEN_LIKELY_SPAM
}

/** Notification actions: "Not spam" and "Expecting a call". Not exported. */
class BlockingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.container.scope.launch {
            try {
                perform(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Runs a notification action ("Not spam", "Expecting a call"), from the receiver or [BlockingActionActivity]. */
        suspend fun perform(context: Context, intent: Intent) {
            val c = context.container
            when (intent.action) {
                ACTION_NOT_SPAM -> intent.getStringExtra(EXTRA_NUMBER)?.let { BlockingActions.notSpam(c, it, intent.getStringExtra(EXTRA_PACK)) }
                ACTION_SNOOZE -> BlockingActions.snooze(c, 60)
                else -> return
            }
            NotificationManagerCompat.from(context).cancel(BlockingNotifier.ID_BLOCKED)
            ExpectingCallTileService.refresh(context)
        }

        const val ACTION_NOT_SPAM = "app.parley.blocking.NOT_SPAM"
        const val ACTION_SNOOZE = "app.parley.blocking.SNOOZE"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_PACK = "pack"
    }
}
