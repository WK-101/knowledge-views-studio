package com.todocompanion.app

import com.todocompanion.app.domain.MarkdownStyle
import com.todocompanion.app.domain.MarkdownStyle.Kind
import com.todocompanion.app.domain.NoteAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave Q — inline live-styling span computation + the reading-appearance model. */
class MarkdownStyleTest {

    private fun kinds(text: String) = MarkdownStyle.spans(text).map { it.kind }.toSet()

    private fun spanText(text: String, kind: Kind): List<String> =
        MarkdownStyle.spans(text).filter { it.kind == kind }.map { text.substring(it.start, it.end) }

    @Test fun offsetsAreAlwaysInBounds() {
        val samples = listOf(
            "", "\n", "plain text", "# H1\n## H2\n### H3\n#### H4",
            "**bold** and *italic* and ~~gone~~ and `code`",
            "> a quote with **bold**", "- [ ] todo\n1. one\n* bullet",
            "```\ncode fence\nmore\n```", "[[Wiki Link]] and [md](http://x) and #tag/child",
            "***all***", "no closing **bold", "trailing #",
        )
        for (s in samples) {
            for (sp in MarkdownStyle.spans(s)) {
                assertTrue("start in bounds for <$s>", sp.start in 0..s.length)
                assertTrue("end in bounds for <$s>", sp.end in 0..s.length)
                assertTrue("start<end for <$s>", sp.start < sp.end)
            }
        }
    }

    @Test fun headingsByLevel() {
        assertTrue(Kind.H1 in kinds("# Title"))
        assertTrue(Kind.H2 in kinds("## Title"))
        assertTrue(Kind.H3 in kinds("### Title"))
        assertTrue("h4 folds into H3", Kind.H3 in kinds("#### Title"))
        // the leading #'s are dimmed as SYNTAX
        assertTrue(Kind.SYNTAX in kinds("## Title"))
        // a bare '#' with no text is NOT a heading
        assertFalse(Kind.H1 in kinds("#"))
    }

    @Test fun inlineEmphasisAndCode() {
        val k = kinds("**b** *i* ~~s~~ `c`")
        assertTrue(Kind.BOLD in k); assertTrue(Kind.ITALIC in k)
        assertTrue(Kind.STRIKE in k); assertTrue(Kind.CODE in k)
        assertEquals(listOf("**b**"), spanText("**b** *i*", Kind.BOLD))
    }

    @Test fun markupInsideCodeSpanIsNotStyled() {
        // the `**not bold**` is inside a code span, so no BOLD span should appear
        val k = kinds("`**not bold**`")
        assertTrue(Kind.CODE in k)
        assertFalse(Kind.BOLD in k)
    }

    @Test fun fencedBlockIsAllCode() {
        val text = "before\n```kotlin\nval x = **1**\n```\nafter"
        val codeText = spanText(text, Kind.CODE)
        assertTrue(codeText.any { it.contains("val x") })
        // the ** inside the fence must not become bold
        assertFalse(Kind.BOLD in kinds(text))
    }

    @Test fun linksTagsAndMarkers() {
        assertTrue(Kind.WIKILINK in kinds("see [[My Note]]"))
        assertTrue(Kind.LINK in kinds("see [site](http://x)"))
        assertTrue(Kind.TAG in kinds("filed under #project/alpha"))
        assertTrue(Kind.MARKER in kinds("- [ ] do it"))
        assertTrue(Kind.MARKER in kinds("1. first"))
    }

    // ── appearance model ──
    @Test fun readingThemesResolveAndCarryBothModes() {
        assertEquals("match", NoteAppearance.theme("nope").id)   // unknown → match
        val kairo = NoteAppearance.theme("kairo")
        assertTrue(kairo.palette(dark = false) != null)
        assertTrue(kairo.palette(dark = true) != null)
        assertTrue(NoteAppearance.MATCH.palette(dark = false) == null)
    }

    @Test fun typographyMathIsClampedAndSensible() {
        val t = NoteAppearance.NoteType(font = "serif", scalePct = 500, lineHeight = "relaxed", measure = true)
        assertEquals(2.0f, t.scale(), 0.001f)            // clamped to 200%
        assertEquals(1.9f, t.lineFactor(), 0.001f)
        assertEquals(66, t.measureCh())
        assertEquals("serif", t.effectiveFont("sans"))   // explicit user font wins
        assertEquals("sans", NoteAppearance.NoteType().effectiveFont("sans")) // system → theme font
    }
}
