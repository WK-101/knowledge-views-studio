package com.todocompanion.app

import com.todocompanion.app.domain.NoteEditing
import com.todocompanion.app.domain.NoteLint
import com.todocompanion.app.domain.NoteOutline
import com.todocompanion.app.domain.NoteTokens
import com.todocompanion.app.util.NoteRichRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Wave M — editor craft: outline, stats, lint, tokens, autocomplete, CSV tables. */
class NoteCraftTest {

    @Test fun outlineSkipsFencedCode() {
        val md = "# Title\n\n```\n# not a heading\n```\n\n## Section\n### Sub"
        val o = NoteOutline.outline(md)
        assertEquals(listOf("Title" to 1, "Section" to 2, "Sub" to 3), o.map { it.title to it.level })
    }

    @Test fun statsCountWordsAndReading() {
        val s = NoteOutline.stats("one two three four five")
        assertEquals(5, s.words)
        assertEquals(1, s.readMinutes)
        assertEquals(0, NoteOutline.stats("").words)
    }

    @Test fun lintFlagsCommonNits() {
        val issues = NoteLint.lint("#Heading\n\n## Done.\ntrailing   \n")
        val msgs = issues.map { it.message }
        assertTrue(msgs.any { it.contains("space after #") })
        assertTrue(msgs.any { it.contains("punctuation") })
        assertTrue(msgs.any { it.contains("Trailing") })
        // fenced code is exempt
        assertTrue(NoteLint.lint("```\n#nospace\n```").isEmpty())
    }

    @Test fun tokensExpandDeterministically() {
        val now = LocalDateTime.of(2026, 3, 4, 9, 7)
        assertEquals("2026-03-04", NoteTokens.expand("{{date}}", now))
        assertEquals("09:07", NoteTokens.expand("{{time}}", now))
        assertEquals("2026", NoteTokens.expand("{{date:yyyy}}", now))
        assertTrue(NoteTokens.hasTokens("today is {{date}}"))
    }

    @Test fun wikiAndTagAutocomplete() {
        assertEquals("Al", NoteEditing.linkAutocompleteQuery("see [[Al", 8))
        assertNull(NoteEditing.linkAutocompleteQuery("see [[Alpha]] and", 17))     // already closed
        assertEquals("wo", NoteEditing.tagAutocompleteQuery("hello #wo", 9))
        assertNull(NoteEditing.tagAutocompleteQuery("# Heading", 9))                // heading, not a tag

        assertEquals("see [[Alpha]]", NoteEditing.applyLink("see [[Al", 8, "Alpha").text)
        val tag = NoteEditing.applyTag("hi #wo", 6, "work")
        assertEquals("hi #work ", tag.text)
    }

    @Test fun csvFenceBecomesTable() {
        val out = NoteRichRenderer.csvToTables("```csv\na,b\n1,2\n```")
        assertTrue(out.contains("| a | b |"))
        assertTrue(out.contains("| --- | --- |"))
        assertTrue(out.contains("| 1 | 2 |"))
    }
}
