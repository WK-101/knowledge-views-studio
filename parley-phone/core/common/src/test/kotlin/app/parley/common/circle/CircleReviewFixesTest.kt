package app.parley.common.circle

import app.parley.common.EventDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** v3.2 Circle review fixes: carried interactions, event keys, fresh promise ticks, short names, digest. */
class CircleReviewFixesTest {
    private val day = NaturalRhythm.DAY

    // R2: interactions carried in the vault entry

    @Test fun carried_interactions_round_trip() {
        val list = listOf(
            CarriedInteraction(InteractionType.MEET.name, null, 1_000L, "[ ] send the book\nlunch", "m:1"),
            CarriedInteraction(InteractionType.MESSAGE.name, InteractionChannel.WHATSAPP.name, 2_000L, null, "w:7:3-0101:2026"),
        )
        assertEquals(list, Interactions.decodeCarried(Interactions.encodeCarried(list)))
    }

    @Test fun no_carried_interactions_store_nothing_and_garbage_reads_as_none() {
        assertNull(Interactions.encodeCarried(emptyList()))
        assertEquals(emptyList<CarriedInteraction>(), Interactions.decodeCarried(null))
        assertEquals(emptyList<CarriedInteraction>(), Interactions.decodeCarried("not json"))
    }

    // G5: two custom events on one day are separate occasions

    @Test fun event_key_includes_the_label() {
        val d = EventDate(null, 3, 14)
        assertNotEquals(DateReminders.eventKey(0, d, "New job"), DateReminders.eventKey(0, d, "Moved"))
        assertEquals(DateReminders.eventKey(0, d, "New job"), DateReminders.eventKey(0, d, "  new JOB "))
        // Without a label the key is the one it always was (fired and wished state stays valid).
        assertEquals("3-0314", DateReminders.eventKey(3, d))
        assertEquals(DateReminders.eventKey(3, d), DateReminders.eventKey(3, d, " "))
        assertNotEquals(DateReminders.tag(7, DateReminders.eventKey(0, d, "a")), DateReminders.tag(7, DateReminders.eventKey(0, d, "b")))
    }

    // R9: ticking a promise uses the note as it is now

    @Test fun promise_tick_applies_to_the_current_note() {
        val shown = "[ ] call the bank\n[ ] send photos"
        // Edited since: a line was added above and another promise changed.
        val current = "Lunch on Friday\n[ ] call the bank\n[ ] send photos and video"
        assertEquals(1, Promises.lineIn(current, shown, 0))
        assertEquals("Lunch on Friday\n[x] call the bank\n[ ] send photos and video", Promises.setDoneFresh(current, shown, 0, true))
        // The promise on line 1 no longer exists as shown: nothing changes.
        assertNull(Promises.lineIn(current, shown, 1))
        assertEquals(current, Promises.setDoneFresh(current, shown, 1, true))
        // Unchanged note: same as setDone.
        assertEquals(Promises.setDone(shown, 1, true), Promises.setDoneFresh(shown, shown, 1, true))
    }

    // R6: names are never split on spaces

    @Test fun short_name_uses_the_given_name_or_the_whole_name() {
        assertEquals("Ana", PeopleInsights.shortName("Ana", "Ana María López"))
        assertEquals("李小龙", PeopleInsights.shortName(null, "李小龙"))
        assertEquals("Nguyen Van An", PeopleInsights.shortName("  ", "Nguyen Van An"))
    }

    // R10/X6: a digest without anyone in the Circle

    @Test fun digest_with_an_empty_circle_still_has_yearly_and_serendipity_picks() {
        val today = LocalDate.of(2026, 9, 27)
        val now = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val yearly = listOfNotNull(
            YearlyEvents.upcoming("ana", "New job", EventDate(2025, 9, 29), today, CircleDigest.DATE_WINDOW_DAYS),
            YearlyEvents.upcoming("ben", "Moved", EventDate(2024, 10, 1), today, CircleDigest.DATE_WINDOW_DAYS),
            YearlyEvents.upcoming("cy", "Graduated", EventDate(2023, 10, 2), today, CircleDigest.DATE_WINDOW_DAYS),
        )
        val quiet = listOf(CircleDigest.Quiet("dee", now - 400 * day))
        val picks = CircleDigest.pick(emptyList(), emptyList(), now, null, quiet, yearly)
        assertEquals(CircleDigest.MAX_PEOPLE, picks.size)
        assertEquals(CircleDigest.Pick("ana", CircleDigest.Reason.YEARLY, "New job", 1), picks[0])
        assertTrue(picks.any { it.reason == CircleDigest.Reason.QUIET && it.lookupKey == "dee" })
        assertTrue(picks.none { it.reason == CircleDigest.Reason.DUE })
    }
}
