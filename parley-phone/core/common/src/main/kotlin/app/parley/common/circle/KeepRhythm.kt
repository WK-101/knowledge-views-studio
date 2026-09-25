package app.parley.common.circle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** R4: how the keep-in-touch gap is chosen for one person. */
enum class RhythmMode {
    /** A fixed gap: "every N days" (contact_meta.reachOutDays). */
    EVERY,

    /** Learned from your own history with them: the usual gap ×1.5, at least a week, re-learned monthly. */
    NATURAL,
}

/**
 * R4: the rhythm part of a Circle member, stored as one small JSON value beside `reachOutDays` in contact_meta
 * (`rhythm`). A contact is in the Circle when `reachOutDays` is set; in [RhythmMode.NATURAL] it is the fallback
 * until enough history exists.
 */
@Serializable
data class KeepRhythm(
    val mode: RhythmMode = RhythmMode.EVERY,
    /** NATURAL: the learned gap in days, null until learned. */
    val learnedDays: Int? = null,
    /** NATURAL: when [learnedDays] was worked out (epoch ms). */
    val learnedAt: Long? = null,
    /** "Not now": no reminder about this person before this time (epoch ms). */
    val snoozedUntil: Long? = null,
) {
    /** The gap in days that counts now, given the stored "every N days" [everyDays]. */
    fun days(everyDays: Int): Int = if (mode == RhythmMode.NATURAL) learnedDays ?: everyDays else everyDays

    /** NATURAL rhythms are re-learned once a month. */
    fun needsRelearn(now: Long): Boolean = mode == RhythmMode.NATURAL && (learnedAt == null || now - learnedAt >= NaturalRhythm.RELEARN_MS)

    fun isSnoozed(now: Long): Boolean = snoozedUntil != null && now < snoozedUntil

    /** Null for the default (nothing to store). */
    fun encode(): String? = if (this == KeepRhythm()) null else json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun decode(text: String?): KeepRhythm = if (text.isNullOrBlank()) KeepRhythm() else runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(KeepRhythm())

        /**
         * Two rows now belong to one contact (MetaRekey): the row already at the new key wins, but a snooze is only
         * kept if both were snoozed (the earlier end), so nobody is silenced by a merge.
         */
        fun merge(into: String?, from: String?): String? {
            if (into.isNullOrBlank()) return from
            if (from.isNullOrBlank()) return into
            val a = decode(into)
            val b = decode(from)
            val snooze = if (a.snoozedUntil != null && b.snoozedUntil != null) minOf(a.snoozedUntil, b.snoozedUntil) else null
            return a.copy(snoozedUntil = snooze).encode()
        }
    }
}

/** R4: learns a person's usual rhythm from the days you were in touch (answered calls and interactions). */
object NaturalRhythm {
    const val DAY = 86_400_000L
    const val MIN_DAYS = 7
    const val MAX_DAYS = 365
    const val RELEARN_MS = 30 * DAY

    /** At least this many gaps between days in touch before a rhythm is learned. */
    const val MIN_GAPS = 3

    /** Only the last two years count, so an old habit doesn't set today's rhythm. */
    const val WINDOW_DAYS = 730L

    /**
     * The median gap between distinct days in touch ×1.5, rounded up, at least [MIN_DAYS] (never nag weekly-plus),
     * at most [MAX_DAYS]. Null with fewer than [MIN_GAPS] gaps.
     */
    fun learn(times: Collection<Long>, now: Long, zone: ZoneId): Int? {
        val since = now - WINDOW_DAYS * DAY
        val days = times.filter { it in since..now }.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.distinct().sorted()
        if (days.size < MIN_GAPS + 1) return null
        val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.sorted()
        val median = if (gaps.size % 2 == 1) gaps[gaps.size / 2].toDouble() else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2.0
        return ceil(median * 1.5).toInt().coerceIn(MIN_DAYS, MAX_DAYS)
    }

    /** [r] with a freshly learned gap when it's due for re-learning (the old one stays when there's too little history). */
    fun relearn(r: KeepRhythm, times: Collection<Long>, now: Long, zone: ZoneId): KeepRhythm =
        if (!r.needsRelearn(now)) r else r.copy(learnedDays = learn(times, now, zone) ?: r.learnedDays, learnedAt = now)
}

/** R1: the chip on a Circle row. Sorted by urgency in this order. */
enum class CircleStatus { DUE, SOON, FINE }

/** R1/R4: where each Circle member stands, from the last contact and their rhythm. */
object CirclePlanner {
    private const val DAY = NaturalRhythm.DAY

    data class Member(
        val lookupKey: String,
        /** The gap that counts now ([KeepRhythm.days]). */
        val days: Int,
        /** Latest answered call or interaction, null if none. */
        val last: Long?,
        val snoozedUntil: Long? = null,
    )

    /** "Soon" starts this long before the gap is up: a fifth of it, at least two days. */
    fun soonWindowMs(days: Int): Long = maxOf(2 * DAY, days * DAY / 5)

    fun status(m: Member, now: Long): CircleStatus {
        if (m.snoozedUntil != null && now < m.snoozedUntil) return CircleStatus.FINE
        val last = m.last ?: return CircleStatus.DUE
        val elapsed = now - last
        val gap = m.days * DAY
        return when {
            elapsed >= gap -> CircleStatus.DUE
            elapsed >= gap - soonWindowMs(m.days) -> CircleStatus.SOON
            else -> CircleStatus.FINE
        }
    }

    /** When [m] becomes due (never contacted: now), respecting a snooze. */
    fun dueAt(m: Member, now: Long): Long {
        val base = m.last?.let { it + m.days * DAY } ?: now
        return maxOf(base, m.snoozedUntil ?: Long.MIN_VALUE)
    }

    /** Most urgent first: by status, then the longest past (or closest to) their due time, then by key. */
    fun sort(members: List<Member>, now: Long): List<Member> =
        members.sortedWith(compareBy<Member>({ status(it, now).ordinal }, { dueAt(it, now) }, { it.lookupKey }))

    /**
     * "Not now": the next reminder waits one more full gap from now, so the gap doubles; it never comes back sooner
     * or more often.
     */
    fun snooze(now: Long, days: Int): Long = now + days.coerceAtLeast(1) * DAY

    /** Whole days since [last], or null. */
    fun daysSince(last: Long?, now: Long): Long? = last?.let { ((now - it) / DAY).coerceAtLeast(0) }
}
