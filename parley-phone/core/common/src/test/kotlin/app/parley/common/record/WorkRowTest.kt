package app.parley.common.record

import app.parley.common.record.WorkRow.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkRowTest {
    @Test fun a_row_with_only_parts_parley_edits_holds_nothing_else() {
        assertFalse(WorkRow.holdsOthers(mapOf(Col.D1 to "Acme", Col.D4 to "Engineer", Col.D5 to "Research", Col.D2 to "1")))
        assertFalse(WorkRow.holdsOthers(mapOf(Col.D9 to "  ")))
    }

    @Test fun office_job_description_symbol_phonetic_name_or_label_count_as_other_content() {
        for (col in listOf(Col.D3, Col.D6, Col.D7, Col.D8, Col.D9)) assertTrue(col, WorkRow.holdsOthers(mapOf(col to "x")))
    }

    @Test fun an_unchanged_row_is_never_touched_even_when_it_looks_blank() {
        // The old rule deleted a department-only row on any save, because company and title were blank.
        assertEquals(Action.NONE, WorkRow.action(exists = true, editedBlank = true, same = true, holdsOthers = false))
        assertEquals(Action.NONE, WorkRow.action(exists = true, editedBlank = false, same = true, holdsOthers = true))
    }

    @Test fun clearing_everything_parley_edits_keeps_a_row_with_other_content() {
        assertEquals(Action.CLEAR, WorkRow.action(exists = true, editedBlank = true, same = false, holdsOthers = true))
        assertEquals(Action.DELETE, WorkRow.action(exists = true, editedBlank = true, same = false, holdsOthers = false))
    }

    @Test fun edits_update_and_new_content_inserts() {
        assertEquals(Action.UPDATE, WorkRow.action(exists = true, editedBlank = false, same = false, holdsOthers = false))
        assertEquals(Action.INSERT, WorkRow.action(exists = false, editedBlank = false, same = false, holdsOthers = false))
        assertEquals(Action.NONE, WorkRow.action(exists = false, editedBlank = true, same = false, holdsOthers = false))
    }
}
