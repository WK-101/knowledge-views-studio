package app.parley.data.people

import app.parley.common.people.ThreeWayMerge
import app.parley.data.ContactDetails
import app.parley.data.ContactDraftJson
import app.parley.data.ContactEditRebase
import app.parley.data.DataItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A relation kept in Parley only stays Parley's: stored apart, carried by the editor's draft, kept through a merge. */
@RunWith(RobolectricTestRunner::class)
class ParleyRelationRowsTest {
    private val kept = listOf(DataItem(value = "Ana", type = 13), DataItem(value = "Rui", type = 0, label = "Climbing buddy"))

    @Test fun rows_round_trip_through_the_stored_form() {
        assertEquals(kept, ParleyRelationRows.decode(ParleyRelationRows.encode(kept)))
        assertNull(ParleyRelationRows.encode(emptyList()))
    }

    @Test fun an_editor_draft_keeps_them_apart_from_the_address_book_relations() {
        val d = ContactDetails(given = "Bob", relations = listOf(DataItem(id = 5, value = "Sam", type = 14)), parleyRelations = kept)
        val back = ContactDraftJson.decode(ContactDraftJson.encode(d))
        assertEquals(kept, back.parleyRelations)
        assertEquals(d.relations, back.relations)
    }

    @Test fun a_merge_with_a_newer_copy_keeps_the_edited_ones() {
        val mine = ContactDetails(id = 1, given = "Bob", parleyRelations = kept)
        val theirs = ContactDetails(id = 1, given = "Bobby")
        val out = ContactEditRebase.rebase(null, mine, theirs, emptyMap(), ThreeWayMerge.Side.THEIRS)
        assertEquals(kept, out.parleyRelations)
    }
}
