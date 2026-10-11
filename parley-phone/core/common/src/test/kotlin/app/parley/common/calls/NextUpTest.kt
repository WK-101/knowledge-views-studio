package app.parley.common.calls

import app.parley.common.circle.CirclePlanner
import app.parley.common.circle.GoodTime
import app.parley.common.circle.Occasion
import app.parley.common.circle.Occasions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NextUpTest {
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val day = 24 * ToCall.HOUR_MS

    private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, london).toInstant().toEpochMilli()

    private fun good(id: String, vararg keys: String) = NextUpLine(NextUpKind.GOOD_TIME, id, keys.toSet())
    private fun occasion(id: String) = NextUpLine(NextUpKind.OCCASION, id)
    private fun chapter(id: String) = NextUpLine(NextUpKind.CHAPTER, id)

    // ---- Next up's lines

    @Test fun nothing_due_shows_nothing() {
        assertEquals(emptyList<NextUpLine>(), NextUp.lines(ToCallCount(0, 0), emptySet(), emptyList()))
    }

    @Test fun lines_rank_by_kind_and_stop_at_three() {
        val lines = NextUp.lines(ToCallCount(2, 0), setOf("k1"), listOf(chapter("Exams"), good("marco", "k9"), occasion("ana"), occasion("ben")))
        assertEquals(listOf(NextUpKind.TO_CALL, NextUpKind.OCCASION, NextUpKind.OCCASION), lines.map { it.kind })
        assertEquals(NextUp.MAX_LINES, lines.size)
    }

    @Test fun someone_already_on_to_call_or_with_a_birthday_line_is_not_offered_again() {
        val lines = NextUp.lines(ToCallCount(1, 1), setOf("k1", "k2"), listOf(good("ana", "k2"), good("ben", "k5"), occasion("ben")))
        assertEquals(listOf(NextUpKind.TO_CALL, NextUpKind.OCCASION), lines.map { it.kind })
    }

    @Test fun items_only_for_later_keep_a_way_into_the_list_last() {
        val lines = NextUp.lines(ToCallCount(0, 2), setOf("k1"), listOf(chapter("Exams")))
        assertEquals(listOf(NextUpKind.CHAPTER, NextUpKind.LATER), lines.map { it.kind })
        assertTrue(NextUp.lines(ToCallCount(1, 2), emptySet(), emptyList()).none { it.kind == NextUpKind.LATER })
    }

    @Test fun a_good_time_needs_the_member_due_and_the_hour_in_their_window() {
        val now = t(2026, 10, 11, 19)
        val due = CirclePlanner.Member("ana", days = 7, last = now - 10 * day)
        val window = GoodTime.Window(18, 21)
        assertTrue(NextUp.goodTimeNow(due, window, london, now))
        assertFalse(NextUp.goodTimeNow(due, window, london, t(2026, 10, 11, 9)))
        assertFalse(NextUp.goodTimeNow(due.copy(last = now - day), window, london, now))
        assertFalse(NextUp.goodTimeNow(due, null, london, now))
        // A catch-up date set after the last call keeps the rhythm quiet until then.
        assertFalse(NextUp.goodTimeNow(due.copy(snoozedUntil = CatchUp.quietUntil(null, now + day)), window, london, now))
    }

    @Test fun a_chapter_ends_soon_within_a_week_and_not_once_over() {
        val now = t(2026, 10, 11, 12)
        assertTrue(NextUp.chapterEndingSoon(now + 3 * day, now))
        assertFalse(NextUp.chapterEndingSoon(now + 8 * day, now))
        assertFalse(NextUp.chapterEndingSoon(now - 1, now))
        assertEquals(1L, NextUp.daysUntil(t(2026, 10, 12, 0), now, london))
    }

    // ---- Next catch-up (shares the promised-time record)

    @Test fun a_catch_up_is_a_promise_not_told_and_replaces_what_was_owed() {
        val now = t(2026, 10, 11, 20)
        val before = ToCall.remind(ToCallState(), "k1", "+441", now - day, now - 2 * day, promised = PromisedTime(now - day, now - 2 * day))
        val at = CatchUp.at(CatchUpChoice.IN_A_WEEK, null, now, london)!!
        assertEquals(t(2026, 10, 18, 9), at)
        val s = CatchUp.set(before, "k1", "+441", at, now)
        val item = s.items.single()
        assertEquals(now, item.since)
        assertEquals(false, item.promised?.told)
        assertEquals(at, CatchUp.of(s, listOf("k0", "k1")))
        // Never said back as "You said you'd call".
        assertNull(ToCall.promisedTo(s, "k1"))
        assertNull(ToCall.entries(s, emptyList(), now).single().promisedAt)
        // The call that just ended (started before now) doesn't settle it; a later one does.
        assertEquals(1, ToCall.settle(s, listOf(SettlingCall("k1", now - 60_000)), now).items.size)
        assertTrue(ToCall.settle(s, listOf(SettlingCall("k1", now + 60_000)), now + 120_000).items.isEmpty())
    }

    @Test fun a_catch_up_date_must_be_after_today_and_usual_sets_nothing() {
        val now = t(2026, 10, 11, 20)
        assertNull(CatchUp.at(CatchUpChoice.USUAL, null, now, london))
        assertNull(CatchUp.at(CatchUpChoice.ON_A_DATE, LocalDate.of(2026, 10, 11), now, london))
        assertEquals(t(2026, 10, 14, 9), CatchUp.at(CatchUpChoice.ON_A_DATE, LocalDate.of(2026, 10, 14), now, london))
        assertEquals(now + day, CatchUp.quietUntil(now, now + day))
        assertEquals(now, CatchUp.quietUntil(now, null))
        assertNull(CatchUp.of(ToCallState(), emptyList()))
    }

    // ---- Today's occasion (the call screen's line and Next up)

    @Test fun a_birthday_today_wins_over_an_anniversary_and_never_for_someone_who_died() {
        val today = LocalDate.of(2026, 10, 11)
        val bday = Occasions.Dated(Occasions.TYPE_BIRTHDAY, null, "1990-10-11")
        val anniv = Occasions.Dated(Occasions.TYPE_ANNIVERSARY, null, "--10-11")
        assertEquals(Occasion.BIRTHDAY, Occasions.today(listOf(anniv, bday), today, null))
        assertEquals(Occasion.ANNIVERSARY, Occasions.today(listOf(anniv), today, null))
        assertNull(Occasions.today(listOf(bday), today.plusDays(1), null))
        val died = Occasions.Dated(0, "Date of death", "2020-01-02")
        assertNull(Occasions.today(listOf(bday, died), today, null))
    }
}
