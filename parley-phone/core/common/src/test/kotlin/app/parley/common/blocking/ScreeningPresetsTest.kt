package app.parley.common.blocking

import app.parley.common.AppSettings
import app.parley.common.CallPolicy
import app.parley.common.IncomingCallFacts
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.PolicyClock
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.TraceMark
import app.parley.common.TraceStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class ScreeningPresetsTest {
    @Test fun a_fresh_install_lets_everyone_ring() {
        assertEquals(listOf(ScreeningPreset.EVERYONE), ScreeningPreset.current(ScreeningSettings()))
    }

    @Test fun each_setup_is_named_after_it_is_applied() {
        ScreeningPreset.entries.forEach { p ->
            val s = p.apply(AppSettings()).screening
            assertTrue("$p is named after applying it", p in ScreeningPreset.current(s))
        }
        // From any starting point, not only the defaults.
        val busy = ScreeningPreset.NIGHTS.apply(ScreeningPreset.KNOWN.apply(AppSettings()))
        assertEquals(listOf(ScreeningPreset.KNOWN, ScreeningPreset.NIGHTS), ScreeningPreset.current(busy.screening))
        assertEquals(listOf(ScreeningPreset.EVERYONE), ScreeningPreset.current(ScreeningPreset.EVERYONE.apply(busy).screening))
    }

    @Test fun known_callers_covers_telemarketers_and_a_mix_has_no_name() {
        val both = ScreeningSettings(blockNonContacts = true, blockInvalid = true, blockFailedVerification = true)
        assertEquals(listOf(ScreeningPreset.KNOWN), ScreeningPreset.current(both))
        // Strangers silenced only at night is not "Only people I know".
        val scheduled = ScreeningSettings(blockNonContacts = true, nonContactsSchedule = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60))
        assertEquals(emptyList<ScreeningPreset>(), ScreeningPreset.current(scheduled))
        assertEquals(emptyList<ScreeningPreset>(), ScreeningPreset.current(ScreeningSettings(blockHidden = true)))
    }

    @Test fun only_people_i_know_says_it_removes_a_schedule_and_can_keep_it() {
        val weekdays = Schedule(Schedule.WEEKDAYS, 9 * 60, 17 * 60)
        val a = AppSettings(screening = ScreeningSettings(blockNonContacts = true, nonContactsSchedule = weekdays))
        assertEquals(weekdays, ScreeningPreset.KNOWN.removedSchedule(a.screening))
        assertEquals(null, ScreeningPreset.NIGHTS.removedSchedule(a.screening))
        assertEquals(null, ScreeningPreset.KNOWN.apply(a).screening.nonContactsSchedule)
        assertEquals(weekdays, ScreeningPreset.KNOWN.apply(a, keepSchedule = true).screening.nonContactsSchedule)
        assertEquals(null, ScreeningPreset.KNOWN.removedSchedule(ScreeningSettings()))
    }

    @Test fun one_person_from_two_numbers_counts_once() {
        val now = 100L * ScreeningWeekly.WEEK_MS
        val calls = listOf(
            StoppedCall(now - 1, "+15550199", silenced = true, fromContact = true, person = "ana"),
            StoppedCall(now - 2, "+15550198", silenced = true, fromContact = true, person = "ana"),
        )
        assertEquals(1, ScreeningWeekly.summarize(calls, now).contactsAffected)
    }

    @Test fun a_contact_stopped_by_the_system_list_counts_as_a_contact() {
        val clock = PolicyClock(1_000_000L, DayOfWeek.MONDAY, 12 * 60)
        val facts = IncomingCallFacts("+15550199", hidden = false, isContact = true, inSystemBlockList = true)
        val d = CallPolicy.decide(facts, emptyList(), ScreeningSettings(), clock)
        assertTrue(d.blocked)
        assertTrue(ScreeningWeekly.fromContact(d.trace))
    }

    @Test fun the_week_counts_silenced_declined_and_contacts_once() {
        val now = 100L * ScreeningWeekly.WEEK_MS
        val day = ScreeningWeekly.WEEK_MS / 7
        val calls = listOf(
            StoppedCall(now - day, "+15550100", silenced = true, fromContact = false),
            StoppedCall(now - 2 * day, "+15550101", silenced = true, fromContact = false),
            StoppedCall(now - 3 * day, "+15550102", silenced = false, fromContact = false),
            // The same contact twice (off hours) counts as one contact affected.
            StoppedCall(now - 4 * day, "+15550199", silenced = true, fromContact = true),
            StoppedCall(now - 5 * day, "+15550199", silenced = true, fromContact = true),
            // Older than a week, or in the future (a clock change): left out.
            StoppedCall(now - 8 * day, "+15550103", silenced = true, fromContact = true),
            StoppedCall(now + day, "+15550104", silenced = true, fromContact = false),
        )
        val w = ScreeningWeekly.summarize(calls, now)
        assertEquals(ScreeningWeek(silenced = 4, declined = 1, contactsAffected = 1), w)
        assertEquals(5, w.stopped)
        assertEquals(ScreeningWeek(0, 0, 0), ScreeningWeekly.summarize(emptyList(), now))
    }

    @Test fun a_contact_is_read_from_the_real_trace() {
        // Off hours that let only favourites ring stop a contact: the trace says it was one.
        val s = ScreeningSettings(offHours = OffHours(enabled = true, schedule = Schedule(Schedule.ALL_DAYS, 0, 24 * 60 - 1), allow = OffHoursAllow.FAVOURITES))
        val clock = PolicyClock(1_000_000L, DayOfWeek.MONDAY, 12 * 60)
        val contact = CallPolicy.decide(IncomingCallFacts("+15550199", hidden = false, isContact = true), emptyList(), s, clock)
        assertTrue(contact.blocked)
        assertTrue(ScreeningWeekly.fromContact(contact.trace))
        val stranger = CallPolicy.decide(IncomingCallFacts("+15550100", hidden = false, isContact = false), emptyList(), s.copy(blockNonContacts = true), clock)
        assertTrue(stranger.blocked)
        assertFalse(ScreeningWeekly.fromContact(stranger.trace))
        // A failed lookup let the call through as a contact; it is never counted as one stopped.
        assertFalse(ScreeningWeekly.fromContact(listOf(TraceStep(ScreeningWeekly.CONTACT_CHECK, "couldn't check", TraceMark.FAILED_OPEN))))
    }
}
