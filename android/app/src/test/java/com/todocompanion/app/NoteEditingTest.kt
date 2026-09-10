package com.todocompanion.app

import com.todocompanion.app.domain.NoteEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Wave A — the pure editor mechanics (toolbar wrapping, line prefixes, smart list continuation). */
class NoteEditingTest {
    @Test fun wrapInsertsEmptyPairWithCaretBetween() {
        val e = NoteEditing.wrapInline("", 0, 0, "**")
        assertEquals("****", e.text)
        assertEquals(2, e.selStart); assertEquals(2, e.selEnd)
    }

    @Test fun wrapWrapsSelectionAndCoversInnerText() {
        val e = NoteEditing.wrapInline("say hello now", 4, 9, "**")   // "hello"
        assertEquals("say **hello** now", e.text)
        assertEquals("hello", e.text.substring(e.selStart, e.selEnd))
    }

    @Test fun linePrefixGoesToStartOfCaretLine() {
        val text = "one\ntwo\nthree"
        val caret = 5   // inside "two"
        val e = NoteEditing.insertLinePrefix(text, caret, "> ")
        assertEquals("one\n> two\nthree", e.text)
        assertEquals(7, e.selStart)  // caret shifted by 2
    }

    @Test fun continuesBulletList() {
        val text = "- milk\n"
        val e = NoteEditing.continueList(text, text.length)!!
        assertEquals("- milk\n- ", e.text)
        assertEquals(e.text.length, e.selStart)
    }

    @Test fun continuesCheckboxList() {
        val text = "- [ ] buy milk\n"
        val e = NoteEditing.continueList(text, text.length)!!
        assertEquals("- [ ] buy milk\n- [ ] ", e.text)
    }

    @Test fun autoIncrementsOrderedList() {
        val text = "1. first\n2. second\n"
        val e = NoteEditing.continueList(text, text.length)!!
        assertEquals("1. first\n2. second\n3. ", e.text)
    }

    @Test fun emptyMarkerExitsList() {
        val text = "- a\n- \n"            // second bullet is empty, Enter pressed after it
        val e = NoteEditing.continueList(text, text.length)!!
        assertEquals("- a\n", e.text)     // empty marker line cleared, list exited
    }

    @Test fun preservesIndentWhenContinuing() {
        val text = "  - nested\n"
        val e = NoteEditing.continueList(text, text.length)!!
        assertEquals("  - nested\n  - ", e.text)
    }

    @Test fun noContinuationWhenNotAfterNewline() {
        assertNull(NoteEditing.continueList("- milk", 6))
        assertNull(NoteEditing.continueList("plain\n", 6))  // previous line not a list item
    }

    @Test fun togglesCheckboxOnLine() {
        val text = "# Plan\n- [ ] a\n- [x] b"
        assertEquals("# Plan\n- [x] a\n- [x] b", NoteEditing.toggleCheckboxAtLine(text, 1))
        assertEquals("# Plan\n- [ ] a\n- [ ] b", NoteEditing.toggleCheckboxAtLine(text, 2))
        assertEquals(text, NoteEditing.toggleCheckboxAtLine(text, 0))   // heading line: no-op
    }
}
