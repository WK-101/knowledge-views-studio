package app.parley.common.situations

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.Schedule
import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SituationSuggestionsTest {
    private val zone = ZoneId.of("Europe/London")

    /** Monday 5 October 2026, noon: the history below runs back from here. */
    private val now = at(2026, 10, 5, 12, 0)
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun daysAgo(days: Int, h: Int, mi: Int = 0) = LocalDateTime.of(2026, 10, 5, h, mi).minusDays(days.toLong()).atZone(zone).toInstant().toEpochMilli()

    private var id = 0L
    private fun call(date: Long, type: CallType, seconds: Long = 0) = CallEntry(id++, "+447700900${id % 1000}", null, type, date, seconds, null, false, false)

    /** Calls in the afternoon, five weeks back: enough history, and nothing that suggests anything. */
    private val ordinary = (1..35).map { call(daysAgo(it, 14), CallType.OUTGOING, 60) }
    private val list = Situations.builtIns()

    private fun suggest(
        calls: List<CallEntry>,
        rings: List<RingFacts> = emptyList(),
        marks: SuggestionMarks = SuggestionMarks(),
        carsKnown: Boolean = false,
        situations: List<Situation> = list,
    ) =
        SituationSuggestions.suggest(ordinary + calls, rings, situations, carsKnown, marks, now, zone)

    private fun carRing(days: Int, name: String) =
        RingFacts(daysAgo(days, 8, 10), outcome = RingOutcome.ANSWERED, answeredRoute = AnswerRoute.BLUETOOTH, answeredDevice = name)

    @Test fun nothing_without_a_pattern() {
        assertNull(suggest(emptyList()))
    }

    @Test fun a_car_kit_used_on_several_days_suggests_driving() {
        val rings = listOf(1, 2, 3, 8, 9).map { carRing(it, "Car Kit") }
        val s = suggest(emptyList(), rings)!!
        assertEquals(SuggestionKind.CAR, s.kind)
        assertEquals(Situations.DRIVING, s.situationId)
        assertEquals("Car Kit", s.device)
        val driving = s.applyTo(list.first { it.id == Situations.DRIVING })
        assertEquals(DeviceTrigger.NAMED, driving.device)
        assertEquals("Car Kit", driving.deviceName)
    }

    @Test fun headphones_or_too_few_days_suggest_no_car() {
        assertNull(suggest(emptyList(), listOf(1, 2, 3, 8, 9).map { carRing(it, "Pixel Buds") }))
        // Three days only.
        assertNull(suggest(emptyList(), listOf(1, 2, 3).map { carRing(it, "Car Kit") }))
        // Four days, but all in one week (Monday to Thursday last week).
        assertNull(suggest(emptyList(), listOf(4, 5, 6, 7).map { carRing(it, "Car Kit") }))
        assertTrue(SituationSuggestions.carLike("SYNC 3"))
        assertTrue(SituationSuggestions.carLike("My Car"))
        assertFalse(SituationSuggestions.carLike("Scarlett headphones"))
    }

    @Test fun no_car_suggestion_when_a_car_is_known_or_driving_already_switches_on() {
        val rings = listOf(1, 2, 3, 8, 9).map { carRing(it, "Car Kit") }
        assertNull(suggest(emptyList(), rings, carsKnown = true))
        val automatic = list.map { if (it.id == Situations.DRIVING) it.copy(device = DeviceTrigger.CAR) else it }
        assertNull(suggest(emptyList(), rings, situations = automatic))
    }

    @Test fun quiet_evenings_with_calls_going_unanswered_suggest_night() {
        val late = listOf(1, 5, 9, 13).map { call(daysAgo(it, 22, 40), CallType.MISSED) }
        val s = suggest(late)!!
        assertEquals(SuggestionKind.NIGHT, s.kind)
        // 21:00 also holds (nothing of yours after 21:00): the earliest start is offered.
        assertEquals(Schedule(Schedule.ALL_DAYS, 21 * 60, 7 * 60), s.schedule)
        // You took calls at 21:30: Night is offered from 22:00.
        val taken = listOf(2, 6, 10).map { call(daysAgo(it, 21, 30), CallType.INCOMING, 120) }
        assertEquals(22 * 60, suggest(late + taken)!!.schedule!!.startMinute)
    }

    @Test fun no_night_when_you_talk_late_or_night_is_already_set() {
        val late = listOf(1, 5, 9, 13).map { call(daysAgo(it, 23, 30), CallType.MISSED) }
        val talks = listOf(3, 7, 11, 15).map { call(daysAgo(it, 23, 45), CallType.OUTGOING, 300) }
        assertNull(suggest(late + talks))
        val scheduled = list.map { if (it.id == Situations.NIGHT) it.copy(schedule = Situations.NIGHT_WINDOW) else it }
        assertNull(suggest(late, situations = scheduled))
    }

    @Test fun declines_in_the_same_weekday_hour_suggest_a_meeting_time() {
        // Mondays (7, 14, 21 days back) at 10:15.
        val declined = listOf(7, 14, 21).map { call(daysAgo(it, 10, 15), CallType.REJECTED) }
        val s = suggest(declined)!!
        assertEquals(SuggestionKind.MEETING, s.kind)
        assertEquals(Schedule(1, 10 * 60, 11 * 60), s.schedule)
        // Two weeks only: not yet a habit.
        assertNull(suggest(declined.take(2)))
    }

    @Test fun at_most_one_a_week_never_a_refused_kind_and_none_on_a_new_phone() {
        val declined = listOf(7, 14, 21).map { call(daysAgo(it, 10, 15), CallType.REJECTED) }
        assertNull(suggest(declined, marks = SuggestionMarks(answeredAt = now - 3 * 24 * 3_600_000L)))
        assertEquals(SuggestionKind.MEETING, suggest(declined, marks = SuggestionMarks(answeredAt = now - SituationSuggestions.WEEK_MS))?.kind)
        assertNull(suggest(declined, marks = SuggestionMarks(never = setOf(SuggestionKind.MEETING))))
        // Only three weeks of history.
        val young = (1..20).map { call(daysAgo(it, 14), CallType.OUTGOING, 60) } + declined.take(2)
        assertNull(SituationSuggestions.suggest(young, emptyList(), list, false, SuggestionMarks(), now, zone))
    }
}
