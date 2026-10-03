package app.parley.common.sync

import app.parley.common.PhoneIdentity
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the folder sync pairs a new file with a contact that has no file yet. */
class FolderSyncKeysTest {
    private fun contact(name: String, vararg rows: DataRow) =
        ContactRecord(key = "k", displayName = name, raws = listOf(RawRecord(null, null, rows = rows.toList())))

    @Test fun phonesAndEmailsAreTheMatchKeys() {
        val c = contact(
            "Ana",
            DataRow(Mime.PHONE, mapOf("data1" to "+351 912 345 678")),
            DataRow(Mime.EMAIL, mapOf("data1" to " Ana@Example.com ")),
            DataRow(Mime.NOTE, mapOf("data1" to "not a key")),
        )
        assertEquals(setOf("p:" + PhoneIdentity.portableKey("+351 912 345 678"), "e:ana@example.com"), FolderSyncRules.matchKeys(c))
        assertTrue(FolderSyncRules.matchKeys(contact("Bo", DataRow(Mime.PHONE, mapOf("data1" to "12")))).isEmpty())
    }

    @Test fun theNameKeyIgnoresCaseAndSpacing() {
        assertEquals("ana  silva".replace("  ", " "), FolderSyncRules.nameKey(contact("  Ana \t SILVA ")))
        assertNull(FolderSyncRules.nameKey(contact("   ")))
    }
}
