package app.parley.common.calls

import app.parley.common.AllowReason
import app.parley.common.CallPolicy
import app.parley.common.IncomingCallFacts
import app.parley.common.PolicyClock
import app.parley.common.RangThrough
import app.parley.common.RangThroughKind
import app.parley.common.ScreeningSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class ExpectedCallsTest {
    private val zone: ZoneId = ZoneOffset.UTC

    // Thursday 1 October 2026, 10:00.
    private val today: LocalDate = LocalDate.of(2026, 10, 1)
    private val now = at(today, 10, 0)

    private fun at(d: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(d, LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private fun window(note: String) = ExpectedCalls.fromNote(note, now, zone)

    @Test fun a_weekday_gives_that_day_in_daytime_hours() {
        val tue = LocalDate.of(2026, 10, 6)
        assertEquals(at(tue, 8) to at(tue, 20), window("Dentist will call Tue"))
        assertEquals(at(tue, 8) to at(tue, 20), window("the dentist rings on tuesday"))
    }

    @Test fun the_same_weekday_is_today_unless_it_says_next() {
        assertEquals(at(today, 8) to at(today, 20), window("Plumber will call Thursday"))
        val nextThu = today.plusDays(7)
        assertEquals(at(nextThu, 8) to at(nextThu, 20), window("Plumber will call next Thursday"))
    }

    @Test fun tomorrow_today_and_in_n_days() {
        val tomorrow = today.plusDays(1)
        assertEquals(at(tomorrow, 8) to at(tomorrow, 20), window("School will phone tomorrow"))
        assertEquals(at(today, 8) to at(today, 20), window("Bank calls back today"))
        val inThree = today.plusDays(3)
        assertEquals(at(inThree, 8) to at(inThree, 20), window("They'll call in 3 days"))
    }

    @Test fun a_time_narrows_the_window() {
        val tomorrow = today.plusDays(1)
        assertEquals(at(tomorrow, 14, 30) to at(tomorrow, 17), window("GP will call tomorrow at 3pm"))
        assertEquals(at(tomorrow, 9, 0) to at(tomorrow, 11, 30), window("callback tomorrow 9:30"))
        // "at 4" without am or pm is the afternoon.
        assertEquals(at(tomorrow, 15, 30) to at(tomorrow, 18), window("Sam will ring tomorrow at 4"))
        assertEquals(at(tomorrow, 8) to at(tomorrow, 12), window("vet calls tomorrow morning"))
    }

    @Test fun month_dates_and_iso_dates() {
        val d = LocalDate.of(2026, 10, 12)
        assertEquals(at(d, 8) to at(d, 20), window("Insurer will call on 12 Oct"))
        assertEquals(at(d, 8) to at(d, 20), window("Insurer will call October 12th"))
        assertEquals(at(d, 8) to at(d, 20), window("call expected 2026-10-12"))
    }

    @Test fun no_call_word_no_date_or_a_ticked_promise_gives_nothing() {
        assertNull(window("Lunch with Ana on Tuesday"))
        assertNull(window("Call Ana sometime"))
        assertNull(window("[x] dentist will call Tue"))
        assertNull(window(""))
        // Lower-case three-letter words aren't weekdays ("sat", "sun").
        assertNull(window("we sat in the sun and talked about a call"))
    }

    @Test fun past_dates_and_far_dates_give_nothing() {
        assertNull(window("Bank will call on 2026-09-20"))
        assertNull(window("Bank will call on 2027-06-01"))
        // A time that has already passed today.
        assertNull(window("GP will call today at 7am"))
    }

    @Test fun an_open_promise_counts_and_the_earliest_line_wins() {
        val tue = LocalDate.of(2026, 10, 6)
        val tomorrow = today.plusDays(1)
        assertEquals(at(tomorrow, 8) to at(tomorrow, 20), window("[ ] Garage will call Tue\n- [ ] courier rings tomorrow"))
        assertEquals(at(tue, 8) to at(tue, 20), window("[x] courier rings tomorrow\n[ ] Garage will call Tue"))
    }

    @Test fun to_call_items_ring_until_a_day_after_they_are_due_for_at_most_a_week() {
        val due = now + 2 * 3_600_000L
        assertEquals(now to due + 86_400_000L, ExpectedCalls.forToCall(due, now))
        assertEquals(now to now + 7 * 86_400_000L, ExpectedCalls.forToCall(now + 30 * 86_400_000L, now))
    }

    @Test fun delivery_codes() {
        assertTrue(ExpectedCalls.isDelivery("https://www.dhl.com/track?id=123"))
        assertTrue(ExpectedCalls.isDelivery("Your parcel 1Z999 is out for delivery"))
        assertTrue(ExpectedCalls.isDelivery("https://evri.com/x/abc"))
        assertFalse(ExpectedCalls.isDelivery("https://example.com/groups"))
        assertFalse(ExpectedCalls.isDelivery("WIFI:S:home;T:WPA;P:secret;;"))
        // Until 8 pm, or tomorrow's daytime when scanned late.
        assertEquals(now to at(today, 20), ExpectedCalls.forDelivery(now, zone))
        val late = at(today, 19)
        assertEquals(late to late + 2 * 3_600_000L, ExpectedCalls.forDelivery(late, zone))
        val tomorrow = today.plusDays(1)
        assertEquals(at(tomorrow, 8) to at(tomorrow, 20), ExpectedCalls.forDelivery(at(today, 22), zone))
    }

    @Test fun a_number_window_wins_and_only_matches_its_number() {
        val all = ExpectedWindow(now - 1, now + 100, ExpectedSource.DELIVERY_QR, "qr")
        val ana = ExpectedWindow(now - 1, now + 100, ExpectedSource.TO_CALL, "+447700900123", number = "+447700900123")
        assertEquals(ana, ExpectedCalls.covering(listOf(all, ana), "07700 900123", "GB", now))
        assertEquals(all, ExpectedCalls.covering(listOf(all, ana), "07700 900999", "GB", now))
        assertNull(ExpectedCalls.covering(listOf(ana), "07700 900999", "GB", now))
        assertNull(ExpectedCalls.covering(listOf(ana), null, "GB", now))
        assertNull(ExpectedCalls.covering(listOf(all), "07700 900999", "GB", now + 100))
    }

    @Test fun put_replaces_the_same_source_and_drops_ended_ones() {
        val a = ExpectedWindow(now, now + 10, ExpectedSource.NOTE, "k1", "Dentist")
        val old = ExpectedWindow(now - 20, now - 10, ExpectedSource.NOTE, "k2")
        val b = a.copy(start = now + 5, end = now + 50)
        assertEquals(listOf(b), ExpectedCalls.put(listOf(a, old), b, now))
        assertEquals(emptyList<ExpectedWindow>(), ExpectedCalls.without(listOf(a), ExpectedSource.NOTE))
    }

    @Test fun the_policy_lets_an_unknown_caller_ring_in_a_window_and_says_why() {
        val strict = ScreeningSettings(blockNonContacts = true)
        val facts = IncomingCallFacts(number = "+33699999999", hidden = false, isContact = false, countryIso = "FR")
        val clock = PolicyClock.of(now, zone)
        assertTrue(CallPolicy.decide(facts, emptyList(), strict, clock).blocked)
        val note = ExpectedWindow(now - 1, now + 3_600_000, ExpectedSource.NOTE, "n", label = "Dentist")
        val r = CallPolicy.decide(facts, emptyList(), strict.copy(expected = listOf(note)), clock)
        assertEquals(AllowReason.SNOOZE, r.allowedBy)
        assertEquals(RangThrough(RangThroughKind.EXPECTING, name = "Dentist", expected = ExpectedSource.NOTE), r.rangThrough)
        // Turned on by hand as well: the plain reason.
        val both = CallPolicy.decide(facts, emptyList(), strict.copy(expected = listOf(note), snoozeUntil = now + 60_000), clock)
        assertEquals(RangThrough(RangThroughKind.EXPECTING), both.rangThrough)
        // A window that ended, or one for another number, changes nothing.
        val ended = note.copy(end = now)
        assertTrue(CallPolicy.decide(facts, emptyList(), strict.copy(expected = listOf(ended)), clock).blocked)
        val other = note.copy(number = "+33611111111")
        assertTrue(CallPolicy.decide(facts, emptyList(), strict.copy(expected = listOf(other)), clock).blocked)
        // Hidden numbers ring only in a window for everyone.
        val hidden = IncomingCallFacts(number = null, hidden = true, isContact = false)
        val r2 = CallPolicy.decide(hidden, emptyList(), ScreeningSettings(blockHidden = true, expected = listOf(note)), clock)
        assertNotNull(r2.rangThrough)
        assertEquals(ExpectedSource.NOTE, r2.rangThrough?.expected)
    }

    @Test fun only_a_promise_of_an_incoming_call_counts() {
        val tomorrow = today.plusDays(1)
        val day = at(tomorrow, 8) to at(tomorrow, 20)
        // A call to make, or no call at all.
        assertNull(window("Ring the plumber tomorrow"))
        assertNull(window("Phone bill due tomorrow"))
        assertNull(window("Call Ana tomorrow"))
        assertNull(window("Call back the bank tomorrow"))
        assertNull(window("I'll call the bank tomorrow"))
        assertNull(window("We will ring Gran tomorrow"))
        assertNull(window("Let's phone Sam tomorrow"))
        assertNull(window("I'm going to call the garage tomorrow"))
        assertNull(window("Ana's phone broke, new one tomorrow"))
        // Someone else will call.
        assertEquals(day, window("Garage is calling tomorrow"))
        assertEquals(day, window("They're going to ring tomorrow"))
        assertEquals(day, window("She'll be calling tomorrow"))
        assertEquals(day, window("Asked them to call me back tomorrow"))
        assertEquals(day, window("Expecting a call from the bank tomorrow"))
        assertEquals(day, window("Courier tomorrow"))
        assertEquals(day, window("Delivery tomorrow"))
    }

    @Test fun promise_words_on_their_own() {
        assertTrue(ExpectedCalls.promisesCall("Dentist will call"))
        assertTrue(ExpectedCalls.promisesCall("callback requested"))
        assertTrue(ExpectedCalls.promisesCall("Bank calls back"))
        assertFalse(ExpectedCalls.promisesCall("ring"))
        assertFalse(ExpectedCalls.promisesCall("phone"))
        assertFalse(ExpectedCalls.promisesCall("ring the plumber"))
        assertFalse(ExpectedCalls.promisesCall("you will call the school"))
    }

    @Test fun note_windows_from_older_versions_are_untracked() {
        assertTrue(ExpectedCalls.untracked(ExpectedWindow(1, 2, ExpectedSource.NOTE, "note:0r12-ABC")))
        assertTrue(ExpectedCalls.untracked(ExpectedWindow(1, 2, ExpectedSource.NOTE, "call:+447700900123")))
        assertFalse(ExpectedCalls.untracked(ExpectedWindow(1, 2, ExpectedSource.NOTE, "note:i42")))
        assertFalse(ExpectedCalls.untracked(ExpectedWindow(1, 2, ExpectedSource.NOTE, "call:n7")))
        assertFalse(ExpectedCalls.untracked(ExpectedWindow(1, 2, ExpectedSource.DELIVERY_QR, "delivery")))
    }

    @Test fun a_private_name_is_hidden_in_discreet_mode() {
        val w = ExpectedWindow(1, 2, ExpectedSource.NOTE, "note:i1", label = "Ana", privateName = true)
        assertEquals("Ana", w.shownLabel(discreet = false))
        assertNull(w.shownLabel(discreet = true))
        assertEquals("Dentist", w.copy(label = "Dentist", privateName = false).shownLabel(discreet = true))
    }

    @Test fun windows_are_never_stored_with_the_settings() {
        val s = ScreeningSettings(expected = listOf(ExpectedWindow(1, 2, ExpectedSource.NOTE, "n", "Dentist")))
        assertFalse("Dentist" in s.encode())
    }
}
