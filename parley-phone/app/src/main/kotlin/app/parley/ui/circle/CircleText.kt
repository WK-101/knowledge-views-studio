package app.parley.ui.circle

import app.parley.common.security.PrivacyView
import app.parley.data.security.Privacy
import android.content.res.Resources
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import app.parley.R
import app.parley.common.Messenger
import app.parley.ui.common.Format
import app.parley.common.circle.CircleStatus
import app.parley.common.circle.ContactKind
import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.InteractionType
import app.parley.common.circle.KeepInTouch
import app.parley.common.circle.KeepRhythm
import app.parley.common.circle.PeopleInsights
import app.parley.common.circle.LastContact
import app.parley.common.circle.LogMode
import app.parley.common.circle.RhythmMode

/** Texts of the Circle, in the app language. Warm and blame-free: no counters, no "overdue". */
object CircleText {
    fun status(res: Resources, s: CircleStatus): String = res.getString(
        when (s) {
            CircleStatus.DUE -> R.string.circle_status_due
            CircleStatus.SOON -> R.string.circle_status_soon
            CircleStatus.FINE -> R.string.circle_status_fine
        },
    )

    fun kind(res: Resources, k: ContactKind): String = res.getString(
        when (k) {
            ContactKind.CALL -> R.string.circle_kind_call
            ContactKind.MEET -> R.string.circle_kind_meet
            ContactKind.MESSAGE -> R.string.circle_kind_message
            ContactKind.VIDEO -> R.string.circle_kind_video
            ContactKind.OTHER -> R.string.circle_kind_other
        },
    )

    fun type(res: Resources, t: InteractionType): String = res.getString(
        when (t) {
            InteractionType.MEET -> R.string.label_met
            InteractionType.MESSAGE -> R.string.action_message
            InteractionType.VIDEO -> R.string.label_video_call
            InteractionType.OTHER -> R.string.label_other
        },
    )

    fun typeIcon(t: InteractionType): ImageVector = when (t) {
        InteractionType.MEET -> Icons.Rounded.Handshake
        InteractionType.MESSAGE -> Icons.AutoMirrored.Rounded.Chat
        InteractionType.VIDEO -> Icons.Rounded.Videocam
        InteractionType.OTHER -> Icons.Rounded.MoreHoriz
    }

    /** Channel names: app names stay as they are; the rest is translated. */
    fun channel(res: Resources, c: InteractionChannel): String = when (c) {
        InteractionChannel.SMS -> res.getString(R.string.msg_sms)
        InteractionChannel.WHATSAPP -> Messenger.WHATSAPP.label
        InteractionChannel.SIGNAL -> Messenger.SIGNAL.label
        InteractionChannel.TELEGRAM -> Messenger.TELEGRAM.label
        InteractionChannel.OTHER_APP -> res.getString(R.string.circle_channel_other_apps)
        InteractionChannel.VIDEO -> res.getString(R.string.circle_channel_video)
    }

    fun mode(res: Resources, m: LogMode): String = res.getString(
        when (m) {
            LogMode.ALWAYS -> R.string.label_always
            LogMode.ASK -> R.string.circle_mode_ask
            LogMode.NEVER -> R.string.label_never
        },
    )

    /** "Last in touch 12 days ago · call", "In touch today · met", or "Not in touch yet". */
    fun last(res: Resources, last: LastContact?, now: Long = System.currentTimeMillis()): String {
        last ?: return res.getString(R.string.circle_never)
        val days = ((now - last.time) / 86_400_000L).coerceAtLeast(0).toInt()
        val kind = kind(res, last.kind)
        return if (days == 0) res.getString(R.string.circle_last_today, kind) else res.getQuantityString(R.plurals.circle_last_days, days, days, kind)
    }

    /**
     * "Every 14 days" or "Natural rhythm · about every 20 days". What the rhythm learned from the calls is a
     * relationship insight: while a duress unlock hides them, a natural rhythm reads only "Natural rhythm".
     */
    fun rhythm(res: Resources, everyDays: Int, r: KeepRhythm, privacy: PrivacyView = Privacy.duressOnly()): String = when {
        r.mode == RhythmMode.NATURAL && !privacy.insightsShown -> res.getString(R.string.circle_rhythm_natural)
        r.mode == RhythmMode.NATURAL && r.learnedDays != null -> res.getQuantityString(R.plurals.circle_natural_days, r.learnedDays!!, r.learnedDays!!)
        r.mode == RhythmMode.NATURAL -> res.getQuantityString(R.plurals.circle_natural_learning, everyDays, everyDays)
        else -> res.getQuantityString(R.plurals.circle_every_days, everyDays, everyDays)
    }

