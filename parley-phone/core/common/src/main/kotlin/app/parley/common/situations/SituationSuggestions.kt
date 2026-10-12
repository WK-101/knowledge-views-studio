package app.parley.common.situations

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.Schedule
import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.RingFacts
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.IsoFields

/** What a suggestion offers: Driving when a car kit connects, Night in the quiet hours, or a weekly Meeting time. */
enum class SuggestionKind { CAR, NIGHT, MEETING }

/**
 * One suggestion: the built-in Situation it changes ([situationId]) and how: the Bluetooth [device] that switches it on
 * ([SuggestionKind.CAR]), or the [schedule] it keeps ([SuggestionKind.NIGHT], [SuggestionKind.MEETING]). Who may ring
 * stays as that Situation has it; its page, opened after, holds the "who" picker.
 */
data class SituationSuggestion(val kind: SuggestionKind, val situationId: String, val device: String? = null, val schedule: Schedule? = null) {
    /** [s] with the suggestion taken. */
    fun applyTo(s: Situation): Situation = when (kind) {
        SuggestionKind.CAR -> s.copy(device = DeviceTrigger.NAMED, deviceName = device)
        SuggestionKind.NIGHT, SuggestionKind.MEETING -> s.copy(schedule = schedule)
    }
}

/** The only things kept about suggestions: when one was last answered, and the kinds never to suggest again. */
data class SuggestionMarks(val answeredAt: Long = 0, val never: Set<SuggestionKind> = emptySet())

/**
 * Situations that suggest themselves: at most one card a week, worked out on this phone from what Parley already has
 * (the call history, and the Bluetooth device a call was answered on). Nothing new is recorded to find them, and only
 * the answer is kept ([SuggestionMarks]). None until the history covers [HISTORY_DAYS], so a new phone isn't nagged;
 * none for a Situation that already switches itself on, and never a kind the user said not to suggest.
 */
object SituationSuggestions {
    const val WEEK_MS = 7 * 24 * 3_600_000L

    /** The history needs to reach this far back before anything is suggested. */
    const val HISTORY_DAYS = 28

    /** Only this much of the history is looked at, so an old habit doesn't speak for today. */
    const val LOOK_BACK_DAYS = 56

    /** A car kit: calls answered on it on this many days, in at least [CAR_WEEKS] weeks. */
    const val CAR_DAYS = 4
    const val CAR_WEEKS = 2

    /** Night: at least this many calls went unanswered in the window, and you made or took almost none. */
    const val NIGHT_UNANSWERED = 3
    val NIGHT_STARTS = listOf(21 * 60, 22 * 60, 23 * 60)
    const val NIGHT_END = 7 * 60

    /** Meeting: calls declined in the same weekday hour in this many different weeks. */
    const val MEETING_WEEKS = 3
    val MEETING_HOURS = 8..17

    /** Words in a Bluetooth name that say it is a car's (a headset's isn't offered for Driving). */
    private val CAR_WORDS = listOf("car", "auto", "sync", "uconnect", "kit", "mylink", "mmi", "carplay", "vehicle", "drive")

    /** The suggestion to show now, or null. [carsKnown]: the drive profile already has a car marked. */
    fun suggest(
        calls: List<CallEntry>,
        rings: List<RingFacts>,
        list: List<Situation>,
        carsKnown: Boolean,
        marks: SuggestionMarks,
        now: Long,
        zone: ZoneId,
    ): SituationSuggestion? {
        if (now - marks.answeredAt < WEEK_MS) return null
        val oldest = calls.minOfOrNull { it.date } ?: return null
        if (now - oldest < HISTORY_DAYS * DAY_MS) return null
        val since = now - LOOK_BACK_DAYS * DAY_MS
        val recent = calls.filter { it.date in since..now }
        fun free(id: String) = list.firstOrNull { it.id == id }?.takeIf { !it.automatic }
        return listOf(SuggestionKind.CAR, SuggestionKind.NIGHT, SuggestionKind.MEETING).filter { it !in marks.never }.firstNotNullOfOrNull { k ->
            when (k) {
                SuggestionKind.CAR -> free(Situations.DRIVING)?.takeIf { !carsKnown }?.let { car(rings.filter { it.startedAt in since..now }, zone) }
                SuggestionKind.NIGHT -> free(Situations.NIGHT)?.takeIf(Situations::silencesAnyone)?.let { night(recent, zone) }
                SuggestionKind.MEETING -> free(Situations.MEETING)?.let { meeting(recent, zone) }
            }
        }
    }

