package com.cairn.reader.ui.reader

import com.cairn.reader.data.db.HighlightEntity

/**
 * Re-locates highlights against the *current* article blocks before they're painted.
 *
 * A highlight is stored as a block index ([HighlightEntity.startSelector]) plus a character range
 * ([HighlightEntity.startOffset]..[HighlightEntity.endOffset]) and the highlighted [HighlightEntity.quote].
 * Those anchors drift when the article is re-extracted or re-linearized — block boundaries move, a
 * lead image is inserted, whitespace collapses differently — so a stored range can point at the wrong
 * text or fall out of bounds, and the highlight visually vanishes or lands on the wrong words.
 *
 * This re-anchors each highlight to where its quote actually is now:
 *  1. trust the stored anchor when the text there still equals the quote (the common, fast path);
 *  2. else re-find the quote within the recorded block (a within-block offset shift);
 *  3. else search every block (a block-index shift);
 *  4. else try the whitespace-trimmed quote (minor re-extraction differences).
 *
 * Purely functional over block texts so it's unit-testable with no Android or WebView dependency.
 * Re-anchoring is render-only: the returned highlights carry corrected offsets for painting, but the
 * stored row is never rewritten, so a highlight always re-locates freshly against whatever is on screen.
 */
object HighlightAnchoring {

    /**
     * Assigns each highlight to the block it should paint in, with corrected offsets (as a copy whose
     * startSelector/startOffset/endOffset point at the located quote). Highlights whose quote can't be
     * found in any block are omitted from the map — they still exist in the database and the notebook,
     * they just aren't drawn over mismatched text.
     */
    fun reanchor(blockTexts: List<String>, highlights: List<HighlightEntity>): Map<Int, List<HighlightEntity>> {
        if (highlights.isEmpty()) return emptyMap()
        val byBlock = HashMap<Int, MutableList<HighlightEntity>>()
        for (h in highlights) {
            val loc = locate(blockTexts, h) ?: continue
            byBlock.getOrPut(loc.block) { mutableListOf() }
                .add(h.copy(startSelector = loc.block.toString(), startOffset = loc.start, endOffset = loc.end))
        }
        return byBlock
    }

    private data class Loc(val block: Int, val start: Int, val end: Int)

    private fun locate(blockTexts: List<String>, h: HighlightEntity): Loc? {
        val quote = h.quote
        if (quote.isEmpty()) return null
        val recorded = h.startSelector?.toIntOrNull() ?: -1

        // 1. Stored anchor still exactly covers the quote → keep it (no search).
        if (recorded in blockTexts.indices) {
            val t = blockTexts[recorded]
            if (h.startOffset in 0..t.length && h.endOffset in h.startOffset..t.length &&
                t.substring(h.startOffset, h.endOffset) == quote
            ) {
                return Loc(recorded, h.startOffset, h.endOffset)
            }
            // 2. Same block, shifted offset.
            val i = t.indexOf(quote)
            if (i >= 0) return Loc(recorded, i, i + quote.length)
        }

        // 3. Block-index shift: find the quote in any block (earliest wins).
        blockTexts.forEachIndexed { idx, t ->
            val i = t.indexOf(quote)
            if (i >= 0) return Loc(idx, i, i + quote.length)
        }

        // 4. Whitespace tolerance for minor re-extraction differences.
        val trimmed = quote.trim()
        if (trimmed.isNotEmpty() && trimmed != quote) {
            blockTexts.forEachIndexed { idx, t ->
                val i = t.indexOf(trimmed)
                if (i >= 0) return Loc(idx, i, i + trimmed.length)
            }
        }
        return null
    }
}