    /**
     * How you two keep in touch, as one short paragraph of [facts] about [name] (their short name); [date] says when a
     * call was ("Tue", "yesterday"). Null with no facts, and while a duress unlock hides relationship insights.
     */
    fun keepInTouch(res: Resources, name: String, facts: List<KeepInTouch.Fact>, date: (Long) -> String, privacy: PrivacyView = Privacy.duressOnly()): String? {
        if (!privacy.insightsShown || facts.isEmpty()) return null
        return facts.joinToString(" ") { f ->
            when (f) {
                is KeepInTouch.Fact.YourTurn -> yourTurn(res, name, f.times, privacy).orEmpty()
                is KeepInTouch.Fact.Rhythm -> rhythmFact(res, f)
                is KeepInTouch.Fact.Balance -> when (f.who) {
                    PeopleInsights.FirstMover.THEM -> res.getString(R.string.circle_keep_first_them, name)
                    PeopleInsights.FirstMover.YOU -> res.getString(R.string.circle_keep_first_you)
                    PeopleInsights.FirstMover.BOTH -> res.getString(R.string.circle_keep_first_both)
                }
                is KeepInTouch.Fact.LooseEnd -> when (f.loop.kind) {
                    PeopleInsights.LoopKind.THEIR_CALL -> res.getString(R.string.circle_keep_their_call, name, date(f.loop.time))
                    PeopleInsights.LoopKind.YOUR_TRY -> res.getString(R.string.circle_keep_your_try, date(f.loop.time))
                }
            }
        }.trim().ifEmpty { null }
    }

    /** "You usually talk about every 3 weeks; it's been 5 weeks." Weeks once the usual gap is two weeks or more. */
    private fun rhythmFact(res: Resources, f: KeepInTouch.Fact.Rhythm): String {
        val weeks = f.usualDays >= WEEKS_FROM_DAYS
        val every = if (weeks) {
            val w = Math.round(f.usualDays / 7.0).toInt()
            res.getQuantityString(R.plurals.circle_keep_every_weeks, w, w)
        } else {
            res.getQuantityString(R.plurals.circle_keep_every_days, f.usualDays, f.usualDays)
        }
        if (f.sinceDays <= 0) return res.getString(R.string.circle_keep_rhythm_today, every)
        val since = if (weeks && f.sinceDays >= 7) {
            val w = Math.round(f.sinceDays / 7.0).toInt()
            res.getQuantityString(R.plurals.circle_keep_since_weeks, w, w)
        } else {
            res.getQuantityString(R.plurals.circle_keep_since_days, f.sinceDays, f.sinceDays)
        }
        return res.getString(R.string.circle_keep_rhythm, every, since)
    }

    private const val WEEKS_FROM_DAYS = 14

    /**
     * "Ana has called you the last 3 times. Your turn to call?" (the digest's line for a due member, and the start of
     * the keep-in-touch paragraph). Null without a turn, and while a duress unlock hides relationship insights.
     */
    fun yourTurn(res: Resources, name: String, times: Int?, privacy: PrivacyView = Privacy.duressOnly()): String? {
        if (times == null || !privacy.insightsShown) return null
        return res.getQuantityString(R.plurals.circle_keep_turn, times, name, times)
    }

    /** "14 calls, 3 h 10 min in the last year · 6 chats started from Parley". Null when hidden or empty. */
    fun keepSummary(res: Resources, s: KeepInTouch.Summary?, privacy: PrivacyView = Privacy.duressOnly()): String? {
        if (s == null || !privacy.insightsShown) return null
        val parts = listOfNotNull(
            when {
                s.calls == 0 -> null
                s.talkSec > 0 -> res.getQuantityString(R.plurals.circle_keep_calls_talk, s.calls, s.calls, Format.duration(s.talkSec))
                else -> res.getQuantityString(R.plurals.circle_keep_calls, s.calls, s.calls)
            },
            s.chats.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.circle_keep_chats, it, it) },
        )
        return parts.joinToString(res.getString(R.string.main_separator)).ifEmpty { null }
    }
}