    /** Whether a Bluetooth device's name says it is a car's. */
    fun carLike(name: String): Boolean {
        val words = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return words.any { it in CAR_WORDS }
    }

    /** The car kit calls were answered on most days, if on enough days and weeks. */
    internal fun car(rings: List<RingFacts>, zone: ZoneId): SituationSuggestion? = rings
        .filter { it.answeredRoute == AnswerRoute.BLUETOOTH }
        .mapNotNull { r -> r.answeredDevice?.trim()?.takeIf { it.isNotEmpty() && carLike(it) }?.let { it to at(r.startedAt, zone) } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, times) -> times.map { it.toLocalDate() }.distinct() }
        .filter { (_, days) -> days.size >= CAR_DAYS && days.map(::week).distinct().size >= CAR_WEEKS }
        .maxByOrNull { it.value.size }
        ?.let { SituationSuggestion(SuggestionKind.CAR, Situations.DRIVING, device = it.key) }

    /** The earliest evening start after which you made or took next to no calls, while some rang unanswered. */
    internal fun night(calls: List<CallEntry>, zone: ZoneId): SituationSuggestion? {
        val days = calls.map { at(it.date, zone).toLocalDate() }.let { d -> if (d.isEmpty()) 0 else (d.max().toEpochDay() - d.min().toEpochDay() + 1).toInt() }
        // At most one call of your own in the window for every four weeks looked at.
        val allowed = days / HISTORY_DAYS
        return NIGHT_STARTS.firstOrNull { start ->
            val inWindow = calls.filter { c -> minute(c.date, zone).let { it >= start || it < NIGHT_END } }
            inWindow.count(::yours) <= allowed && inWindow.count(::unanswered) >= NIGHT_UNANSWERED
        }?.let { SituationSuggestion(SuggestionKind.NIGHT, Situations.NIGHT, schedule = Schedule(Schedule.ALL_DAYS, it, NIGHT_END)) }
    }

    /** The weekday hour you declined calls in on most weeks, when you rarely take or make calls in it. */
    internal fun meeting(calls: List<CallEntry>, zone: ZoneId): SituationSuggestion? {
        val slots = calls.groupBy { c -> at(c.date, zone).let { it.dayOfWeek to it.hour } }
            .filterKeys { (d, h) -> d.value <= DayOfWeek.FRIDAY.value && h in MEETING_HOURS }
        val best = slots.mapNotNull { (slot, list) ->
            val declined = list.filter { it.type == CallType.REJECTED }
            val weeks = declined.map { week(at(it.date, zone).toLocalDate()) }.distinct().size
            (slot to declined.size).takeIf { weeks >= MEETING_WEEKS && list.count(::yours) * 2 <= declined.size }
        }
            // The most declines wins; on a tie, the earliest in the week.
            .maxWithOrNull(compareBy({ it.second }, { -(it.first.first.value * 24 + it.first.second) })) ?: return null
        val (day, hour) = best.first
        val schedule = Schedule(1 shl (day.value - 1), hour * 60, (hour + 1) * 60)
        return SituationSuggestion(SuggestionKind.MEETING, Situations.MEETING, schedule = schedule)
    }

    private fun yours(c: CallEntry) = c.type == CallType.OUTGOING || (c.type == CallType.INCOMING && c.durationSec > 0)
    private fun unanswered(c: CallEntry) = c.type == CallType.MISSED || c.type == CallType.REJECTED
    private fun at(millis: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)
    private fun minute(millis: Long, zone: ZoneId): Int = at(millis, zone).let { it.hour * 60 + it.minute }
    private fun week(d: java.time.LocalDate): Int = d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    private const val DAY_MS = 24 * 3_600_000L
}
