package app.parley.common

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateIndexTest {
    private fun rec(name: String, phone: String? = null, email: String? = null) = ContactRecord(
        "", name,
        raws = listOf(
            RawRecord(
                null, null,
                rows = listOfNotNull(
                    phone?.let { DataRow(Mime.PHONE, mapOf(Col.D1 to it)) },
                    email?.let { DataRow(Mime.EMAIL, mapOf(Col.D1 to it)) },
                ),
            ),
        ),
    )

    private val index = DuplicateIndex().apply {
        add(ContactSummary(1, "k1", "Ann Lee", null, false, listOf(PhoneEntry("+33 6 12 34 56 78", 2, null)), listOf("Ann@Example.com")))
        add(ContactSummary(2, "k2", "John Smith", null, false, emptyList()))
    }

    @Test fun same_number_in_another_format_matches() = assertTrue(index.matches(rec("Annie", phone = "06 12 34 56 78")))

    @Test fun same_email_ignoring_case_matches() = assertTrue(index.matches(rec("A. Lee", email = "ann@example.com ")))

    @Test fun same_name_without_contact_points_matches() = assertTrue(index.matches(rec("Smith John")))

    @Test fun same_name_with_a_different_number_is_a_different_person() = assertFalse(index.matches(rec("John Smith", phone = "+1 555 000 1111")))

    @Test fun short_numbers_never_match() = assertFalse(index.matches(rec("X", phone = "112")))

    @Test fun records_added_during_import_are_matched_too() {
        val i = DuplicateIndex()
        i.add(rec("New Person", phone = "+1 555 123 4567"))
        assertTrue(i.matches(rec("Other", phone = "(555) 123-4567")))
    }
}
