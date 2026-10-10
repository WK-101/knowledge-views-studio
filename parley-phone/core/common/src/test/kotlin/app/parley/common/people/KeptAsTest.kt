package app.parley.common.people

import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.RawRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeptAsTest {
    @Test fun kept_as_names_where_a_contact_is() {
        assertEquals(KeptAs.VISIBLE, KeptAs.of(isPrivate = false, archived = false))
        assertEquals(KeptAs.PRIVATE, KeptAs.of(isPrivate = true, archived = false))
        assertEquals(KeptAs.ARCHIVED, KeptAs.of(isPrivate = true, archived = true))
    }

    /** Each choice asks for the change the menu used to make; an archived contact goes back through Unarchive. */
    @Test fun each_choice_is_one_step() {
        assertEquals(KeptAsStep.MAKE_PRIVATE, KeptAs.VISIBLE.stepTo(KeptAs.PRIVATE))
        assertEquals(KeptAsStep.MAKE_VISIBLE, KeptAs.PRIVATE.stepTo(KeptAs.VISIBLE))
        assertEquals(KeptAsStep.ARCHIVE, KeptAs.VISIBLE.stepTo(KeptAs.ARCHIVED))
        assertEquals(KeptAsStep.ARCHIVE, KeptAs.PRIVATE.stepTo(KeptAs.ARCHIVED))
        assertEquals(KeptAsStep.UNARCHIVE, KeptAs.ARCHIVED.stepTo(KeptAs.PRIVATE))
        KeptAs.entries.forEach { assertNull(it.stepTo(it)) }
        // Visible waits until it's unarchived: it is shown, but can't be picked from Archived.
        assertNull(KeptAs.ARCHIVED.stepTo(KeptAs.VISIBLE))
        assertEquals(listOf(KeptAs.VISIBLE to false, KeptAs.PRIVATE to true, KeptAs.ARCHIVED to true), KeptAs.ARCHIVED.choices())
        assertTrue(KeptAs.VISIBLE.choices().all { it.second })
    }

    /** An archived contact is kept until Unarchive: "Delete automatically" isn't offered on its page. */
    @Test fun an_archived_contact_never_deletes_itself() {
        assertTrue(KeptAs.VISIBLE.offersDeleteAutomatically)
        assertTrue(KeptAs.PRIVATE.offersDeleteAutomatically)
        assertEquals(false, KeptAs.ARCHIVED.offersDeleteAutomatically)
    }

    private fun card(id: Long, name: String, number: String, private: Boolean = false) =
        ArchivedView.Match(id, name, private) to listOf(number)

    @Test fun contacts_search_names_the_archived_ones_that_match() {
        val all = listOf(card(1, "Ana Lima", "+351 912 345 678"), card(2, "Bruno", "+351 913 000 111"), card(3, "Anabel", "555", private = true))
        assertNull(ArchivedView.alsoArchived("", all))
        assertNull(ArchivedView.alsoArchived("zed", all))
        val ana = ArchivedView.alsoArchived("ana", all)!!
        assertEquals(listOf("Ana Lima", "Anabel"), ana.matches.map { it.name })
        assertNull(ana.single)
        // By number too; one match opens that contact.
        assertEquals("Bruno", ArchivedView.alsoArchived("913000", all)!!.single?.name)
    }

    @Test fun an_archived_page_reads_its_record_once_per_value() {
        val rows = listOf(
            DataRow("vnd.android.cursor.item/phone_v2", mapOf("data1" to "+351 912 345 678")),
            DataRow("vnd.android.cursor.item/phone_v2", mapOf("data1" to "+351 912 345 678")),
            DataRow("vnd.android.cursor.item/email_v2", mapOf("data1" to "ana@example.com")),
            DataRow("vnd.android.cursor.item/organization", mapOf("data1" to "Lima & Co", "data4" to "Owner")),
            DataRow("vnd.android.cursor.item/note", mapOf("data1" to " ")),
            DataRow("vnd.android.cursor.item/photo", mapOf("data1" to "x")),
        )
        val lines = ArchivedView.lines(ContactRecord("k", "Ana", raws = listOf(RawRecord("com.google", "a@b", rows = rows))))
        assertEquals(
            listOf(
                ArchivedView.Line(ArchivedView.Kind.PHONE, "+351 912 345 678"),
                ArchivedView.Line(ArchivedView.Kind.EMAIL, "ana@example.com"),
                ArchivedView.Line(ArchivedView.Kind.WORK, "Lima & Co", "Owner"),
            ),
            lines,
        )
    }

    @Test fun the_editors_add_chips_come_in_three_groups() {
        val choices = EditorForm.addChoices(setOf(EditorForm.Kind.PHONE), withBlankRow = setOf(EditorForm.Kind.PHONE))
        val grouped = EditorForm.groupedChoices(choices)
        assertEquals(listOf(EditorForm.ChipGroup.CONTACT, EditorForm.ChipGroup.ABOUT, EditorForm.ChipGroup.CALLS), grouped.map { it.first })
        assertEquals(EditorForm.Kind.EMAIL, grouped.first().second.first())
        // Name in their language is offered with the name's details, not as a chip.
        assertTrue(grouped.none { (_, kinds) -> EditorForm.Kind.NATIVE_NAME in kinds })
        // Every other chip is in a group (the name's details are the chevron beside the name).
        assertEquals((choices - EditorForm.Kind.NATIVE_NAME - EditorForm.Kind.NAME_DETAILS).toSet(), grouped.flatMap { it.second }.toSet())
    }
}
