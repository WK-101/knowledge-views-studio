package app.parley.common.circle

import app.parley.common.circle.PeopleInsights.Touch
import app.parley.common.circle.PeopleInsights.TouchKind
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import kotlin.math.ceil

/**
 * "Your year": a short look back at one calendar year, worked out when the page opens and one year at a time (the
 * history of many years is never read at once). Pure: the app hands in that year's and the year before's [Touch]es.
 *
 * Counts are conversations, not entries: calls and chats less than [PeopleInsights.CONVERSATION_GAP_MS] apart with one
 * person are one (a call back, a second try), so a bad line doesn't make someone "closer".
 */
object YourYear {
    /** Closer and quieter only among people with at least this many conversations in one of the two years. */
    const val MIN_CONVERSATIONS = 4

    /** How many people each list names. */
    const val TOP = 3

    /** The digest offers last year's page in the first days of January, once. */
    const val OFFER_DAYS = 14

    /** The digest offers it only when the year had at least this many conversations (the People card's own bar). */
    const val OFFER_MIN = PeopleInsights.REVIEW_MIN_ENTRIES

    data class Person(val key: String, val count: Int)

    /** [now]: conversations this year; [before]: the year before. */
    data class Change(val key: String, val now: Int, val before: Int) {
        val delta: Int get() = now - before
    }

    /** The longest time between two conversations with one Circle member within the year, [from] and [to] being them. */
    data class Gap(val key: String, val from: Long, val to: Long) {
        /** Whole days, rounded (a change to summer time doesn't take a day off). */
        val days: Int get() = ((to - from + NaturalRhythm.DAY / 2) / NaturalRhythm.DAY).toInt()
    }

    data class Review(
        val year: Int,
        /** Conversations in all. */
        val inTouch: Int,
        val most: List<Person>,
        /** Empty when the year before isn't in Parley's history (everyone would look closer). */
        val closer: List<Change>,
        val quieter: List<Change>,
        val gap: Gap?,
        /** Occasions marked as wished. */
        val occasions: Int,
        /** Answered calls, and chats or video calls started from Parley. */
        val calls: Int,
        val chats: Int,
    )

    /** The first and the first-after moments of [year] in [zone]. */
    fun bounds(year: Int, zone: ZoneId): LongRange {
        val from = LocalDate.of(year, Month.JANUARY, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val next = LocalDate.of(year + 1, Month.JANUARY, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return from until next
    }

    fun yearOf(time: Long, zone: ZoneId): Int = Instant.ofEpochMilli(time).atZone(zone).year

    /** Every year with history, newest first, from the times of what happened ([times] in any order). */
    fun years(times: Sequence<Long>, zone: ZoneId): List<Int> = times.map { yearOf(it, zone) }.distinct().sorted().toList().asReversed()

    /** How many conversations [times] (one person's, in any order) make. */
    fun conversations(times: List<Long>): Int {
        if (times.isEmpty()) return 0
        val sorted = times.sorted()
        return 1 + sorted.zipWithNext().count { (a, b) -> b - a >= PeopleInsights.CONVERSATION_GAP_MS }
    }

    /**
     * The look back at [year] from [touches] (that year's and the year before's; others are ignored), with the longest
     * gap among [circle]. [historyFrom]: when Parley's history starts; closer and quieter need the whole year before.
     * Null when nothing happened that year.
     */
    fun review(touches: List<Touch>, circle: Set<String>, year: Int, zone: ZoneId, historyFrom: Long?): Review? {
        val range = bounds(year, zone)
        val before = bounds(year - 1, zone)
        val inTouch = touches.filter { it.kind.inTouch }
        val thisYear = inTouch.filter { it.time in range }
        if (thisYear.isEmpty()) return null
        val counts = thisYear.groupBy { it.key }.mapValues { (_, t) -> conversations(t.map { it.time }) }
        val most = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP).map { Person(it.key, it.value) }
        val compared = historyFrom != null && historyFrom < range.first
        val changes = if (!compared) {
            emptyList()
        } else {
            val prev = inTouch.filter { it.time in before }.groupBy { it.key }.mapValues { (_, t) -> conversations(t.map { it.time }) }
            (counts.keys + prev.keys).map { Change(it, counts[it] ?: 0, prev[it] ?: 0) }
                .filter { maxOf(it.now, it.before) >= MIN_CONVERSATIONS && it.delta != 0 }
        }
        val closer = changes.filter { it.delta > 0 }.sortedWith(compareByDescending<Change> { it.delta }.thenBy { it.key }).take(TOP)
        val quieter = changes.filter { it.delta < 0 }.sortedWith(compareBy<Change> { it.delta }.thenBy { it.key }).take(TOP)
        val gap = thisYear.filter { it.key in circle }.groupBy { it.key }.mapNotNull { (key, list) ->
            list.map { it.time }.sorted().zipWithNext().maxByOrNull { (a, b) -> b - a }?.let { (a, b) -> Gap(key, a, b) }
        }.filter { it.days > 0 }.maxWithOrNull(compareBy<Gap> { it.to - it.from }.thenByDescending { it.key })
        return Review(
            year = year,
            inTouch = counts.values.sum(),
            most = most,
            closer = closer,
            quieter = quieter,
            gap = gap,
            occasions = thisYear.count { it.kind == TouchKind.WISHED },
            calls = thisYear.count { it.kind == TouchKind.CALL_IN || it.kind == TouchKind.CALL_OUT },
            chats = thisYear.count { it.kind == TouchKind.CHAT_OUT },
        )
    }

    /**
     * When Parley's history starts inside [year] (not at its start), that moment: the page then says "Parley has calls
     * from March 2025", since older calls depend on what Android's call log kept.
     */
    fun thinFrom(historyFrom: Long?, year: Int, zone: ZoneId): Long? {
        val range = bounds(year, zone)
        // A week's grace: history that starts on 3 January is the whole year.
        return historyFrom?.takeIf { it in range && it > range.first + THIN_GRACE_DAYS * NaturalRhythm.DAY }
    }

    private const val THIN_GRACE_DAYS = 7

    /**
     * The year the digest offers to look back on on [today]: last year, in the first [OFFER_DAYS] of January, unless
     * it was offered already ([lastOffered]).
     */
    fun offerYear(today: LocalDate, lastOffered: Int?): Int? {
        if (today.month != Month.JANUARY || today.dayOfMonth > OFFER_DAYS) return null
        val year = today.year - 1
        return year.takeIf { lastOffered == null || lastOffered < it }
    }

    /** A rhythm for someone quieter this year who joins the Circle: a little looser than how often you talked before. */
    fun suggestedDays(c: Change): Int =
        ceil(365.0 / c.before.coerceAtLeast(1) * 1.5).toInt().coerceIn(NaturalRhythm.MIN_DAYS, NaturalRhythm.MAX_DAYS)
}
