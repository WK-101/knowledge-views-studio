package app.parley.common.calls

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** "Next catch-up" after a call with a Circle member. */
enum class CatchUpChoice {
    /** Nothing set: the rhythm decides, as before. */
    USUAL,
    IN_A_WEEK,
    ON_A_DATE,
}

/**
 * The next catch-up with a Circle member, chosen after a call ("Anything to remember?"): a date you keep for yourself.
 * It is a [PromisedTime] with `told = false` on their To call item, the same record "Promise a time" writes, so To call
 * and Next up read one place; it is never said back on the call screen or as "You said you'd call". Until that date the
 * rhythm stays quiet about them ([quietUntil]): the date replaces the rhythm's next due, once. A call with them settles
 * the item and the date with it.
 */
object CatchUp {
    const val WEEK_DAYS = 7

    /** When [choice] is due: [ToCall.MORNING_HOUR] on the day, in [zone]. Null for [CatchUpChoice.USUAL] or a day not after today. */
    fun at(choice: CatchUpChoice, date: LocalDate?, now: Long, zone: ZoneId): Long? = when (choice) {
        CatchUpChoice.USUAL -> null
        CatchUpChoice.IN_A_WEEK -> ToCall.inDays(WEEK_DAYS, now, zone)
        CatchUpChoice.ON_A_DATE -> date?.let { d ->
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            d.takeIf { it.isAfter(today) }?.atTime(ToCall.MORNING_HOUR, 0)?.atZone(zone)?.toInstant()?.toEpochMilli()
        }
    }

    /**
     * [state] with the catch-up for [key] set to [at], made at [now]. Whatever was on the list for them before goes
     * first: the call that just ended settles it, and the catch-up must not inherit its older start (or that same call
     * would settle the catch-up too).
     */
    fun set(state: ToCallState, key: String, number: String, at: Long, now: Long, zone: String? = null): ToCallState =
        ToCall.remind(
            ToCall.done(state, key, now), key, number, at, now, source = ToCallSource.FOLLOW_UP, zone = zone,
            promised = PromisedTime(at = at, made = now, told = false),
        )

    /** The catch-up date kept for any of [keys] (their numbers' line keys), the earliest; null when none is set. */
    fun of(state: ToCallState, keys: Collection<String>): Long? {
        if (keys.isEmpty()) return null
        val set = keys.toSet()
        return state.items.filter { it.key in set }.mapNotNull { it.promised?.takeIf { p -> !p.told }?.at }.minOrNull()
    }

    /** The time the rhythm stays quiet until: its own "Not now" ([snoozedUntil]) or the catch-up date, whichever is later. */
    fun quietUntil(snoozedUntil: Long?, catchUpAt: Long?): Long? = listOfNotNull(snoozedUntil, catchUpAt).maxOrNull()
}
