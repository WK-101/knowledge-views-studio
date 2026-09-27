package app.parley.common.sync

import app.parley.common.sync.FolderSyncRules.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSyncRulesTest {
    private fun a(fileChanged: Boolean = false, fileGone: Boolean = false, localChanged: Boolean = false, localGone: Boolean = false) =
        FolderSyncRules.action(fileChanged, fileGone, localChanged, localGone)

    @Test fun each_side_alone_flows_to_the_other() {
        assertEquals(Action.NONE, a())
        assertEquals(Action.WRITE_FILE, a(localChanged = true))
        assertEquals(Action.DELETE_FILE, a(localGone = true))
        assertEquals(Action.APPLY_FILE, a(fileChanged = true))
        assertEquals(Action.DELETE_LOCAL, a(fileGone = true))
    }

    @Test fun both_sides_changed_is_a_conflict_and_delete_versus_edit_keeps_the_survivor() {
        assertEquals(Action.CONFLICT, a(fileChanged = true, localChanged = true))
        assertEquals(Action.FORGET, a(fileGone = true, localChanged = true))
        assertEquals(Action.FORGET, a(fileChanged = true, localGone = true))
    }

    @Test fun many_deletions_or_a_recently_edited_one_need_confirmation() {
        assertFalse(FolderSyncRules.mustConfirm(deletions = 3, entries = 4, recentlyEditedDeletions = 0))
        assertFalse(FolderSyncRules.mustConfirm(deletions = 4, entries = 100, recentlyEditedDeletions = 0))
        assertTrue(FolderSyncRules.mustConfirm(deletions = 4, entries = 10, recentlyEditedDeletions = 0))
        assertTrue(FolderSyncRules.mustConfirm(deletions = 1, entries = 1000, recentlyEditedDeletions = 1))
    }

    @Test fun a_stamp_needs_both_time_and_size() {
        assertEquals("1700000000000:512", FolderSyncRules.stamp(1_700_000_000_000L, 512L))
        assertNull(FolderSyncRules.stamp(null, 512L))
        assertNull(FolderSyncRules.stamp(0L, 512L))
        assertNull(FolderSyncRules.stamp(1L, null))
    }
}
