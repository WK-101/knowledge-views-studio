package app.parley.common.people

import app.parley.common.people.EditorForm.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFormTest {
    // ---------------------------------------------------------------- "Add more info"

    @Test fun addable_lists_only_kinds_not_shown_in_sheet_order() {
        assertEquals(Kind.entries, EditorForm.addable(emptySet()))
        assertEquals(
            listOf(Kind.NAME_DETAILS, Kind.ADDRESS, Kind.HANDLE, Kind.NOTE),
            EditorForm.addable(setOf(Kind.DATE, Kind.WEBSITE, Kind.RELATION)),
        )
        assertEquals(emptyList<Kind>(), EditorForm.addable(Kind.entries.toSet()))
    }

    @Test fun addable_respects_allowed_kinds() {
        assertEquals(listOf(Kind.DATE), EditorForm.addable(setOf(Kind.NOTE), allowed = setOf(Kind.DATE, Kind.NOTE)))
    }

    // ---------------------------------------------------------------- dirty state and Save

    private data class R(val id: Long?, val v: String)

    @Test fun new_blank_rows_are_not_a_change() {
        val start = listOf(R(1, "123"))
        val withBlank = start + R(null, " ")
        val m = { l: List<R> -> EditorForm.meaningful(l, { it.id == null }, { it.v.isBlank() }) }
        assertEquals(m(start), m(withBlank))
        // Something typed in the new row is a change…
        assertNotEquals(m(start), m(start + R(null, "4")))
        // …and so is blanking a saved row (it gets deleted on save).
        assertNotEquals(m(start), m(listOf(R(1, ""))))
    }

    @Test fun save_enabled_rules() {
        // New: anything typed, even unchanged from the prefill.
        assertTrue(EditorForm.canSave(isNew = true, changed = false, hasContent = true, saving = false))
        assertFalse(EditorForm.canSave(isNew = true, changed = true, hasContent = false, saving = false))
        // Existing: only a real change.
        assertFalse(EditorForm.canSave(isNew = false, changed = false, hasContent = true, saving = false))
        assertTrue(EditorForm.canSave(isNew = false, changed = true, hasContent = false, saving = false))
        assertFalse(EditorForm.canSave(isNew = false, changed = true, hasContent = true, saving = true))
    }

    @Test fun has_content() {
        assertFalse(EditorForm.hasContent(listOf("", "  ")))
        assertTrue(EditorForm.hasContent(listOf("", "Ana")))
    }

    // ---------------------------------------------------------------- hints

    @Test fun email_hint() {
        assertFalse(EditorForm.emailLooksWrong(""))
        assertFalse(EditorForm.emailLooksWrong(" ana@example.org "))
        assertFalse(EditorForm.emailLooksWrong("a.b+c@mail.co.uk"))
        assertTrue(EditorForm.emailLooksWrong("ana@example"))
        assertTrue(EditorForm.emailLooksWrong("ana example.org"))
        assertTrue(EditorForm.emailLooksWrong("ana@@example.org"))
        assertTrue(EditorForm.emailLooksWrong("ana@example."))
    }

    @Test fun phone_hint() {
        assertFalse(EditorForm.phoneLooksWrong(""))
        assertFalse(EditorForm.phoneLooksWrong("+49 30 1234-567"))
        assertFalse(EditorForm.phoneLooksWrong("1-800-FLOWERS"))
        assertFalse(EditorForm.phoneLooksWrong("*#06#"))
        assertFalse(EditorForm.phoneLooksWrong("0301234,,55 ext 12"))
        assertTrue(EditorForm.phoneLooksWrong("ana@example.org"))
        assertTrue(EditorForm.phoneLooksWrong("call me"))
    }

    // ---------------------------------------------------------------- row keys

    @Test fun row_keys_are_stable_across_add_and_remove() {
        val k = RowKeys()
        val first = k.keys("phone", 3)
        assertEquals(3, first.distinct().size)
        val added = k.added("phone", 3)
        assertEquals(first + added, k.keys("phone", 4))
        k.removed("phone", 1)
        assertEquals(listOf(first[0], first[2], added), k.keys("phone", 3))
        // Groups are independent, and keys are never reused.
        val mail = k.keys("email", 1)
        assertTrue(mail.single() !in first + added)
        // A list that shrank elsewhere drops keys from the end.
        assertEquals(listOf(first[0]), k.keys("phone", 1))
        assertTrue(k.added("phone", 1) !in first + added + mail)
    }

    @Test fun row_keys_ignore_bad_index() {
        val k = RowKeys()
        val a = k.keys("x", 2)
        k.removed("x", 5)
        k.removed("nope", 0)
        assertEquals(a, k.keys("x", 2))
    }
}
