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
            // Google's custom fields are edited now (CustomFields), so they aren't "other" fields.
            listOf("File as" to "Smith, Anna", "Pet name" to "Rex"),
            fields.map { it.label to it.value },
        )
    }

    @Test fun a_work_rows_office_and_job_description_show_but_its_edited_parts_dont() {
        val org = DataRow(Mime.ORG, mapOf("data1" to "Acme", "data4" to "Engineer", "data5" to "Research", "data6" to "Builds rockets", "data9" to "Room 4"))
        assertEquals(
            listOf("Office" to "Room 4", "Job description" to "Builds rockets"),
            OtherFields.describe(listOf(org)).map { it.label to it.value },
        )
        assertEquals(emptyList<OtherFields.Field>(), OtherFields.describe(listOf(DataRow(Mime.ORG, mapOf("data1" to "Acme")))))
    }

    @Test fun a_private_contacts_office_and_job_description_show_from_its_details() {
        assertEquals(
            listOf("Office" to "Room 4", "Job description" to "Builds rockets"),
            OtherFields.workExtras(" Room 4 ", "Builds rockets").map { it.label to it.value },
        )
        assertEquals(listOf("Job description"), OtherFields.workExtras("", "Builds rockets").map { it.label })
        assertEquals(emptyList<OtherFields.Field>(), OtherFields.workExtras(null, " "))
    }
}
