package app.parley.common.circle

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.circle.PeopleInsights.Touch
import app.parley.common.circle.PeopleInsights.TouchKind
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * How you two keep in touch, for one person (the contact page's Stay in touch card and the pre-call peek): at most
 * [MAX_FACTS] plain facts from your own history, most useful first, and a one-line summary of the last year. Facts
 * and counts only, never a score or a verdict. Every one of them is a relationship insight: callers show none while a
 * duress unlock hides them.
 */
object KeepInTouch {
    private const val DAY = NaturalRhythm.DAY

    /** At most this many facts in the sentence. */
    const val MAX_FACTS = 3

    /** "Your turn" needs them to have started at least this many conversations in a row. */
    const val YOUR_TURN_MIN = 3

    /** The summary line covers this many days. */
    const val SUMMARY_DAYS = 365

    sealed interface Fact {
        /** They started the last [times] conversations and they're due: "Ana has called you the last 3 times." */
        data class YourTurn(val times: Int) : Fact

        /** "You usually talk about every [usualDays] days; it's been [sinceDays]." */
        data class Rhythm(val usualDays: Int, val sinceDays: Int) : Fact

        /** Who usually reaches out first. */
        data class Balance(val who: PeopleInsights.FirstMover) : Fact

        /** Their call you haven't returned, or your call they haven't answered. */
        data class LooseEnd(val loop: PeopleInsights.Loop) : Fact
    }

    /** "14 calls, 3 h 10 min in the last year · 6 chats started from Parley". */
    data class Summary(val calls: Int, val talkSec: Long, val chats: Int)

    /**
     * How many conversations in a row they started, when that makes it your turn: they're [due] in your Circle, they
     * started at least [YOUR_TURN_MIN] in a row, and "who reaches out first" is on ([firstMoverShown]). Null otherwise.
     * Chats you started from Parley count as you reaching out.
     */
    fun yourTurn(touches: List<Touch>, due: Boolean, firstMoverShown: Boolean): Int? {
        if (!due || !firstMoverShown) return null
        return PeopleInsights.theirTurnsInARow(touches).takeIf { it >= YOUR_TURN_MIN }
    }

    /**
     * The facts about one person's [touches], most useful first: your turn, the usual rhythm against how long it has
     * been, who usually reaches out first, and a loose end. The balance follows the "who reaches out first" switch
     * ([firstMoverShown]), and isn't said again when your turn already says they called.
     */
    fun facts(touches: List<Touch>, due: Boolean, firstMoverShown: Boolean, now: Long, zone: ZoneId): List<Fact> {
        val out = ArrayList<Fact>()
        val turn = yourTurn(touches, due, firstMoverShown)
        if (turn != null) out += Fact.YourTurn(turn)
        val inTouch = touches.filter { it.kind.inTouch }.map { it.time }
        val usual = NaturalRhythm.usualGap(inTouch, now, zone)
        val last = inTouch.filter { it <= now }.maxOrNull()
        if (usual != null && last != null) out += Fact.Rhythm(usual, ((now - last) / DAY).toInt())
        if (firstMoverShown) {
            PeopleInsights.firstMover(touches)?.takeUnless { turn != null && it == PeopleInsights.FirstMover.THEM }?.let { out += Fact.Balance(it) }
        }
        PeopleInsights.openLoops(touches, now).firstOrNull()?.let { out += Fact.LooseEnd(it) }
        return out.take(MAX_FACTS)
    }

    /** The last year with them: answered calls and their talk time from [calls], chats you started from [touches]. */
    fun summary(calls: List<CallEntry>, touches: List<Touch>, now: Long): Summary? {
        val since = now - SUMMARY_DAYS * DAY
        val answered = calls.filter { it.date in since..now && it.durationSec > 0 && (it.type == CallType.INCOMING || it.type == CallType.OUTGOING) }
        val chats = touches.count { it.kind == TouchKind.CHAT_OUT && it.time in since..now }
        if (answered.isEmpty() && chats == 0) return null
        return Summary(answered.size, answered.sumOf { it.durationSec.coerceAtLeast(0) }, chats)
    }
}

/**
 * The quiet header on the Circle: how many are due, soon and fine, how many you were in
 * touch with this month, and the share of your talk time that was with your Circle. Counts only, never a score.
 */
object CircleGlance {
    /** Below this many members the header would only repeat the list. */
    const val MIN_MEMBERS = 4

    data class Glance(
        val due: Int,
        val soon: Int,
        val fine: Int,
        /** In touch this month against the month before; null while a duress unlock hides insights. */
        val reach: PeopleInsights.Reach?,
        /** Whole percent of this month's talk time that was with your Circle; null without calls, or while hidden. */
        val talkShare: Int?,
    )

    /**
     * The header for a Circle whose members stand at [statuses], or null below [MIN_MEMBERS]. [circleTalkSec] of
     * [allTalkSec] were with the Circle this month. While [insightsShown] is false only the status counts stay: they
     * repeat the chips every row shows anyway, so the Circle looks as it always does.
     */
    fun of(statuses: List<CircleStatus>, reach: PeopleInsights.Reach?, circleTalkSec: Long, allTalkSec: Long, insightsShown: Boolean): Glance? {
        if (statuses.size < MIN_MEMBERS) return null
        val share = if (allTalkSec > 0 && circleTalkSec > 0) (circleTalkSec * 100.0 / allTalkSec).roundToInt().coerceIn(1, 100) else null
        return Glance(
            due = statuses.count { it == CircleStatus.DUE },
            soon = statuses.count { it == CircleStatus.SOON },
            fine = statuses.count { it == CircleStatus.FINE },
            reach = reach.takeIf { insightsShown },
            talkShare = share.takeIf { insightsShown },
        )
    }
}
