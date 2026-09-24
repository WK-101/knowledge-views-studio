package app.parley.common.backup

import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.RawRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RawIdTest {
    @Test fun raw_id_is_device_local_and_never_serialized() {
        val row = DataRow("vnd.android.cursor.item/name", mapOf("data1" to "Ana"))
        val withId = ContactRecord(key = "k", displayName = "Ana", raws = listOf(RawRecord("com.google", "a@b", rows = listOf(row), rawId = 42)))
        val withoutId = withId.copy(raws = listOf(withId.raws[0].copy(rawId = null)))
        val json = RecordJson.encode(withId)
        assertFalse(json.contains("rawId"))
        assertFalse(json.contains("42"))
        // Backups and content hashes don't change because of it.
        assertEquals(RecordJson.encode(withoutId), json)
        assertNull(RecordJson.decode(json) { null }.raws[0].rawId)
    }
}
