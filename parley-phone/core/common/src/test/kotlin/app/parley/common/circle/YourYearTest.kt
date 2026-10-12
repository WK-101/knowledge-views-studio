package app.parley.common.circle

import app.parley.common.circle.PeopleInsights.Touch
import app.parley.common.circle.PeopleInsights.TouchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class YourYearTest {
    private val zone = ZoneId.of("Europe/London")
    private val hour = 3_600_000L

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long = LocalDate.of(y, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    /** [n] conversations with [key] in [year], a week apart from mid-January. */
    private fun weekly(key: String, year: Int, n: Int, kind: TouchKind = TouchKind.CALL_OUT): List<Touch> =
        (0 until n).map { Touch(key, at(year, 1, 14) + it * 7 * NaturalRhythm.DAY, kind) }

    @Test fun counts_conversations_not_entries() {
        // A call, a second try an hour later and a call back count once; the next day is another conversation.
        val t = listOf(at(2025, 3, 1, 9), at(2025, 3, 1, 10), at(2025, 3, 1, 11), at(2025, 3, 2, 12))
        assertEquals(2, YourYear.conversations(t))
        assertEquals(0, YourYear.conversations(emptyList()))
        // Missed calls and unanswered tries are not being in touch.
        val r = YourYear.review(
            listOf(Touch("a", at(2025, 3, 1), TouchKind.CALL_IN), Touch("a", at(2025, 3, 1) + hour, TouchKind.CALL_OUT),
                Touch("b", at(2025, 3, 5), TouchKind.MISSED_IN)),
            emptySet(), 2025, zone, null,
        )!!
        assertEquals(1, r.inTouch)
        assertEquals(listOf(YourYear.Person("a", 1)), r.most)
    }

    @Test fun nothing_that_year_is_no_page() {
        assertNull(YourYear.review(weekly("a", 2024, 5), emptySet(), 2025, zone, at(2020, 1, 1)))
    }

    @Test fun closer_and_quieter_among_people_with_four_or_more() {
        val touches = weekly("ana", 2024, 2) + weekly("ana", 2025, 10) + // closer by 8
            weekly("ben", 2024, 12) + weekly("ben", 2025, 3) + // quieter by 9
            weekly("cat", 2024, 1) + weekly("cat", 2025, 3) + // fewer than 4 either year: left out
            weekly("dan", 2024, 6) + // not at all this year: quieter by 6
            weekly("eve", 2024, 5) + weekly("eve", 2025, 5) // the same: neither
        val r = YourYear.review(touches, emptySet(), 2025, zone, at(2023, 6, 1))!!
        assertEquals(listOf(YourYear.Change("ana", 10, 2)), r.closer)
        assertEquals(listOf("ben", "dan"), r.quieter.map { it.key })
        assertEquals(listOf("ana", "eve", "ben"), r.most.map { it.key })
        assertEquals(10 + 3 + 3 + 5, r.inTouch)
        // The most-closer and quieter lists stop at three.
        val many = (1..5).flatMap { weekly("p$it", 2024, 4 + it) }
        assertEquals(3, YourYear.review(many + weekly("x", 2025, 1), emptySet(), 2025, zone, at(2023, 1, 1))!!.quieter.size)
    }

    @Test fun no_comparison_when_the_year_before_isnt_in_the_history() {
        val touches = weekly("ana", 2025, 10)
        val r = YourYear.review(touches, emptySet(), 2025, zone, at(2025, 3, 10))!!
        assertTrue(r.closer.isEmpty() && r.quieter.isEmpty())
        assertTrue(YourYear.review(touches, emptySet(), 2025, zone, null)!!.closer.isEmpty())
    }

    @Test fun longest_gap_in_the_circle_with_its_dates() {
        val touches = listOf(
            Touch("ana", at(2025, 2, 1), TouchKind.CALL_IN), Touch("ana", at(2025, 6, 1), TouchKind.CALL_OUT),
            Touch("ana", at(2025, 7, 1), TouchKind.LOGGED),
            // Longer, but not in the Circle.
            Touch("ben", at(2025, 1, 2), TouchKind.CALL_IN), Touch("ben", at(2025, 12, 1), TouchKind.CALL_IN),
            // Before the year: not part of this year's gap.
            Touch("ana", at(2024, 1, 1), TouchKind.CALL_IN),
        )
        val gap = YourYear.review(touches, setOf("ana"), 2025, zone, null)!!.gap!!
        assertEquals("ana", gap.key)
        assertEquals(at(2025, 2, 1), gap.from)
        assertEquals(at(2025, 6, 1), gap.to)
        assertEquals(120, gap.days)
    }

    @Test fun occasions_calls_and_chats() {
        val touches = listOf(
            Touch("a", at(2025, 1, 5), TouchKind.WISHED), Touch("b", at(2025, 2, 5), TouchKind.WISHED),
            Touch("a", at(2025, 3, 5), TouchKind.CALL_IN), Touch("a", at(2025, 4, 5), TouchKind.CALL_OUT),
            Touch("c", at(2025, 5, 5), TouchKind.CHAT_OUT), Touch("c", at(2025, 5, 6), TouchKind.UNANSWERED_OUT),
        )
        val r = YourYear.review(touches, emptySet(), 2025, zone, null)!!
        assertEquals(2, r.occasions)
        assertEquals(2, r.calls)
        assertEquals(1, r.chats)
    }

    @Test fun years_newest_first_in_the_phones_zone() {
        // New Year's Eve in London is already New Year's Day further east.
        val eve = at(2024, 12, 31, 23)
        assertEquals(listOf(2025, 2024, 2023), YourYear.years(sequenceOf(at(2023, 5, 1), eve, at(2025, 2, 1), at(2024, 1, 1)), zone))
        assertEquals(listOf(2025), YourYear.years(sequenceOf(eve), ZoneId.of("Asia/Tokyo")))
    }

    @Test fun thin_history_note_only_in_the_year_it_starts() {
        val march = at(2025, 3, 10)
        assertEquals(march, YourYear.thinFrom(march, 2025, zone))
        assertNull(YourYear.thinFrom(march, 2026, zone))
        assertNull(YourYear.thinFrom(at(2025, 1, 3), 2025, zone))
        assertNull(YourYear.thinFrom(null, 2025, zone))
    }

    @Test fun the_digest_offers_last_year_once_in_early_january() {
        assertEquals(2025, YourYear.offerYear(LocalDate.of(2026, 1, 4), null))
        assertEquals(2025, YourYear.offerYear(LocalDate.of(2026, 1, 14), 2024))
        assertNull(YourYear.offerYear(LocalDate.of(2026, 1, 11), 2025))
        assertNull(YourYear.offerYear(LocalDate.of(2026, 1, 15), null))
        assertNull(YourYear.offerYear(LocalDate.of(2025, 12, 28), null))
    }

    @Test fun a_quieter_persons_suggested_rhythm() {
        // Every 2 weeks or so before: a little looser than that.
        assertEquals(22, YourYear.suggestedDays(YourYear.Change("b", 4, 26)))
        assertEquals(NaturalRhythm.MAX_DAYS, YourYear.suggestedDays(YourYear.Change("b", 0, 1)))
        assertEquals(NaturalRhythm.MIN_DAYS, YourYear.suggestedDays(YourYear.Change("b", 0, 300)))
    }
}
