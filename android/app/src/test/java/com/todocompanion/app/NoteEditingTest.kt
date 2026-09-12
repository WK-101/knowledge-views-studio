package com.todocompanion.app

import com.todocompanion.app.domain.NoteEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun setLineHeadingAddsAndReplacesHashes() {
        val text = "one\ntwo\nthree"
        val caret = 5   // inside "two"
        val h2 = NoteEditing.setLineHeading(text, caret, 2)
        assertEquals("one\n## two\nthree", h2.text)
        // Re-applying a different level replaces (does not stack) the leading #-run.
        val h1 = NoteEditing.setLineHeading(h2.text, 6, 1)
        assertEquals("one\n# two\nthree", h1.text)
        // Level 0 strips back to a paragraph.
        val p = NoteEditing.setLineHeading(h1.text, 5, 0)
        assertEquals("one\ntwo\nthree", p.text)
    }

    @Test fun headingLevelOfReadsCaretLine() {
        val text = "# Title\nbody\n### Deep"
        assertEquals(1, NoteEditing.headingLevelOf(text, 2))    // in "# Title"
        assertEquals(0, NoteEditing.headingLevelOf(text, 9))    // in "body"
        assertEquals(3, NoteEditing.headingLevelOf(text, 18))   // in "### Deep"
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

    @Test fun linksFirstPlainMention() {
        assertEquals("see [[Project Alpha]] soon", NoteEditing.linkMention("see Project Alpha soon", "Project Alpha"))
        // already bracketed → unchanged
        assertEquals("see [[Project Alpha]]", NoteEditing.linkMention("see [[Project Alpha]]", "Project Alpha"))
        // absent → unchanged
        assertEquals("nothing here", NoteEditing.linkMention("nothing here", "Project Alpha"))
    }

    @Test fun togglesCheckboxOnLine() {
        val text = "# Plan\n- [ ] a\n- [x] b"
        assertEquals("# Plan\n- [x] a\n- [x] b", NoteEditing.toggleCheckboxAtLine(text, 1))
        assertEquals("# Plan\n- [ ] a\n- [ ] b", NoteEditing.toggleCheckboxAtLine(text, 2))
        assertEquals(text, NoteEditing.toggleCheckboxAtLine(text, 0))   // heading line: no-op
    }

    // ── Wave G · quick-insert palette ──
    @Test fun quickQueryDoesNotCrashAtColumnZeroOfSlashLine() {
        // Caret at column 0 of a line whose first char is '/' — must return null, never throw.
        assertEquals(null, NoteEditing.quickQuery("/date", 0))
        assertEquals(null, NoteEditing.quickQuery("a\n/date", 2))   // caret just before the '/'
        assertEquals("", NoteEditing.quickQuery("/date", 1))        // caret right after '/' → empty query
        assertEquals("da", NoteEditing.quickQuery("/date", 3))      // mid-token
    }

    @Test fun quickQueryOnlyAtLineStartSlash() {
        assertEquals("", NoteEditing.quickQuery("/", 1))
        assertEquals("head", NoteEditing.quickQuery("/head", 5))
        assertEquals("h1", NoteEditing.quickQuery("intro\n/h1", 9))   // second line begins with / (caret at end)
        assertNull(NoteEditing.quickQuery("a /head", 7))              // slash not at line start
        assertNull(NoteEditing.quickQuery("/he ad", 6))              // whitespace dismisses
    }

    @Test fun filterQuickPrefixRanksFirst() {
        val hits = NoteEditing.filterQuick("h")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().id.startsWith("h"))   // h1/h2/h3 rank above substring matches
        assertTrue(NoteEditing.filterQuick("zzz").isEmpty())
    }

    @Test fun applyQuickReplacesSlashTokenAndPlacesCaret() {
        // "/todo" on its own line → checklist prefix, caret after "- [ ] "
        val e = NoteEditing.applyQuick("/todo", 5, "- [ ] ", 6)
        assertEquals("- [ ] ", e.text)
        assertEquals(6, e.selStart)
        // preserves following lines and only rewrites the caret's line
        val e2 = NoteEditing.applyQuick("/quote\nkeep", 6, "> ", 2)
        assertEquals("> \nkeep", e2.text)
        assertEquals(2, e2.selStart)
    }
}
