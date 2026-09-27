package app.parley.common.people

import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Test

class OtherFieldsTest {
    @Test fun other_fields_show_unknown_kinds_and_skip_known_ones() {
        fun r(mime: String, vararg v: Pair<String, String?>) = DataRow(mime, v.toMap())
        val rows = listOf(
            r(Mime.PHONE, "data1" to "123"),
            r(Mime.IM, "data1" to "x@y"),
            r(OtherFields.GOOGLE_FILE_AS, "data1" to "Smith, Anna"),
            r(OtherFields.GOOGLE_USER_FIELD, "data1" to "Shoe size", "data2" to "38"),
            r("vnd.android.cursor.item/vnd.example.pet_name", "data1" to "", "data2" to "Rex"),
            r("vnd.android.cursor.item/vnd.com.whatsapp.profile", "data1" to "123@s.whatsapp.net"),
            r("vnd.android.cursor.item/vnd.example.empty"),
        )
        val fields = OtherFields.describe(rows) { it.mimeType.contains("whatsapp") }
        assertEquals(
            listOf("File as" to "Smith, Anna", "Shoe size" to "38", "Pet name" to "Rex"),
            fields.map { it.label to it.value },
        )
    }
}
