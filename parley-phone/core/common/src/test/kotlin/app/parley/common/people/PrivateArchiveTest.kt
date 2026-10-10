package app.parley.common.people

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Archived private contacts show (Contacts › ⋮ › Archived, Recall) only while private contacts may. */
class PrivateArchiveTest {
    @Test fun shown_only_while_private_contacts_may_show() {
        assertTrue(PrivateArchive.mayShow(hidden = false, hiding = false, locked = false))
        assertFalse("discreet mode", PrivateArchive.mayShow(hidden = true, hiding = false, locked = false))
        assertFalse("a duress unlock", PrivateArchive.mayShow(hidden = false, hiding = true, locked = false))
        assertFalse("Lock private contacts", PrivateArchive.mayShow(hidden = false, hiding = false, locked = true))
    }
}
