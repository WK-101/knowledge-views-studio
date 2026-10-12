package app.parley.common.circle

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * "Quieter than usual": someone you used to talk to often and now much less, found by comparing two windows of your
 * own history with them (the last [RECENT_DAYS] against the [BEFORE_DAYS] before). Computed when the digest is made;
 * nothing is stored but "They're fine" ([KeepRhythm.fineUntil]). The thresholds are strict on purpose: people move in
 * together, colleagues are seasonal, chats move to other apps, and a false alarm about a friendship hurts.
 */
object Drift {
    private const val DAY = NaturalRhythm.DAY

    const val RECENT_DAYS = 182L
    const val BEFORE_DAYS = 365L

    /** At least this many days in touch in the earlier window. */
    const val MIN_BEFORE = 6

    /** The usual gap must have grown at least this many times. */
    const val FACTOR = 2

    /** And be at least this long now: from daily to every other day isn't drifting apart. */
    const val MIN_GAP_NOW = 14

    /** "They're fine" lasts this long. */
    const val FINE_DAYS = 365L

    data class Candidate(
        val lookupKey: String,
        /** The usual gap in days in the earlier window. */
        val usualBefore: Int,
        /** The usual gap since [since], counting the time since you were last in touch. */
        val usualNow: Int,
        /** Where the recent window starts ("since June"). */
        val since: Long,
    )

    private fun median(gaps: List<Int>): Double {
        val g = gaps.sorted()
        return if (g.size % 2 == 1) g[g.size / 2].toDouble() else (g[g.size / 2 - 1] + g[g.size / 2]) / 2.0
    }

    private fun gaps(days: List<LocalDate>): List<Int> = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }

    /**
     * Whether [times] (days in touch with one person) show them drifting away: at least [MIN_BEFORE] days in touch
     * before, the usual gap at least [FACTOR] times longer now and at least [MIN_GAP_NOW] days, and not in touch
     * within the old usual gap (if you just talked, you're in touch).
     */
    fun of(lookupKey: String, times: Collection<Long>, now: Long, zone: ZoneId): Candidate? {
        val recentStart = now - RECENT_DAYS * DAY
        val from = recentStart - BEFORE_DAYS * DAY
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val split = Instant.ofEpochMilli(recentStart).atZone(zone).toLocalDate()
        val days = times.filter { it in from..now }.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.distinct().sorted()
        val before = days.filter { it.isBefore(split) }
        if (before.size < MIN_BEFORE) return null
        val usualBefore = median(gaps(before)).roundToInt().coerceAtLeast(1)
        val last = days.last()
        val open = ChronoUnit.DAYS.between(last, today).toInt()
        if (open < usualBefore) return null
        val recent = days.filter { !it.isBefore(split) }
        val gapsNow = gaps(listOf(before.last()) + recent) + open
        val usualNow = median(gapsNow).roundToInt()
        if (usualNow < MIN_GAP_NOW || usualNow < FACTOR * usualBefore) return null
        return Candidate(lookupKey, usualBefore, usualNow, recentStart)
    }

    /**
     * The one person for this week's digest, or null: among [candidates], not [excluded] (in a season, "They're fine",
     * already in this digest) and not last week's ([lastPick]), the one whose gap grew most.
     */
    fun pick(candidates: List<Candidate>, excluded: Set<String>, lastPick: String?): Candidate? =
        candidates.filter { it.lookupKey !in excluded && it.lookupKey != lastPick }
            .maxWithOrNull(compareBy<Candidate> { it.usualNow.toDouble() / it.usualBefore }.thenByDescending { it.lookupKey })

    /** "They're fine": quiet about them until then. */
    fun fineUntil(now: Long): Long = now + FINE_DAYS * DAY

    /** The rhythm offered by "Add to your Circle": how often you used to talk, with the natural rhythm's margin. */
    fun suggestedDays(c: Candidate): Int = ceil(c.usualBefore * 1.5).toInt().coerceIn(NaturalRhythm.MIN_DAYS, NaturalRhythm.MAX_DAYS)
}
