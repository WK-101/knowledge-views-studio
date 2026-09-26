package app.parley.common.circle

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * R4 / X6: what the kind reminders say, and when. Nothing escalates: a person appears at most once a week, "Not now"
 * pushes them a full gap further, and there are no counters.
 */
object CircleDigest {
    private const val DAY = NaturalRhythm.DAY

    /** At most this many people in one digest. */
    const val MAX_PEOPLE = 3

    /** Dates this many days ahead can appear in the digest. */
    const val DATE_WINDOW_DAYS = 7

    /** X6: the serendipity pick is someone you haven't been in touch with for over a year. */
    const val QUIET_DAYS = 365

    enum class Reason { DUE, DATE, QUIET, YEARLY }

    /** [label] and [years]: the life event of a [Reason.YEARLY] pick (R10). */
    data class Pick(val lookupKey: String, val reason: Reason, val label: String? = null, val years: Int? = null)

    /** An upcoming date of someone you know ([daysUntil] 0 = today). */
    data class UpcomingDate(val lookupKey: String, val daysUntil: Int)

    /** X6: anyone you were once in touch with (Circle or not), with the last time; never-contacted people aren't. */
    data class Quiet(val lookupKey: String, val last: Long)

    /** Weekly digest day. */
    val DIGEST_DAY: DayOfWeek = DayOfWeek.SUNDAY

    /**
     * Whether the weekly digest goes out [today]: on Sundays (once), or on the first run after a missed Sunday
     * (the phone was off), but never twice within a week.
     */
    fun isDigestDay(today: LocalDate, lastDigest: LocalDate?): Boolean {
        if (lastDigest != null && !lastDigest.isBefore(today)) return false
        if (today.dayOfWeek == DIGEST_DAY) return lastDigest == null || ChronoUnit.DAYS.between(lastDigest, today) >= 6
        return lastDigest != null && ChronoUnit.DAYS.between(lastDigest, today) >= 8
    }

    /**
     * Up to three people: the most overdue Circle member; one with an upcoming date or a life event remembered
     * yearly (R10; whichever is sooner, a birthday on a tie); and one serendipity pick (X6), someone you haven't
     * been in touch with for over a year, never the same person as last week's. A remaining date or yearly event
     * fills a free place. Each person appears once.
     *
     * [quiet]: everyone you were once in touch with; [seed] varies the serendipity pick from week to week (the
     * week number), so it isn't always the same longest-silent person.
     */
    fun pick(
        members: List<CirclePlanner.Member>,
        dates: List<UpcomingDate>,
        now: Long,
        lastQuiet: String? = null,
        quiet: List<Quiet> = members.mapNotNull { m -> m.last?.let { Quiet(m.lookupKey, it) } },
        yearly: List<YearlyEvents.Upcoming> = emptyList(),
        seed: Long = now / (7 * DAY),
    ): List<Pick> {
        val out = ArrayList<Pick>()
        val used = HashSet<String>()
        CirclePlanner.sort(members, now).firstOrNull { CirclePlanner.status(it, now) == CircleStatus.DUE }?.let {
            out += Pick(it.lookupKey, Reason.DUE)
            used += it.lookupKey
        }
        val dateQueue = (
            dates.filter { it.daysUntil in 0..DATE_WINDOW_DAYS }.map { Triple(it.daysUntil, 0, Pick(it.lookupKey, Reason.DATE)) } +
                yearly.filter { it.daysUntil in 0..DATE_WINDOW_DAYS }.map { Triple(it.daysUntil, 1, Pick(it.lookupKey, Reason.YEARLY, it.label, it.years)) }
            ).sortedWith(compareBy({ it.first }, { it.second }, { it.third.lookupKey })).map { it.third }.toMutableList()
        fun nextDate(): Pick? {
            val i = dateQueue.indexOfFirst { it.lookupKey !in used }
            return if (i < 0) null else dateQueue.removeAt(i)
        }
        nextDate()?.let { out += it; used += it.lookupKey }
        val snoozed = members.filter { it.snoozedUntil != null && now < it.snoozedUntil }.map { it.lookupKey }.toSet()
        val eligible = quiet.filter { q -> q.lookupKey !in used && q.lookupKey != lastQuiet && q.lookupKey !in snoozed && now - q.last >= QUIET_DAYS * DAY }
            .distinctBy { it.lookupKey }.sortedBy { it.lookupKey }
        if (eligible.isNotEmpty()) {
            val q = eligible[Math.floorMod(seed, eligible.size.toLong()).toInt()]
            out += Pick(q.lookupKey, Reason.QUIET)
            used += q.lookupKey
        }
        while (out.size < MAX_PEOPLE) {
            val p = nextDate() ?: break
            out += p
            used += p.lookupKey
        }
        return out.take(MAX_PEOPLE)
    }

    /**
     * AS_DUE delivery: the due members to notify now, most overdue first. Someone reminded less than half a gap ago
     * is skipped (no daily repeats), and no more than [cap] go out per week ([sentThisWeek] already did).
     */
    fun asDue(members: List<CirclePlanner.Member>, lastNudged: Map<String, Long>, now: Long, cap: Int, sentThisWeek: Int): List<String> {
        val room = (cap - sentThisWeek).coerceAtLeast(0)
        if (room == 0) return emptyList()
        return CirclePlanner.sort(members, now)
            .filter { CirclePlanner.status(it, now) == CircleStatus.DUE }
            .filter { m -> lastNudged[m.lookupKey]?.let { now - it >= m.days * DAY / 2 } ?: true }
            .take(room)
            .map { it.lookupKey }
    }

    /** Start of the week [now] falls in (Monday), for the weekly cap. */
    fun weekOf(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
}
