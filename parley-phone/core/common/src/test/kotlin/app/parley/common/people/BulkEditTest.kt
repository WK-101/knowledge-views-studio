package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkEditTest {
    private val bob = 30L
    private val cy = 20L
    private val ada = ContactRef.Private(1).navId

    @Test fun every_edit_but_the_account_move_works_on_private_contacts_too() {
        val ids = listOf(bob, ada, cy)
        listOf(BulkEdit.ADD_LABEL, BulkEdit.REMOVE_LABEL, BulkEdit.RINGTONE, BulkEdit.SIM).forEach { e ->
            assertEquals(e.name, BulkEdits.Plan(ids), BulkEdits.plan(e, ids))
        }
        // A private contact lives in Parley, not in an account: left out, and counted so the bar can say so.
        assertEquals(BulkEdits.Plan(listOf(bob, cy), skippedPrivate = 1), BulkEdits.plan(BulkEdit.MOVE_ACCOUNT, ids))
    }

    @Test fun contacts_already_as_asked_are_left_alone() {
        val plan = BulkEdits.plan(BulkEdit.MOVE_ACCOUNT, listOf(bob, cy, bob, ada)) { it == cy }
        assertEquals(BulkEdits.Plan(listOf(bob), unchanged = listOf(cy), skippedPrivate = 1), plan)
    }

    @Test fun undo_restores_only_what_changed() {
        // Ringtones before "Set ringtone" to the chime: Cy had it already, Ada had none.
        val before = mapOf(bob to "bell", cy to "chime", ada to null)
        assertEquals(mapOf(bob to "bell", ada to null), BulkEdits.previous(before, "chime"))
        // A SIM per number works the same way.
        assertEquals(mapOf("+1555" to null), BulkEdits.previous(mapOf("+1555" to null, "+1666" to "sim2"), "sim2"))
        // Joining a label: Undo takes out the newcomers only; leaving it: Undo puts back the ones who were in it.
        assertEquals(listOf(bob, ada), BulkEdits.labelChange(BulkEdit.ADD_LABEL, listOf(bob, ada, cy), membersBefore = setOf(cy)))
        assertEquals(listOf(cy), BulkEdits.labelChange(BulkEdit.REMOVE_LABEL, listOf(bob, ada, cy), membersBefore = setOf(cy, 99L)))
        assertEquals(emptyList<Long>(), BulkEdits.labelChange(BulkEdit.RINGTONE, listOf(bob), emptySet()))
    }

    @Test fun the_labels_offered_for_removal_are_the_ones_the_selection_is_in() {
        val labels = mapOf(bob to setOf("Work", "family"), ada to setOf("Family"), cy to setOf("Work"))
        assertEquals(
            listOf("Work" to 2, "family" to 1, "Family" to 1),
            BulkEdits.removableLabels(listOf(bob, ada, cy)) { labels[it].orEmpty() },
        )
        assertEquals(emptyList<Pair<String, Int>>(), BulkEdits.removableLabels(listOf(bob)) { emptySet() })
    }
}
