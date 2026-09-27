package app.parley.common

import app.parley.common.vcard.ImportReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredTextTest {
    @Test fun status_round_trips_and_old_text_stays_text() {
        val s = StoredStatus.of("report", 1, 2, 3)
        assertEquals(s, StoredStatus.decode(s.encode()))
        assertEquals(3, StoredStatus.decode(s.encode())!!.int(2))
        assertEquals(null, StoredStatus.decode("Backed up 12 contacts"))
        assertEquals(null, StoredStatus.decode(null))
    }

    @Test fun csv_columns_are_keys_not_text() {
        val k = ImportReport.csvColumn("Shoe size")
        assertEquals("Shoe size", ImportReport.csvColumnName(k))
        assertEquals(null, ImportReport.csvColumnName("X-SHOE"))
    }

    @Test fun vault_label_is_a_marker() {
        assertTrue(NotificationPrivacy.isVaultLabel(NotificationPrivacy.VAULT_LABEL))
        assertFalse(NotificationPrivacy.isVaultLabel("Mobile"))
        assertEquals(null, NotificationPrivacy.shownLabel(NotificationPrivacy.VAULT_LABEL))
    }
}
