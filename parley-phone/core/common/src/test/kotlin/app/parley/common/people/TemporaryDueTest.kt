package app.parley.common.people

import app.parley.common.people.TemporaryDue.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryDueTest {
    private val day = TemporaryChoice.DAY_MS
    private val now = 100 * day

    @Test fun only_expired_contacts_are_due() {
        val due = TemporaryDue.due(mapOf("ana" to now - 1, "bob" to now, "cy" to now + 1, "kept" to null), now)
        assertEquals(setOf("ana", "bob"), due)
    }

    @Test fun asks_once_then_reminds_gently_and_never_when_nothing_is_due() {
        assertFalse(TemporaryDue.shouldNotify(emptySet(), emptySet(), 0, now))
        // Something newly due: ask.
        assertTrue(TemporaryDue.shouldNotify(setOf("ana"), emptySet(), now, now))
        // Asked today about the same contact: quiet.
        assertFalse(TemporaryDue.shouldNotify(setOf("ana"), setOf("ana"), now - day, now))
        // Unanswered for three days: one reminder.
        assertTrue(TemporaryDue.shouldNotify(setOf("ana"), setOf("ana"), now - TemporaryDue.REMIND_AFTER_DAYS * day, now))
        // Another one became due: ask again (about both).
        assertTrue(TemporaryDue.shouldNotify(setOf("ana", "bob"), setOf("ana"), now, now))
    }

    @Test fun a_decision_acts_only_on_what_was_asked_and_is_still_due() {
        // Bob became due after the question; Cy was kept on her page meanwhile.
        assertEquals(setOf("ana"), TemporaryDue.targets(asked = setOf("ana", "cy"), due = setOf("ana", "bob")))
        assertEquals(setOf("bob"), TemporaryDue.stillWaiting(setOf("ana", "bob"), setOf("ana")))
    }

    @Test fun keep_longer_moves_the_date_and_keep_clears_it() {
        assertEquals(now + 7 * day, TemporaryDue.newExpiry(Decision.KEEP_LONGER, now))
        assertNull(TemporaryDue.newExpiry(Decision.KEEP, now))
        assertNull(TemporaryDue.newExpiry(Decision.DELETE, now))
    }

    @Test fun without_asking_the_old_automatic_deletion_applies() {
        assertTrue(TemporaryDue.deletesWithoutAsking(askFirst = false))
        assertFalse(TemporaryDue.deletesWithoutAsking(askFirst = true))
    }
}
