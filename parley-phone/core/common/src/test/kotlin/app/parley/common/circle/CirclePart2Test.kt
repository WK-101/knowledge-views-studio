package app.parley.common.circle

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.EventDate
import app.parley.common.people.MetaRekey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** R6–R10, X1, X6. */
class CirclePart2Test {
    private val day = NaturalRhythm.DAY
    private val hour = 3_600_000L
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 9, 25)
    private val now = today.atStartOfDay(zone).toInstant().toEpochMilli() + 12 * hour

    private fun call(type: CallType, at: Long, sec: Long = 60) = CallEntry(0, "+491701234567", null, type, at, sec, null, false, false)
    private fun t(key: String, daysAgo: Double, kind: PeopleInsights.TouchKind) = PeopleInsights.Touch(key, now - (daysAgo * day).toLong(), kind)

    // R9

    @Test fun promises_are_lines_with_a_box() {
        val note = "Met at the café\n[ ] send the photos\n- [x] book the table\n  [ ]   call Mum on Sunday\n[] not a promise\n[ ]"
        val all = Promises.parse(note)
        assertEquals(listOf("send the photos", "book the table", "call Mum on Sunday"), all.map { it.text })
        assertEquals(listOf(1, 2, 3), all.map { it.line })
        assertEquals(listOf(false, true, false), all.map { it.done })
        assertEquals(2, Promises.open(note).size)
        assertTrue(Promises.parse(null).isEmpty())
    }

    @Test fun ticking_off_rewrites_only_that_line() {
        val note = "Hi\n[ ] send the photos\n- [ ] book the table"
        val done = Promises.setDone(note, 2, true)
        assertEquals("Hi\n[ ] send the photos\n- [x] book the table", done)
        assertEquals(note, Promises.setDone(done, 2, false))
        // Not a promise line, or out of range: unchanged.
        assertEquals(note, Promises.setDone(note, 0, true))
        assertEquals(note, Promises.setDone(note, 9, true))
    }

    @Test fun checkbox_button_starts_a_promise_line() {
        assertEquals("[ ] " to 4, Promises.insertBox("", 0))
        assertEquals("Their news\n[ ] " to 15, Promises.insertBox("Their news", 10))
        assertEquals("a\n[ ] \nb" to 6, Promises.insertBox("a\n\nb", 2))
        // Already a promise: nothing added.
        assertEquals("[ ] x" to 3, Promises.insertBox("[ ] x", 3))
        assertEquals("Hi · ☐ photos · ☑ table", Promises.preview("Hi\n\n[ ] photos\n[x] table"))
    }

    // R6

    @Test fun reach_counts_people_in_touch_this_month_against_the_month_before() {
        val circle = setOf("a", "b", "c")
        val touches = listOf(
            t("a", 3.0, PeopleInsights.TouchKind.CALL_IN), t("a", 40.0, PeopleInsights.TouchKind.CALL_OUT),
            t("b", 10.0, PeopleInsights.TouchKind.LOGGED),
            t("c", 5.0, PeopleInsights.TouchKind.MISSED_IN), // a missed call isn't being in touch
            t("x", 2.0, PeopleInsights.TouchKind.CALL_IN), // not in the circle
            t("c", 45.0, PeopleInsights.TouchKind.WISHED),
        )
        val r = PeopleInsights.reach(circle, touches, now)
        assertEquals(PeopleInsights.Reach(2, 2, 3), r)
        assertEquals(PeopleInsights.Trend.SAME, r.trend)
        assertEquals(PeopleInsights.Trend.UP, PeopleInsights.Reach(3, 1, 3).trend)
    }

    @Test fun open_loops_close_by_themselves_when_contact_follows() {
        val touches = listOf(
            // Their missed calls, twice, nothing since.
            t("a", 5.0, PeopleInsights.TouchKind.MISSED_IN), t("a", 2.0, PeopleInsights.TouchKind.MISSED_IN), t("a", 20.0, PeopleInsights.TouchKind.CALL_OUT),
            // Your unanswered call.
            t("b", 1.0, PeopleInsights.TouchKind.UNANSWERED_OUT),
            // Missed, then you called back: closed.
            t("c", 4.0, PeopleInsights.TouchKind.MISSED_IN), t("c", 3.0, PeopleInsights.TouchKind.CALL_OUT),
            // Your try, then a logged meeting: closed.
            t("d", 4.0, PeopleInsights.TouchKind.UNANSWERED_OUT), t("d", 1.0, PeopleInsights.TouchKind.LOGGED),
            // Too long ago: faded out.
            t("e", 45.0, PeopleInsights.TouchKind.MISSED_IN),
        )
        val loops = PeopleInsights.openLoops(touches, now)
        assertEquals(listOf("b", "a"), loops.map { it.key })
        assertEquals(PeopleInsights.LoopKind.YOUR_TRY, loops[0].kind)
        assertEquals(PeopleInsights.LoopKind.THEIR_CALL, loops[1].kind)
        assertEquals(2, loops[1].count)
    }

    @Test fun first_mover_counts_the_first_call_of_each_conversation() {
        fun at(d: Double, k: PeopleInsights.TouchKind) = t("a", d, k)
        val them = listOf(
            at(10.0, PeopleInsights.TouchKind.MISSED_IN), at(9.99, PeopleInsights.TouchKind.CALL_OUT), // one conversation, they started
            at(8.0, PeopleInsights.TouchKind.CALL_IN), at(6.0, PeopleInsights.TouchKind.CALL_OUT), at(4.0, PeopleInsights.TouchKind.CALL_OUT),
            at(2.0, PeopleInsights.TouchKind.LOGGED), // no direction
        )
        assertEquals(PeopleInsights.FirstMover.BOTH, PeopleInsights.firstMover(them))
        assertEquals(PeopleInsights.FirstMover.THEM, PeopleInsights.firstMover(them + at(1.0, PeopleInsights.TouchKind.CALL_IN) + at(0.5, PeopleInsights.TouchKind.CALL_IN)))
        val you = (1..5).map { at(it.toDouble(), PeopleInsights.TouchKind.UNANSWERED_OUT) }
        assertEquals(PeopleInsights.FirstMover.YOU, PeopleInsights.firstMover(you))
        assertNull(PeopleInsights.firstMover(you.take(3)))
    }

    @Test fun year_in_review_needs_twenty_entries() {
        val few = (1..19).map { t("a", it.toDouble(), PeopleInsights.TouchKind.CALL_IN) }
        assertNull(PeopleInsights.yearInReview(few, setOf("a"), now))
        val touches = few + listOf(
            t("b", 5.0, PeopleInsights.TouchKind.LOGGED), t("b", 105.0, PeopleInsights.TouchKind.CALL_OUT),
            t("c", 30.0, PeopleInsights.TouchKind.WISHED), t("c", 400.0, PeopleInsights.TouchKind.CALL_IN), // outside the year
            t("a", 3.0, PeopleInsights.TouchKind.MISSED_IN), // not an entry
        )
        val r = PeopleInsights.yearInReview(touches, setOf("a", "b"), now)
        assertNotNull(r)
        r!!
        assertEquals(22, r.entries)
        assertEquals("a" to 19, r.most.first())
        assertEquals("b" to 100, r.longestGap)
        assertEquals(1, r.occasions)
    }

    @Test fun calls_become_touches() {
        assertEquals(PeopleInsights.TouchKind.CALL_IN, PeopleInsights.touchOf("a", call(CallType.INCOMING, now))?.kind)
        assertEquals(PeopleInsights.TouchKind.UNANSWERED_OUT, PeopleInsights.touchOf("a", call(CallType.OUTGOING, now, 0))?.kind)
        assertEquals(PeopleInsights.TouchKind.MISSED_IN, PeopleInsights.touchOf("a", call(CallType.REJECTED, now, 0))?.kind)
        assertNull(PeopleInsights.touchOf("a", call(CallType.BLOCKED, now, 0)))
    }

    // X1

    @Test fun good_time_needs_eight_answered_calls_and_a_clear_window() {
        val evenings = (1..8).map { call(if (it % 2 == 0) CallType.INCOMING else CallType.OUTGOING, now - it * day + 7 * hour) } // 19:00 UTC
        assertNull(GoodTime.window(evenings.take(7), zone, now))
        val w = GoodTime.window(evenings, zone, now)
        assertNotNull(w)
        assertTrue(w!!.contains(19))
        assertFalse(w.contains(12))
        // In their zone (UTC+2), the same calls are at 21:00.
        assertTrue(GoodTime.window(evenings, ZoneId.of("Europe/Berlin"), now)!!.contains(21))
        // Spread over the whole day: no pattern.
        val spread = (0 until 24).map { call(CallType.OUTGOING, now - day - it * hour) }
        assertNull(GoodTime.window(spread, zone, now))
    }

    @Test fun good_time_window_can_run_past_midnight() {
        val late = (1..8).map { call(CallType.INCOMING, now - it * day + 11 * hour + (it % 2) * hour) } // 23:00 and 00:00
        val w = GoodTime.window(late, zone, now)!!
        assertTrue(w.contains(23) && w.contains(0))
        assertEquals((w.startHour + 3) % 24, w.endOfDay)
    }

    @Test fun zone_is_known_only_when_the_offsets_agree() {
        assertEquals(ZoneId.of("Europe/Berlin"), GoodTime.zoneOf(listOf("Europe/Berlin"), now))
        assertNull(GoodTime.zoneOf(listOf("America/New_York", "America/Los_Angeles"), now))
        assertNull(GoodTime.zoneOf(listOf("Nowhere/Unknown"), now))
        assertTrue(GoodTime.differs(ZoneId.of("Asia/Tokyo"), ZoneId.of("Europe/Berlin"), now))
        assertFalse(GoodTime.differs(ZoneId.of("Europe/Paris"), ZoneId.of("Europe/Berlin"), now))
        assertFalse(GoodTime.differs(null, ZoneId.of("Europe/Berlin"), now))
    }

    // R10

    @Test fun yearly_flags_are_keyed_by_type_label_and_day_and_merge_on_rekey() {
        val d = EventDate(2025, 10, 1)
        val key = YearlyEvents.key(0, " New job ", d)
        assertEquals(key, YearlyEvents.key(0, "new job", EventDate(null, 10, 1)))
        assertTrue(YearlyEvents.eligible(0) && YearlyEvents.eligible(2))
        assertFalse(YearlyEvents.eligible(3) || YearlyEvents.eligible(1))
        val stored = YearlyEvents.toggle(null, key, true)
        assertEquals(setOf(key), YearlyEvents.decode(stored))
        assertNull(YearlyEvents.toggle(stored, key, false))
        val other = YearlyEvents.key(2, "moved", d)
        val merged = MetaRekey.merge(MetaRekey.Values(yearlyEvents = stored), MetaRekey.Values(yearlyEvents = YearlyEvents.encode(setOf(other))))
        assertEquals(setOf(key, other), YearlyEvents.decode(merged.yearlyEvents))
    }

    @Test fun yearly_event_comes_up_within_the_window_with_its_years() {
        val u = YearlyEvents.upcoming("ana", "new job", EventDate(2025, 9, 28), today, 7)
        assertEquals(YearlyEvents.Upcoming("ana", "new job", 3, 1), u)
        assertNull(YearlyEvents.upcoming("ana", "new job", EventDate(2025, 10, 28), today, 7))
        // The first occurrence isn't an anniversary yet.
        assertNull(YearlyEvents.upcoming("ana", "baby", EventDate(2026, 9, 27), today, 7))
        assertNull(YearlyEvents.upcoming("ana", "moved", EventDate(null, 9, 27), today, 7)!!.years)
    }

    // X6 + R10 in the digest

    @Test fun serendipity_pick_is_someone_quiet_for_over_a_year_never_twice_in_a_row() {
        val quiet = listOf(CircleDigest.Quiet("old1", now - 400 * day), CircleDigest.Quiet("old2", now - 500 * day), CircleDigest.Quiet("recent", now - 200 * day))
        val first = CircleDigest.pick(emptyList(), emptyList(), now, quiet = quiet, seed = 0)
        assertEquals(listOf(CircleDigest.Pick("old1", CircleDigest.Reason.QUIET)), first)
        // The seed varies the pick from week to week.
        assertEquals("old2", CircleDigest.pick(emptyList(), emptyList(), now, quiet = quiet, seed = 1).single().lookupKey)
        // Never last week's person again.
        val next = CircleDigest.pick(emptyList(), emptyList(), now, lastQuiet = "old1", quiet = quiet, seed = 0)
        assertEquals("old2", next.single().lookupKey)
        // Nobody quiet long enough: no pick.
        assertTrue(CircleDigest.pick(emptyList(), emptyList(), now, quiet = listOf(quiet[2])).isEmpty())
    }

    @Test fun yearly_events_share_the_date_place_and_fill_free_ones() {
        val yearly = listOf(YearlyEvents.Upcoming("ana", "new job", 2, 1))
        val bday = listOf(CircleDigest.UpcomingDate("bea", 5))
        val picks = CircleDigest.pick(emptyList(), bday, now, quiet = emptyList(), yearly = yearly)
        // The sooner one takes the date place; the other fills a free place.
        assertEquals(listOf("ana" to CircleDigest.Reason.YEARLY, "bea" to CircleDigest.Reason.DATE), picks.map { it.lookupKey to it.reason })
        assertEquals("new job", picks[0].label)
        assertEquals(1, picks[0].years)
        // With a due person and a quiet pick, only one date-like line fits.
        val due = CirclePlanner.Member("due", 14, now - 30 * day)
        val full = CircleDigest.pick(listOf(due), bday, now, quiet = listOf(CircleDigest.Quiet("old", now - 400 * day)), yearly = yearly)
        assertEquals(listOf(CircleDigest.Reason.DUE, CircleDigest.Reason.YEARLY, CircleDigest.Reason.QUIET), full.map { it.reason })
    }
}
