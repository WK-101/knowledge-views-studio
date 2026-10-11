package app.parley.common.calls

import app.parley.common.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** The times "I'll call you at…" offers. */
enum class PromiseChoice {
    /** When the Situation that keeps you busy ends ("Meeting until 15:30"). */
    WHEN_FREE,
    IN_AN_HOUR,
    THIS_EVENING,

    /** Early evening where they are, when their clock differs from yours. */
    THEIR_EVENING,
    TOMORROW_MORNING,
}

data class PromiseOption(val choice: PromiseChoice, val at: Long)

/**
 * "Promise a time": the times worth telling someone you'll call back, and how far ahead each one is for them. Pure: the
 * app passes the clock, both time zones and when the Situation on now ends; it stores the result as a [PromisedTime] on
 * the To call item ([ToCall.remind]).
 */
object PromiseTimes {
    /** Promised times are on the quarter hour: "15:30", never "15:17". */
    const val STEP_MINUTES = 15L

    /** A Situation ending later than this isn't offered: "I'll call you tomorrow at 7" is a different promise. */
    const val FREE_WITHIN_MS = 12 * ToCall.HOUR_MS

    /** A Situation ending sooner than this isn't worth a promise: "In 1 hour" covers it. */
    const val FREE_AFTER_MS = 10 * 60_000L

    private const val STEP_MS = STEP_MINUTES * 60_000L

    /**
     * The choices, earliest first, never two for the same moment: when you're free ([freeAt], the end of the Situation
     * on now), in an hour, this evening, early evening their time ([theirs], when it differs from [mine]) and tomorrow
     * morning. At most four.
     */
    fun options(now: Long, mine: ZoneId, theirs: ZoneId?, freeAt: Long?): List<PromiseOption> {
        val list = ArrayList<PromiseOption>()
        freeAt?.takeIf { it - now in FREE_AFTER_MS..FREE_WITHIN_MS }?.let { list += PromiseOption(PromiseChoice.WHEN_FREE, roundUp(it)) }
        list += PromiseOption(PromiseChoice.IN_AN_HOUR, roundUp(now + ToCall.HOUR_MS))
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), mine)
        if (local.hour < ToCall.EVENING_HOUR - 1) {
            list += PromiseOption(PromiseChoice.THIS_EVENING, local.truncatedTo(ChronoUnit.DAYS).withHour(ToCall.EVENING_HOUR).toInstant().toEpochMilli())
        }
        if (theirs != null && differs(theirs, mine, now)) {
            list += PromiseOption(PromiseChoice.THEIR_EVENING, ToCall.theirEvening(roundUp(now + ToCall.HOUR_MS), theirs))
        }
        list += PromiseOption(PromiseChoice.TOMORROW_MORNING, ToCall.at(RemindTime.TOMORROW_MORNING, now, mine))
        return list.distinctBy { it.at }.sortedBy { it.at }.take(MAX_OPTIONS)
    }

    private const val MAX_OPTIONS = 4

    /** [t] on the next quarter hour (itself when it already is one). */
    fun roundUp(t: Long): Long = (t + STEP_MS - 1) / STEP_MS * STEP_MS

    fun differs(theirs: ZoneId, mine: ZoneId, now: Long): Boolean {
        val at = Instant.ofEpochMilli(now)
        return theirs.rules.getOffset(at) != mine.rules.getOffset(at)
    }

    /** The zone a promise is said in: [theirs] when their clock differs from [mine] now, else none (one clock). */
    fun saidIn(theirs: ZoneId?, mine: ZoneId, now: Long): ZoneId? = theirs?.takeIf { differs(it, mine, now) }

    /** How many days after today [at] is, on the clock of [zone]: 0 today, 1 tomorrow. */
    fun daysAhead(at: Long, now: Long, zone: ZoneId): Long {
        val day: (Long) -> LocalDate = { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        return ChronoUnit.DAYS.between(day(now), day(at))
    }

    /** The record to keep for a promise of [at], made at [now] and said in [saidIn] (null: in your own time). */
    fun record(at: Long, now: Long, saidIn: ZoneId?): PromisedTime = PromisedTime(at = at, made = now, told = true, zone = saidIn?.id)

    /**
     * When the Situation on now ends: its own end when it was switched on "until" a time ([until]), else, when its
     * schedule turned it on ([scheduled]), the end of that window. Null when it has no end (switched on by hand, a
     * device, a whole-day schedule) or nothing is on.
     */
    fun freeAt(until: Long?, scheduled: Boolean, schedule: Schedule?, now: Long, zone: ZoneId): Long? {
        until?.takeIf { it > now }?.let { return it }
        if (!scheduled || schedule == null || schedule.startMinute == schedule.endMinute) return null
        val here = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val end = here.truncatedTo(ChronoUnit.DAYS).plusMinutes(schedule.endMinute.toLong().coerceIn(0, MINUTES_A_DAY))
        val endAt = end.toInstant().toEpochMilli()
        return if (endAt > now) endAt else end.plusDays(1).toInstant().toEpochMilli()
    }

    private const val MINUTES_A_DAY = 1440L
}
