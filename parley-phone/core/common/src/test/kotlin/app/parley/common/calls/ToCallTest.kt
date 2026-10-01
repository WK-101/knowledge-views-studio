package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ToCallTest {
    private val london: ZoneId = ZoneId.of("Europe/London")
    private val karachi: ZoneId = ZoneId.of("Asia/Karachi")
    private val newYork: ZoneId = ZoneId.of("America/New_York")

    private fun t(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun hourIn(zone: ZoneId, at: Long) = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), zone).hour

    // ---- times

    @Test fun in_an_hour_is_rounded_up_to_the_minute() {
        val now = t(london, 2026, 9, 29, 10, 15) + 20_000
        assertEquals(t(london, 2026, 9, 29, 11, 16), ToCall.at(RemindTime.IN_AN_HOUR, now, london))
    }

    @Test fun this_evening_and_tomorrow_morning_are_fixed_times() {
        val now = t(london, 2026, 9, 29, 10)
        assertEquals(t(london, 2026, 9, 29, 18), ToCall.at(RemindTime.THIS_EVENING, now, london))
        assertEquals(t(london, 2026, 9, 30, 9), ToCall.at(RemindTime.TOMORROW_MORNING, now, london))
    }

    @Test fun this_evening_is_offered_only_before_it_is_close() {
        assertTrue(RemindTime.THIS_EVENING in ToCall.choices(t(london, 2026, 9, 29, 16, 59), london))
        assertFalse(RemindTime.THIS_EVENING in ToCall.choices(t(london, 2026, 9, 29, 17), london))
        assertFalse(RemindTime.THIS_EVENING in ToCall.choices(t(london, 2026, 9, 29, 22), london))
        assertEquals(listOf(RemindTime.IN_AN_HOUR, RemindTime.TOMORROW_MORNING), ToCall.choices(t(london, 2026, 9, 29, 23), london))
    }

    @Test fun this_evening_after_it_began_is_an_hour_from_now() {
        val now = t(london, 2026, 9, 29, 19)
        assertEquals(now + ToCall.HOUR_MS, ToCall.at(RemindTime.THIS_EVENING, now, london))
    }

    @Test fun tomorrow_morning_across_a_clock_change() {
        // The UK clocks go back on 25 October 2026.
        val now = t(london, 2026, 10, 24, 22)
        assertEquals(t(london, 2026, 10, 25, 9), ToCall.at(RemindTime.TOMORROW_MORNING, now, london))
    }

    @Test fun follow_up_in_days_is_that_morning() {
        assertEquals(t(london, 2026, 10, 6, 9), ToCall.inDays(7, t(london, 2026, 9, 29, 21), london))
    }

    @Test fun their_evening_waits_for_six_pm_where_they_are() {
        // 10:00 in London is 14:00 in Karachi: wait until 18:00 there (14:00 in London).
        val at = t(london, 2026, 9, 29, 10)
        val due = ToCall.theirEvening(at, karachi)
        assertEquals(18, hourIn(karachi, due))
        assertEquals(t(karachi, 2026, 9, 29, 18), due)
    }

    @Test fun their_evening_keeps_a_time_that_already_is_evening_there() {
        val at = t(karachi, 2026, 9, 29, 19, 30)
        assertEquals(at, ToCall.theirEvening(at, karachi))
    }

    @Test fun their_evening_too_late_moves_to_their_next_evening() {
        // 18:00 in London is 22:00 in Karachi: too late, the next day at 18:00 there.
        val at = t(london, 2026, 9, 29, 18)
        assertEquals(t(karachi, 2026, 9, 30, 18), ToCall.theirEvening(at, karachi))
    }

    @Test fun due_ignores_the_window_without_a_zone() {
        val at = t(london, 2026, 9, 29, 10)
        assertEquals(at, ToCall.dueAt(at, theirEvening = true, zone = null))
        assertEquals(at, ToCall.dueAt(at, theirEvening = true, zone = "Not/AZone"))
        assertEquals(at, ToCall.dueAt(at, theirEvening = false, zone = karachi.id))
        assertEquals(t(karachi, 2026, 9, 29, 18), ToCall.dueAt(at, theirEvening = true, zone = karachi.id))
    }

    @Test fun their_evening_is_offered_only_when_their_clock_differs() {
        val now = t(london, 2026, 9, 29, 10)
        assertTrue(ToCall.offersTheirEvening(newYork.id, london, now))
        assertFalse(ToCall.offersTheirEvening("Europe/Dublin", london, now))
        assertFalse(ToCall.offersTheirEvening(null, london, now))
    }

    // ---- the list

    private val now = 10_000_000_000L

    @Test fun remind_adds_one_item_per_number_and_a_newer_one_moves_it() {
        var s = ToCall.remind(ToCallState(), "k1", "+441", at = now + 100, now = now)
        s = ToCall.remind(s, "k1", "+441", at = now + 500, now = now + 50)
        assertEquals(1, s.items.size)
        assertEquals(now + 500, s.items[0].at)
        // When the call became owed stays the first time.
        assertEquals(now, s.items[0].since)
    }

    @Test fun a_follow_up_never_turns_a_reminder_into_a_follow_up() {
        var s = ToCall.remind(ToCallState(), "k1", "+441", at = now + 100, now = now)
        s = ToCall.remind(s, "k1", "+441", at = now + 900, now = now, source = ToCallSource.FOLLOW_UP)
        assertEquals(ToCallSource.REMINDER, s.items[0].source)
    }

    @Test fun missed_calls_become_rows_until_dealt_with() {
        val missed = listOf(OwedMissedCall("k2", "+442", now - 1000, null))
        val rows = ToCall.entries(ToCallState(), missed, now)
        assertEquals(ToCallKind.MISSED, rows.single().kind)
        assertTrue(rows.single().isDue(now))
        val done = ToCall.done(ToCallState(), "k2", now)
        assertTrue(ToCall.entries(done, missed, now).isEmpty())
        // A newer missed call from them asks again.
        val again = listOf(OwedMissedCall("k2", "+442", now + 10, null))
        assertEquals(1, ToCall.entries(done, again, now + 20).size)
    }

    @Test fun a_reminder_and_a_missed_call_from_the_same_number_are_one_row() {
        val s = ToCall.remind(ToCallState(), "k1", "+441", at = now + 100, now = now)
        val older = ToCall.entries(s, listOf(OwedMissedCall("k1", "+441", now - 5, null)), now)
        assertEquals(1, older.size)
        assertNull(older[0].missedAt)
        val newer = ToCall.entries(s, listOf(OwedMissedCall("k1", "+441", now + 50, null)), now + 60)
        assertEquals(1, newer.size)
        assertEquals(now + 50, newer[0].missedAt)
    }

    @Test fun due_rows_come_first_then_later_ones_by_time() {
        var s = ToCall.remind(ToCallState(), "late", "+1", at = now + 5000, now = now)
        s = ToCall.remind(s, "soon", "+2", at = now + 1000, now = now)
        s = ToCall.remind(s, "past", "+3", at = now - 10, now = now - 100)
        val rows = ToCall.entries(s, listOf(OwedMissedCall("m", "+4", now - 50, null)), now)
        assertEquals(listOf("past", "m", "soon", "late"), rows.map { it.key })
        assertEquals(ToCallCount(2, 2), ToCall.count(rows, now))
    }

    @Test fun a_later_call_settles_an_item() {
        val s = ToCall.remind(ToCallState(), "k1", "+441", at = now + 100, now = now)
        assertSame(s, ToCall.settle(s, listOf(SettlingCall("k1", now - 1), SettlingCall("k9", now + 5)), now + 10))
        val settled = ToCall.settle(s, listOf(SettlingCall("k1", now + 5)), now + 10)
        assertTrue(settled.items.isEmpty())
        assertEquals(now + 10, settled.handledMissed["k1"])
    }

    @Test fun calls_you_made_or_answered_settle_missed_and_declined_ones_dont() {
        fun call(type: app.parley.common.CallType, sec: Long = 30, hidden: Boolean = false) =
            app.parley.common.CallEntry(1, "+1", null, type, now, sec, null, false, hidden)
        assertTrue(ToCall.settles(call(app.parley.common.CallType.OUTGOING, sec = 0)))
        assertTrue(ToCall.settles(call(app.parley.common.CallType.INCOMING)))
        assertFalse(ToCall.settles(call(app.parley.common.CallType.MISSED)))
        assertFalse(ToCall.settles(call(app.parley.common.CallType.REJECTED)))
        assertFalse(ToCall.settles(call(app.parley.common.CallType.BLOCKED)))
        assertFalse(ToCall.settles(call(app.parley.common.CallType.OUTGOING, hidden = true)))
    }

    @Test fun old_handled_missed_calls_are_forgotten() {
        val s = ToCallState(handledMissed = mapOf("old" to now - ToCall.HANDLED_KEEP_MS - 1, "new" to now - 5))
        assertEquals(setOf("new"), ToCall.settle(s, emptyList(), now).handledMissed.keys)
    }

    @Test fun undo_done_brings_back_the_item_or_the_missed_call_and_keeps_other_changes() {
        val before = ToCall.remind(ToCallState(), "a", "+1", at = now, now = now)
        var s = ToCall.done(before, "a", now + 5)
        s = ToCall.remind(s, "b", "+2", at = now, now = now + 6)
        val undone = ToCall.undoDone(s, before, "a")
        assertEquals(setOf("a", "b"), undone.items.map { it.key }.toSet())
        assertEquals(now, undone.handledMissed["a"])
        val missed = listOf(OwedMissedCall("m", "+3", now - 10, null))
        val cleared = ToCall.done(ToCallState(), "m", now)
        assertEquals(1, ToCall.entries(ToCall.undoDone(cleared, ToCallState(), "m"), missed, now).size)
    }

    @Test fun snoozing_a_missed_call_makes_it_an_item() {
        val row = ToCall.entries(ToCallState(), listOf(OwedMissedCall("k2", "+442", now - 1000, "sim1")), now).single()
        val s = ToCall.snooze(ToCallState(), row, now + 3600, now)
        val item = s.items.single()
        assertEquals(now - 1000, item.since)
        assertEquals("sim1", item.accountId)
        val rows = ToCall.entries(s, listOf(OwedMissedCall("k2", "+442", now - 1000, "sim1")), now)
        assertEquals(ToCallKind.REMINDER, rows.single().kind)
        assertFalse(rows.single().isDue(now))
    }

    @Test fun one_notification_for_what_is_due_then_the_next_alarm() {
        var s = ToCall.remind(ToCallState(), "a", "+1", at = now - 10, now = now - 100)
        s = ToCall.remind(s, "b", "+2", at = now + 30_000, now = now - 100)
        s = ToCall.remind(s, "c", "+3", at = now + 7_200_000, now = now - 100)
        // Within a minute counts as due (WorkManager isn't exact).
        assertEquals(setOf("a", "b"), ToCall.toNotify(s, now).map { it.key }.toSet())
        s = ToCall.markNotified(s, setOf("a", "b"))
        assertTrue(ToCall.toNotify(s, now).isEmpty())
        assertEquals(now + 7_200_000, ToCall.nextAlarm(s, now))
        assertNull(ToCall.nextAlarm(ToCall.markNotified(s, setOf("c")), now))
    }

    @Test fun next_alarm_is_never_in_the_past() {
        val s = ToCall.remind(ToCallState(), "a", "+1", at = now - 10_000, now = now - 20_000)
        assertEquals(now, ToCall.nextAlarm(s, now))
    }

    @Test fun not_now_brings_the_shown_items_back_in_an_hour_once() {
        var s = ToCall.remind(ToCallState(), "a", "+1", at = now, now = now)
        s = ToCall.markNotified(s, setOf("a"))
        s = ToCall.notNow(s, setOf("a"), now)
        assertEquals(now + ToCall.NOT_NOW_MS, s.items[0].dueAt)
        assertFalse(s.items[0].notified)
    }

    @Test fun their_evening_moves_the_due_time_and_turning_it_on_for_a_missed_call_adds_an_item() {
        val at = t(london, 2026, 9, 29, 10)
        val row = ToCallEntry("k", "+92300", ToCallKind.MISSED, at - 1000, null, zone = karachi.id)
        val s = ToCall.setTheirEvening(ToCallState(), row, on = true, now = at)
        assertEquals(t(karachi, 2026, 9, 29, 18), s.items.single().dueAt)
        val off = ToCall.setTheirEvening(s, ToCall.entries(s, emptyList(), at).single(), on = false, now = at)
        assertEquals(at, off.items.single().dueAt)
    }

    @Test fun the_list_is_capped() {
        var s = ToCallState()
        repeat(ToCall.MAX_ITEMS + 5) { i -> s = ToCall.remind(s, "k$i", "+$i", at = now, now = now + i) }
        assertEquals(ToCall.MAX_ITEMS, s.items.size)
        assertFalse(s.items.any { it.key == "k0" })
    }

    @Test fun blank_numbers_are_ignored() {
        assertEquals(ToCallState(), ToCall.remind(ToCallState(), "", "+1", now, now))
        assertTrue(ToCall.remind(ToCallState(), "k", " ", now, now).items.isEmpty())
    }

    // ---- storage and backup

    @Test fun encodes_and_decodes() {
        var s = ToCall.remind(ToCallState(), "k1", "+441", at = now + 100, now = now, theirEvening = true, zone = karachi.id, accountId = "sim")
        s = ToCall.done(s, "k2", now)
        assertEquals(s, ToCall.decode(ToCall.encode(s)))
        assertEquals(s.items[0].dueAt, ToCall.decode(ToCall.encode(s)).items[0].dueAt)
        assertEquals(ToCallState(), ToCall.decode("not json"))
        assertEquals(ToCallState(), ToCall.decode(null))
    }

    @Test fun backup_leaves_out_private_numbers_and_merge_keeps_this_phones_items() {
        var s = ToCall.remind(ToCallState(), "p", "+1private", at = now, now = now)
        s = ToCall.remind(s, "v", "+2visible", at = now, now = now)
        val out = ToCall.without(s) { it.contains("private") }
        assertEquals(listOf("v"), out.items.map { it.key })
        assertFalse("p" in out.handledMissed)
        val here = ToCall.remind(ToCallState(), "v", "+2visible", at = now + 9, now = now)
        val merged = ToCall.merge(here, s)
        assertEquals(now + 9, merged.items.first { it.key == "v" }.at)
        assertTrue(merged.items.any { it.key == "p" })
    }
}
