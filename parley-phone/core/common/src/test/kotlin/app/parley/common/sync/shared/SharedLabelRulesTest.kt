package app.parley.common.sync.shared

import app.parley.common.sync.shared.SharedLabelRules.Action
import app.parley.common.sync.shared.SharedLabelRules.Local
import app.parley.common.sync.shared.SharedLabelRules.Remote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLabelRulesTest {
    @Test fun the_decision_table() {
        val expected = mapOf(
            (Local.UNCHANGED to Remote.UNCHANGED) to Action.NONE,
            (Local.CHANGED to Remote.UNCHANGED) to Action.PUBLISH,
            (Local.GONE to Remote.UNCHANGED) to Action.PUBLISH_TOMBSTONE,
            (Local.UNCHANGED to Remote.CHANGED) to Action.APPLY,
            (Local.CHANGED to Remote.CHANGED) to Action.MERGE,
            (Local.GONE to Remote.CHANGED) to Action.IMPORT_AGAIN,
            (Local.UNCHANGED to Remote.TOMBSTONE) to Action.DELETE_LOCAL,
            (Local.CHANGED to Remote.TOMBSTONE) to Action.PUBLISH,
            (Local.GONE to Remote.TOMBSTONE) to Action.FORGET,
            (Local.UNCHANGED to Remote.MISSING) to Action.PUBLISH,
            (Local.CHANGED to Remote.MISSING) to Action.PUBLISH,
            (Local.GONE to Remote.MISSING) to Action.PUBLISH_TOMBSTONE,
        )
        expected.forEach { (k, v) -> assertEquals("$k", v, SharedLabelRules.decide(k.first, k.second)) }
        Local.entries.forEach { assertEquals(Action.NONE, SharedLabelRules.decide(it, Remote.UNREADABLE)) }
    }

    @Test fun a_vanished_file_is_never_a_deletion() {
        assertEquals(Remote.MISSING, SharedLabelRules.remote(null, deleted = false, readable = false, lastVersion = 5, seenVersion = 5))
        assertTrue(Local.entries.none { SharedLabelRules.decide(it, Remote.MISSING) == Action.DELETE_LOCAL })
    }

    @Test fun old_copies_put_back_are_ignored() {
        // Older than what this phone synced or saw: treated as missing, so this phone writes its version over it.
        assertEquals(Remote.MISSING, SharedLabelRules.remote(3, deleted = false, readable = true, lastVersion = 5, seenVersion = 5))
        assertEquals(Remote.MISSING, SharedLabelRules.remote(6, deleted = true, readable = true, lastVersion = 5, seenVersion = 7))
        assertEquals(Remote.UNCHANGED, SharedLabelRules.remote(5, deleted = false, readable = true, lastVersion = 5, seenVersion = 5))
        assertEquals(Remote.CHANGED, SharedLabelRules.remote(8, deleted = false, readable = true, lastVersion = 5, seenVersion = 5))
        assertEquals(Remote.TOMBSTONE, SharedLabelRules.remote(8, deleted = true, readable = true, lastVersion = 5, seenVersion = 5))
        assertEquals(Remote.UNREADABLE, SharedLabelRules.remote(8, deleted = false, readable = false, lastVersion = 5, seenVersion = 5))
    }

    @Test fun new_contacts_and_resurrections() {
        assertTrue(SharedLabelRules.isNewContact(10, deleted = false, seenVersion = null))
        assertFalse(SharedLabelRules.isNewContact(10, deleted = true, seenVersion = null))
        // A deleted contact's file put back (no newer than its deletion) stays deleted.
        assertFalse(SharedLabelRules.isNewContact(10, deleted = false, seenVersion = 12))
        assertTrue(SharedLabelRules.isNewContact(13, deleted = false, seenVersion = 12))
    }

    @Test fun versions_grow_and_mass_deletions_wait() {
        assertEquals(1000, SharedLabelRules.nextVersion(5, 1000))
        assertEquals(1001, SharedLabelRules.nextVersion(1000, 900))
        assertTrue(SharedLabelRules.mustConfirm(5, 10))
        assertFalse(SharedLabelRules.mustConfirm(3, 4))
        assertFalse(SharedLabelRules.mustConfirm(5, 40))
    }

    @Test fun an_unreadable_file_is_written_again_after_a_grace_period() {
        // A damaged file waits an hour (it may still be arriving), one signed by a stranger a day (their journal may).
        val t = 1_000_000L
        assertEquals(Remote.UNREADABLE, SharedLabelRules.unreadable(t, t + SharedLabelRules.CORRUPT_GRACE_MS - 1, stranger = false))
        assertEquals(Remote.MISSING, SharedLabelRules.unreadable(t, t + SharedLabelRules.CORRUPT_GRACE_MS, stranger = false))
        assertEquals(Remote.UNREADABLE, SharedLabelRules.unreadable(t, t + SharedLabelRules.CORRUPT_GRACE_MS, stranger = true))
        assertEquals(Remote.MISSING, SharedLabelRules.unreadable(t, t + SharedLabelRules.STRANGER_GRACE_MS, stranger = true))
        // Then it is missing: this phone's copy goes back, or its deletion.
        assertEquals(Action.PUBLISH, SharedLabelRules.decide(Local.UNCHANGED, Remote.MISSING))
        assertEquals(Action.PUBLISH_TOMBSTONE, SharedLabelRules.decide(Local.GONE, Remote.MISSING))
    }

    @Test fun versions_far_in_the_future_or_at_the_top_never_freeze_a_contact() {
        // A version stamp far in the future is no version.
        val now = 1_700_000_000_000L
        assertTrue(SharedLabelRules.plausibleVersion(now + 1000, now))
        assertFalse(SharedLabelRules.plausibleVersion(Long.MAX_VALUE, now))
        assertEquals(Long.MAX_VALUE, SharedLabelRules.nextVersion(Long.MAX_VALUE, now))
        assertTrue(SharedLabelRules.nextVersion(Long.MAX_VALUE - 1, now) > 0)
    }

    @Test fun an_edit_made_alongside_this_phones_copy_merges_whichever_is_newer() {
        // This phone took 10 from someone, then wrote 20 itself; a member's edit from 10 didn't see 20.
        val prior = listOf(10L)
        assertTrue(SharedLabelRules.concurrent(30, 10, 20, prior))
        assertTrue(SharedLabelRules.concurrent(15, 10, 20, prior))
        assertEquals(Remote.CONCURRENT, SharedLabelRules.remote(15, false, true, 20, 20, parent = 10, prior = prior))
        assertEquals(Remote.CONCURRENT, SharedLabelRules.remote(30, false, true, 20, 20, parent = 10, prior = prior))
        assertEquals(Action.MERGE, SharedLabelRules.decide(Local.UNCHANGED, Remote.CONCURRENT))
        assertEquals(Action.MERGE, SharedLabelRules.decide(Local.CHANGED, Remote.CONCURRENT))
        assertEquals(Action.IMPORT_AGAIN, SharedLabelRules.decide(Local.GONE, Remote.CONCURRENT))
        // A deletion made alongside an edit loses to it: this phone's copy is written again.
        assertEquals(Remote.MISSING, SharedLabelRules.remote(30, true, true, 20, 20, parent = 10, prior = prior))
        // Written from this phone's synced version: an ordinary change. Unknown parent or none: as before.
        assertEquals(Remote.CHANGED, SharedLabelRules.remote(30, false, true, 20, 20, parent = 20, prior = prior))
        assertEquals(Remote.CHANGED, SharedLabelRules.remote(30, false, true, 20, 20, parent = 5, prior = prior))
        assertEquals(Remote.CHANGED, SharedLabelRules.remote(30, false, true, 20, 20, parent = null, prior = prior))
        // A version this phone held itself, put back: never an edit alongside.
        assertFalse(SharedLabelRules.concurrent(10, 5, 20, listOf(10L, 5L)))
        assertEquals(Remote.MISSING, SharedLabelRules.remote(15, false, true, 20, 20, parent = 5, prior = prior))
        assertEquals(Remote.UNCHANGED, SharedLabelRules.remote(20, false, true, 20, 20, parent = 10, prior = prior))
    }
}
