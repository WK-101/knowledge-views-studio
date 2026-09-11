package com.todocompanion.app

import com.todocompanion.app.domain.MarkdownSections
import com.todocompanion.app.domain.MarkdownTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave R — the visual table editor + section reorder pure models. */
class MarkdownBlocksTest {

    @Test fun tableParsesAndRoundTrips() {
        val md = "| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"
        val t = MarkdownTable.parse(md)!!
        assertEquals(listOf("A", "B"), t.headers)
        assertEquals(2, t.rows.size)
        assertEquals(listOf("3", "4"), t.rows[1])
        // re-parse of a serialize is stable
        val again = MarkdownTable.parse(MarkdownTable.serialize(t))!!
        assertEquals(t, again)
    }

    @Test fun tableNormalizesRaggedRowsAndEscapesPipes() {
        val t = MarkdownTable.parse("| A | B | C |\n|---|---|---|\n| x |")!!
        assertEquals(3, t.rows[0].size)   // padded to header width
        val esc = MarkdownTable.parse("| A |\n|---|\n| a \\| b |")!!
        assertEquals("a | b", esc.rows[0][0])
    }

    @Test fun notATableReturnsNull() {
        assertNull(MarkdownTable.parse("just some text\nand more"))
        assertNull(MarkdownTable.parse("| only header |"))
    }

    @Test fun emptyTableHasShape() {
        val e = MarkdownTable.empty(3, 2)
        assertEquals(3, e.headers.size)
        assertEquals(2, e.rows.size)
        assertEquals(3, e.rows[0].size)
    }

    @Test fun sectionsSplitByHeadingAndReorder() {
        val body = "intro line\n\n# One\na\nb\n\n# Two\nc\n\n# Three\nd"
        val secs = MarkdownSections.sections(body)
        // preamble + 3 headings
        assertEquals(4, secs.size)
        assertEquals("", secs[0].heading)
        assertEquals("# One", secs[1].heading)
        // move "Two" (index 2) above "One" (index 1)
        val moved = MarkdownSections.move(body, from = 2, to = 1)
        val order = MarkdownSections.sections(moved).mapNotNull { it.heading.ifEmpty { null } }
        assertEquals(listOf("# Two", "# One", "# Three"), order)
    }

    @Test fun sectionsAreFencedCodeAware() {
        val body = "# Real\n```\n# not a heading\n```\ntext"
        val secs = MarkdownSections.sections(body)
        assertEquals(1, secs.count { it.heading.startsWith("#") })
        assertTrue(secs[0].body.contains("# not a heading"))
    }

    @Test fun moveIsClamped() {
        val body = "# A\nx\n# B\ny"
        assertEquals(body, MarkdownSections.move(body, from = 9, to = 0)) // out of range → unchanged
    }
}
