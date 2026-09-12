package com.todocompanion.app

import com.todocompanion.app.domain.NoteGrammar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2a — the one shared note Markdown grammar. These lock the canonical patterns so the engines that now
 *  depend on them (live-styling, block render, related/wrapped analysers, the editor info panel) can't
 *  drift apart again. */
class NoteGrammarTest {

    @Test fun tagMatchesInlineHashtags() {
        val m = NoteGrammar.TAG.findAll("a #work and #nested/tag but not #123 nor ## heading").toList()
        assertEquals(listOf("work", "nested/tag"), m.map { it.groupValues[1] })
    }

    @Test fun tagIgnoresMidWordAndDoubleHash() {
        assertFalse(NoteGrammar.TAG.containsMatchIn("email@x#y"))       // preceded by a word char
        assertFalse(NoteGrammar.TAG.containsMatchIn("## Heading"))      // ## is a heading, not a tag
        assertTrue(NoteGrammar.TAG.containsMatchIn("start #ok"))
    }

    @Test fun wikiLinkWholeMatch() {
        val m = NoteGrammar.WIKI_LINK.findAll("see [[Project Alpha]] and [[Beta]]").map { it.value }.toList()
        assertEquals(listOf("[[Project Alpha]]", "[[Beta]]"), m)
    }

    @Test fun mdLinkWholeMatch() {
        assertEquals("[text](https://x)", NoteGrammar.MD_LINK.find("go [text](https://x) now")?.value)
    }

    @Test fun headingLinePatterns() {
        assertTrue(NoteGrammar.HEADING_LINE.containsMatchIn("### Deep"))
        assertFalse(NoteGrammar.HEADING_LINE.containsMatchIn("####### too many"))   // 7 # is not a heading
        assertFalse(NoteGrammar.HEADING_LINE.containsMatchIn("#notitle"))           // needs a space
        val g = NoteGrammar.HEADING_CONTENT.find("## Title here")!!.groupValues
        assertEquals(2, g[1].length)              // level 2
        assertEquals("Title here", g[2])
    }
}
