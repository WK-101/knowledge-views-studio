package app.parley.common.circle

import app.parley.common.circle.Agenda.Shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgendaTest {
    @Test fun an_item_is_added_as_a_promise_line_of_the_note() {
        assertEquals("[ ] Ask about the trip", Agenda.add(null, "Ask about the trip"))
        assertEquals("Gate code 1234\n[ ] Ask about the trip", Agenda.add("Gate code 1234\n\n", "  Ask about   the trip "))
        // It is a promise like any other: the promises card, Recall and case files see it.
        assertEquals(listOf("Ask about the trip"), Promises.open(Agenda.add("Gate code", "Ask about the trip")).map { it.text })
    }

    @Test fun adding_cleans_the_text_and_keeps_one_of_the_same() {
        assertEquals("[ ] Photos", Agenda.add(null, "[ ] Photos"))
        assertEquals("[ ] Photos", Agenda.add(null, "- [x] Photos"))
        assertEquals("[ ] Photos", Agenda.add("[ ] Photos", "photos"))
        assertEquals("note", Agenda.add("note", "   "))
        assertEquals(Agenda.MAX_LENGTH, Agenda.clean("a".repeat(500))!!.length)
        assertEquals("two lines", Agenda.clean("two\nlines"))
        assertNull(Agenda.clean("[ ]"))
    }

    @Test fun an_item_ticked_off_can_be_added_again() {
        val note = "[x] Photos"
        assertEquals("[x] Photos\n[ ] Photos", Agenda.add(note, "Photos"))
    }

    @Test fun the_lifecycle_add_tick_untick() {
        var note = Agenda.add("Call after 6", "Birthday plans")
        note = Agenda.add(note, "The loan")
        assertEquals(listOf("Birthday plans", "The loan"), Agenda.open(note))
        note = Agenda.setDone(note!!, "Birthday plans", true)
        assertEquals(listOf("The loan"), Agenda.open(note))
        assertEquals("Call after 6\n[x] Birthday plans\n[ ] The loan", note)
        // Ticking what is already ticked, or an item that is gone, changes nothing.
        assertEquals(note, Agenda.setDone(note, "Birthday plans", true))
        assertEquals(note, Agenda.setDone(note, "Something else", true))
        note = Agenda.setDone(note, "Birthday plans", false)
        assertEquals(listOf("Birthday plans", "The loan"), Agenda.open(note))
    }

    @Test fun the_pinned_line_leaves_the_items_out() {
        assertEquals("Call after 6\n\nGate code", Agenda.withoutItems("Call after 6\n[ ] Photos\n\n\n[x] Loan\nGate code"))
        assertNull(Agenda.withoutItems("[ ] Photos\n[x] Loan"))
        assertNull(Agenda.withoutItems("  "))
        assertNull(Agenda.withoutItems(null))
        assertEquals("Plain note", Agenda.withoutItems("Plain note"))
        // An empty box being typed is a box too.
        assertEquals("Hi", Agenda.withoutItems("Hi\n[ ]"))
    }

    @Test fun unlocked_shows_the_items() {
        assertEquals(Shown.ITEMS, Agenda.shown(2, locked = false, textOnLockScreen = false, masked = false, privateContact = false))
        assertEquals(Shown.ITEMS, Agenda.shown(2, locked = false, textOnLockScreen = false, masked = false, privateContact = true))
        assertEquals(Shown.NOTHING, Agenda.shown(0, locked = false, textOnLockScreen = true, masked = false, privateContact = false))
    }

    @Test fun the_lock_screen_shows_a_count_unless_notes_may_show_there() {
        assertEquals(Shown.COUNT, Agenda.shown(3, locked = true, textOnLockScreen = false, masked = false, privateContact = false))
        assertEquals(Shown.ITEMS, Agenda.shown(3, locked = true, textOnLockScreen = true, masked = false, privateContact = false))
    }

    @Test fun a_private_contact_or_a_masked_caller_shows_nothing_on_the_lock_screen() {
        for (allowed in listOf(false, true)) {
            assertEquals(Shown.NOTHING, Agenda.shown(1, locked = true, textOnLockScreen = allowed, masked = false, privateContact = true))
            assertEquals(Shown.NOTHING, Agenda.shown(1, locked = true, textOnLockScreen = allowed, masked = true, privateContact = false))
        }
    }

    @Test fun the_compact_card_lists_two_then_all() {
        val items = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "b"), Agenda.visible(items, expanded = false))
        assertEquals(2, Agenda.moreThanShown(items, expanded = false))
        assertEquals(items, Agenda.visible(items, expanded = true))
        assertEquals(0, Agenda.moreThanShown(items, expanded = true))
        assertEquals(0, Agenda.moreThanShown(listOf("a"), expanded = false))
    }

    @Test fun after_the_call_asks_about_what_was_not_ticked() {
        val start = listOf("Photos", "Loan", "Trip")
        assertEquals(listOf("Loan", "Trip"), Agenda.toAskAfter(start, setOf("Photos"), listOf("Loan", "Trip", "New"), connected = true))
        // Ticked elsewhere meanwhile (the contact's page): not asked. Added during the call: not asked either.
        assertEquals(listOf("Trip"), Agenda.toAskAfter(start, setOf("Photos"), listOf("Trip", "New"), connected = true))
        // A call that never connected asks nothing.
        assertEquals(emptyList<String>(), Agenda.toAskAfter(start, emptySet(), start, connected = false))
    }
}
