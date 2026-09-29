package app.parley.common.people

import app.parley.common.people.PrivateLabels.Group
import app.parley.common.people.PrivateLabels.Membership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLabelsTest {
    // "Family" exists in two accounts (ids 1 and 5); the first is its canonical id.
    private val groups = listOf(Group(1, "Family"), Group(2, "Work"), Group(5, "Family "))

    @Test fun memberships_follow_renames_by_id_and_find_labels_again_by_title() {
        // Renamed elsewhere: the id still says which label it is.
        val renamed = listOf(Group(1, "Family & friends"), Group(2, "Work"))
        assertEquals(setOf("Family & friends"), PrivateLabels.titles(listOf(Membership(1, "Family")), renamed))
        // Restored on another phone: the id means nothing there, the title finds the label.
        assertEquals(listOf(Membership(2, "Work")), PrivateLabels.resolve(listOf(Membership(77, "Work")), groups))
        // Another account's copy of a label resolves to the canonical id, once.
        assertEquals(listOf(Membership(1, "Family")), PrivateLabels.resolve(listOf(Membership(5, "Family"), Membership(1, "Family")), groups))
    }

    @Test fun a_label_that_is_gone_is_hidden_but_kept_through_edits() {
        val stored = listOf(Membership(1, "Family"), Membership(9, "Old club"))
        assertEquals(setOf("Family"), PrivateLabels.titles(stored, groups))
        assertEquals(listOf(Membership(9, "Old club")), PrivateLabels.unresolved(stored, groups))
        // The editor showed only Family and Work: choosing Work keeps the one it couldn't show.
        val saved = PrivateLabels.fromIds(setOf(2), groups, stored)
        assertEquals(setOf("Work", "Old club"), saved.map { it.title }.toSet())
        // Made again later: the membership is back.
        assertEquals(setOf("Work", "Old club"), PrivateLabels.titles(saved, groups + Group(12, "Old club")))
    }

    @Test fun without_the_address_book_the_stored_titles_are_trusted() {
        val stored = listOf(Membership(1, "Family"), Membership(9, "Old club"))
        assertEquals(setOf("Family", "Old club"), PrivateLabels.titles(stored, emptyList()))
        assertTrue(PrivateLabels.unresolved(stored, emptyList()).isEmpty())
    }

    @Test fun add_remove_rename_and_merge() {
        var m = PrivateLabels.add(emptyList(), Group(1, "Family"))
        m = PrivateLabels.add(m, Group(5, "Family"))
        assertEquals(1, m.size)
        m = PrivateLabels.add(m, Group(2, "Work"))
        assertEquals(setOf("Family", "Work"), PrivateLabels.titles(m, groups))
        // Removing a label removes it in every account.
        assertEquals(setOf("Work"), PrivateLabels.titles(PrivateLabels.remove(m, "Family", groups), groups))
        // Merging Work into Family: one membership left, under Family's id.
        val merged = PrivateLabels.renamed(m, mapOf("Work" to "Family"), mapOf("Family" to 1L))
        assertEquals(listOf(Membership(1, "Family")), merged)
        val renamed = PrivateLabels.renamed(m, mapOf("Work" to "Office"), mapOf("Office" to 2L))
        assertEquals(setOf("Family", "Office"), renamed.map { it.title }.toSet())
    }

    @Test fun counts_add_up_every_private_member() {
        assertEquals(mapOf("Family" to 2, "Work" to 1), PrivateLabels.counts(mapOf(1L to setOf("Family"), 2L to setOf("Family", "Work"), 3L to emptySet())))
        assertFalse(PrivateLabels.counts(emptyMap()).containsKey("Family"))
    }

    @Test fun bulk_actions_that_would_copy_a_private_contact_out_act_on_device_contacts_only() {
        val mixed = listOf(10L, -3L, 11L, -4L)
        assertEquals(BulkActions.Targets(mixed, 0), BulkActions.targets(BulkAction.STAR, mixed))
        assertEquals(BulkActions.Targets(mixed, 0), BulkActions.targets(BulkAction.ADD_TO_LABEL, mixed))
        assertEquals(BulkActions.Targets(mixed, 0), BulkActions.targets(BulkAction.DELETE, mixed))
        assertEquals(BulkActions.Targets(mixed, 0), BulkActions.targets(BulkAction.INTRODUCE, mixed))
        listOf(BulkAction.SHARE, BulkAction.EXPORT, BulkAction.COPY_AS_TEXT, BulkAction.MERGE).forEach {
            assertEquals(it.name, BulkActions.Targets(listOf(10L, 11L), 2), BulkActions.targets(it, mixed))
        }
        assertEquals(listOf(10L, 11L), BulkActions.targets(BulkAction.MAKE_PRIVATE, mixed).ids)
        assertEquals(listOf(-3L, -4L), BulkActions.targets(BulkAction.MAKE_VISIBLE, mixed).ids)
        assertFalse(BulkActions.available(BulkAction.MERGE, listOf(10L, -3L)))
        assertTrue(BulkActions.available(BulkAction.MERGE, mixed))
        assertFalse(BulkActions.available(BulkAction.MAKE_VISIBLE, listOf(10L)))
    }
}
