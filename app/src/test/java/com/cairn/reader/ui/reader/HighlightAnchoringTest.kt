package com.cairn.reader.ui.reader

import com.cairn.reader.data.db.HighlightEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the re-anchoring contract: a highlight follows its quote when the article is re-extracted and
 * the stored block/offset anchors no longer line up. Without this a highlight silently disappears or
 * paints over the wrong words after content changes.
 */
class HighlightAnchoringTest {

    private fun hl(block: Int, start: Int, end: Int, quote: String) = HighlightEntity(
        id = "h", itemId = "i", quote = quote, color = 0,
        startSelector = block.toString(), startOffset = start, endOffset = end, createdAt = 0,
    )

    @Test fun `intact anchor is kept without searching`() {
        val blocks = listOf("The quick brown fox", "jumps over the lazy dog")
        val h = hl(0, 4, 9, "quick") // "The quick"[4..9) == "quick"
        val out = HighlightAnchoring.reanchor(blocks, listOf(h))
        val painted = out[0]!!.single()
        assertEquals(4, painted.startOffset)
        assertEquals(9, painted.endOffset)
        assertEquals("0", painted.startSelector)
    }

    @Test fun `offset drift within the same block is corrected`() {
        // A lead sentence was prepended, so "quick" is now at a different offset.
        val blocks = listOf("Note: The quick brown fox")
        val h = hl(0, 4, 9, "quick") // stale offsets no longer cover "quick"
        val painted = HighlightAnchoring.reanchor(blocks, listOf(h))[0]!!.single()
        assertEquals("quick", blocks[0].substring(painted.startOffset, painted.endOffset))
    }

    @Test fun `block-index shift relocates to the block that now holds the quote`() {
        // An image block was inserted at index 0, pushing the paragraph from block 0 to block 1.
        val blocks = listOf("", "The quick brown fox jumps")
        val h = hl(0, 4, 9, "quick")
        val out = HighlightAnchoring.reanchor(blocks, listOf(h))
        assertTrue("must not paint in the now-wrong block 0", out[0] == null)
        val painted = out[1]!!.single()
        assertEquals("quick", blocks[1].substring(painted.startOffset, painted.endOffset))
        assertEquals("1", painted.startSelector)
    }

    @Test fun `whitespace-trimmed quote still matches`() {
        val blocks = listOf("alpha beta gamma")
        val h = hl(0, 0, 5, "  beta  ") // saved with stray surrounding whitespace
        val painted = HighlightAnchoring.reanchor(blocks, listOf(h))[0]!!.single()
        assertEquals("beta", blocks[0].substring(painted.startOffset, painted.endOffset))
    }

    @Test fun `unfindable quote is dropped, not painted over the wrong text`() {
        val blocks = listOf("completely different content now")
        val h = hl(0, 4, 9, "quick")
        assertTrue(HighlightAnchoring.reanchor(blocks, listOf(h)).isEmpty())
    }

    @Test fun `blank quote is ignored`() {
        assertNull(HighlightAnchoring.reanchor(listOf("text"), listOf(hl(0, 0, 0, "")))[0])
    }

    @Test fun `empty highlight list yields empty map`() {
        assertTrue(HighlightAnchoring.reanchor(listOf("a", "b"), emptyList()).isEmpty())
    }
}
