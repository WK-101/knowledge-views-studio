package app.parley.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAndDirectoryTest {
    private val day = app.parley.common.people.LookupPolicy.ASK_INTERVAL_MS

    @Test fun prompts_at_most_once_a_day_and_never_once_decided() {
        val p = app.parley.common.people.LookupPolicy
        val pending = app.parley.common.people.LookupApproval.PENDING
        assertTrue(p.shouldAsk(null, 0, 1_000))
        assertFalse(p.shouldAsk(pending, 1_000, 1_000 + day - 1))
        assertTrue(p.shouldAsk(pending, 1_000, 1_000 + day))
        assertFalse(p.shouldAsk(app.parley.common.people.LookupApproval.DENIED, 0, 1_000))
        assertFalse(p.shouldAsk(app.parley.common.people.LookupApproval.ALLOWED, 0, 1_000))
    }

    @Test fun directory_row_ids_never_look_like_contact_ids() {
        val d = app.parley.common.people.DirectoryPolicy
        val id = d.rowId(42)
        assertTrue(id > 0 && d.isDirectoryRowId(id))
        assertFalse(d.isDirectoryRowId(42))
        assertTrue(id != d.rowId(43))
    }
}
