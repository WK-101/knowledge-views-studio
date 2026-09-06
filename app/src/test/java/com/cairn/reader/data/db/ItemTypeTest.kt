package com.cairn.reader.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemTypeTest {

    @Test fun `fromRaw resolves the exact enum name`() {
        assertEquals(ItemType.ARTICLE, ItemType.fromRaw("ARTICLE"))
        assertEquals(ItemType.AUDIO, ItemType.fromRaw("AUDIO"))
        assertEquals(ItemType.PDF, ItemType.fromRaw("PDF"))
    }

    @Test fun `fromRaw is case-sensitive and null-safe`() {
        assertNull(ItemType.fromRaw("audio"))
        assertNull(ItemType.fromRaw("unknown"))
        assertNull(ItemType.fromRaw(null))
    }

    @Test fun `known types use their curated plural and singular labels`() {
        assertEquals("Podcasts", ItemType.label("AUDIO"))
        assertEquals("Podcast", ItemType.labelSingular("AUDIO"))
        assertEquals("Articles", ItemType.label("ARTICLE"))
        assertEquals("Article", ItemType.labelSingular("ARTICLE"))
        assertEquals("PDFs", ItemType.label("PDF"))
    }

    @Test fun `unknown raw values fall back to title case`() {
        assertEquals("Something", ItemType.label("SOMETHING"))
        assertEquals("Something", ItemType.labelSingular("something"))
        // Case-sensitivity means a lowercase known name misses the curated label and is title-cased.
        assertEquals("Pdf", ItemType.label("pdf"))
    }
}
