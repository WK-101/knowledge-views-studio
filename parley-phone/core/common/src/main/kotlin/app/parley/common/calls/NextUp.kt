package app.parley.common.calls

import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.CircleStatus
import app.parley.common.circle.GoodTime
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What a line of Next up is about, in the order the lines rank. */
enum class NextUpKind {
    /** Calls owed now and times you promised: the To call list's due items, as one line. */
    TO_CALL,

    /** A call round under way with people still to tell: one line, "3 of 8 left · Next: Sam". */
    CALL_ROUND,

    /** A birthday or anniversary today. */
    OCCASION,

    /** A case file whose "Follow up by" day is today or has passed (and it isn't resolved). */
    CASE_DUE,

    /** A Circle member who is due, at the hour they're usually free. */
    GOOD_TIME,

    /** A Chapter (a label with an end) ending this week. */
    CHAPTER,

    /** Nothing owed now, but To call has items later: the way into the list stays. */
    LATER,
}

/** One candidate line: [id] names the person (lookup key) or the Chapter (label title); [lineKeys] are their numbers' line keys. */
data class NextUpLine(val kind: NextUpKind, val id: String, val lineKeys: Set<String> = emptySet())

/**
 * Next up: the To call strip at the top of Recents, grown into at most [MAX_LINES] "now" lines, ranked by [NextUpKind]:
 * calls owed and promised, a call round under way, today's birthdays and anniversaries, a case file to follow up, a
 * Circle member due in their good hours, a Chapter ending this week, and last the To call items waiting for later. Nothing shows when nothing is due. Pure: the app
 * passes the To call count and the candidates it found, already filtered for what the privacy view may show.
 */
object NextUp {
    const val MAX_LINES = 3

    /** A Chapter is "ending this week" this close to its end. */
    const val CHAPTER_DAYS = 7L

    const val TO_CALL_ID = "to-call"
    const val CALL_ROUND_ID = "call-round"
    const val LATER_ID = "later"

    /** How many cases due there are beyond the one line Next up gives them, among [candidates]. */
    fun moreCases(candidates: List<NextUpLine>): Int =
        (candidates.filter { it.kind == NextUpKind.CASE_DUE }.distinctBy { it.id }.size - 1).coerceAtLeast(0)

    /**
     * The lines to show. [toCallKeys] are the line keys of every To call item and owed missed call (due or later): a
     * person already on the list isn't offered again as a good time to call (a catch-up date you set waits there), nor
     * someone whose birthday line already says it.
     */
    fun lines(toCall: ToCallCount, toCallKeys: Set<String>, candidates: List<NextUpLine>): List<NextUpLine> {
        val out = ArrayList<NextUpLine>()
        if (toCall.due > 0) out += NextUpLine(NextUpKind.TO_CALL, TO_CALL_ID, toCallKeys)
        val occasionIds = candidates.filter { it.kind == NextUpKind.OCCASION }.map { it.id }.toSet()
        out += candidates
            .filter { it.kind != NextUpKind.TO_CALL && it.kind != NextUpKind.LATER }
            .filterNot { it.kind == NextUpKind.GOOD_TIME && (it.id in occasionIds || it.lineKeys.any(toCallKeys::contains)) }
            .distinctBy { it.kind to it.id }
            .sortedBy { it.kind.ordinal }
            .let { ranked ->
                // One case file line (the longest overdue; the app says how many more): cases never push the rest out.
                val firstCase = ranked.firstOrNull { it.kind == NextUpKind.CASE_DUE }
                ranked.filter { it.kind != NextUpKind.CASE_DUE || it === firstCase }
            }
        if (toCall.due == 0 && toCall.later > 0) out += NextUpLine(NextUpKind.LATER, LATER_ID)
        return out.take(MAX_LINES)
    }

    /**
     * Whether a Circle member is a good call now: due by their rhythm ([member], with any catch-up date already folded
     * into its quiet time, see [CatchUp.quietUntil]) and [now] falls in the hours they usually answer ([window], in
     * [zone], theirs when known).
     */
    fun goodTimeNow(member: CirclePlanner.Member, window: GoodTime.Window?, zone: ZoneId, now: Long): Boolean {
        if (window == null || CirclePlanner.status(member, now) != CircleStatus.DUE) return false
        return window.contains(Instant.ofEpochMilli(now).atZone(zone).hour)
    }

    /** Whether a Chapter ending at [endsAt] ends within [CHAPTER_DAYS] of [now] (and hasn't ended yet). */
    fun chapterEndingSoon(endsAt: Long, now: Long): Boolean = endsAt > now && endsAt - now <= CHAPTER_DAYS * DAY_MS

    /** Whole days from [now] to [endsAt] in [zone]'s calendar: 0 today, 1 tomorrow. */
    fun daysUntil(endsAt: Long, now: Long, zone: ZoneId): Long {
        val day = { t: Long -> Instant.ofEpochMilli(t).atZone(zone).toLocalDate() }
        return ChronoUnit.DAYS.between(day(now), day(endsAt))
    }

    private const val DAY_MS = 24 * ToCall.HOUR_MS
}
