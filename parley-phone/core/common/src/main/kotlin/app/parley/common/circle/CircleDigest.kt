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

    /** "Haven't heard from in a while": at least this long since the last contact. */
    const val QUIET_DAYS = 60

    enum class Reason { DUE, DATE, QUIET }

    data class Pick(val lookupKey: String, val reason: Reason)

    /** An upcoming date of someone you know ([daysUntil] 0 = today). */
    data class UpcomingDate(val lookupKey: String, val daysUntil: Int)

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
     * Up to three people: the most overdue Circle member, one with an upcoming date, and one you haven't heard from
     * in a while (never the same person as last week's "quiet" pick). Each person appears once.
     */
    fun pick(
        members: List<CirclePlanner.Member>,
        dates: List<UpcomingDate>,
        now: Long,
        lastQuiet: String? = null,
    ): List<Pick> {
        val out = ArrayList<Pick>()
        val used = HashSet<String>()
        CirclePlanner.sort(members, now).firstOrNull { CirclePlanner.status(it, now) == CircleStatus.DUE }?.let {
            out += Pick(it.lookupKey, Reason.DUE)
            used += it.lookupKey
        }
        dates.filter { it.daysUntil in 0..DATE_WINDOW_DAYS && it.lookupKey !in used }.minByOrNull { it.daysUntil }?.let {
            out += Pick(it.lookupKey, Reason.DATE)
            used += it.lookupKey
        }
        members.filter { m ->
            m.lookupKey !in used && m.lookupKey != lastQuiet && !(m.snoozedUntil != null && now < m.snoozedUntil) &&
                (m.last == null || now - m.last >= QUIET_DAYS * DAY)
        }.minByOrNull { it.last ?: Long.MIN_VALUE }?.let { out += Pick(it.lookupKey, Reason.QUIET) }
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
