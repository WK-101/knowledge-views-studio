package app.parley.common.people

import app.parley.common.people.EditorForm.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFormTest {
    // ---------------------------------------------------------------- "Add" chips and layout

    @Test fun add_choices_offer_hidden_kinds_commonest_first() {
        // A new contact shows name and a blank phone: the phone chip waits until that row is filled.
        val fresh = EditorForm.addChoices(setOf(Kind.PHONE), withBlankRow = setOf(Kind.PHONE))
        assertEquals(Kind.EMAIL, fresh.first())
        assertFalse(Kind.PHONE in fresh)
        assertEquals(EditorForm.chipOrder - Kind.PHONE, fresh)
        // Once filled, "Phone" is offered again (it adds another row) and leads.
        assertEquals(Kind.PHONE, EditorForm.addChoices(setOf(Kind.PHONE)).first())
    }

    @Test fun add_choices_drop_shown_single_kinds_but_keep_repeatable_ones() {
        val shown = setOf(Kind.PHONE, Kind.EMAIL, Kind.WORK, Kind.NOTE, Kind.ADDRESS)
        val c = EditorForm.addChoices(shown, withBlankRow = setOf(Kind.EMAIL))
        assertFalse(Kind.WORK in c)
        assertFalse(Kind.NOTE in c)
        assertFalse(Kind.EMAIL in c)
        assertTrue(Kind.PHONE in c)
        assertTrue(Kind.ADDRESS in c)
        assertEquals(emptyList<Kind>(), EditorForm.addChoices(emptySet(), allowed = emptySet()))
    }

    @Test fun add_choices_respect_allowed_kinds() {
        assertEquals(listOf(Kind.DATE), EditorForm.addChoices(setOf(Kind.NOTE), allowed = setOf(Kind.DATE, Kind.NOTE)))
        // Every kind has a place in the chip order.
        assertEquals(Kind.entries.toSet(), EditorForm.chipOrder.toSet())
    }

    @Test fun type_selector_moves_under_only_when_narrow_or_large_font() {
        assertFalse(EditorForm.typeBelow(fieldWidthDp = 252f, fontScale = 1f))
        assertFalse(EditorForm.typeBelow(fieldWidthDp = 252f, fontScale = 1.15f))
        assertTrue(EditorForm.typeBelow(fieldWidthDp = 252f, fontScale = 1.3f))
        assertTrue(EditorForm.typeBelow(fieldWidthDp = 200f, fontScale = 1f))
    }

    @Test fun my_card_offers_only_its_own_fields_and_one_address() {
        val fresh = EditorForm.meCardChoices(setOf(Kind.PHONE), withBlankRow = setOf(Kind.PHONE), hasAddress = false)
        assertEquals(listOf(Kind.EMAIL, Kind.WORK, Kind.ADDRESS, Kind.NOTE, Kind.WEBSITE, Kind.PROFILE), fresh)
        assertTrue(fresh.none { it in setOf(Kind.DATE, Kind.RELATION, Kind.HANDLE, Kind.LABELS, Kind.CALL_BACKGROUND, Kind.NAME_DETAILS) })
        // The card has one address line: once shown, no second one is offered; numbers can still be added.
        val withAddress = EditorForm.meCardChoices(setOf(Kind.PHONE, Kind.ADDRESS), emptySet(), hasAddress = true)
        assertFalse(Kind.ADDRESS in withAddress)
        assertEquals(Kind.PHONE, withAddress.first())
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
