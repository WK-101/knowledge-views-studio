package com.cairn.reader.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlCleanerTest {

    @Test fun `drops utm and click-id params but keeps functional ones`() {
        val out = UrlCleaner.strip("https://ex.com/a?id=42&utm_source=news&fbclid=xyz&page=2")
        assertTrue(out.contains("id=42"))
        assertTrue(out.contains("page=2"))
        assertFalse(out.contains("utm_source"))
        assertFalse(out.contains("fbclid"))
    }

    @Test fun `drops whole tracking prefix families`() {
        val out = UrlCleaner.strip("https://ex.com/a?pk_campaign=x&mtm_source=y&keep=1")
        assertTrue(out.contains("keep=1"))
        assertFalse(out.contains("pk_campaign"))
        assertFalse(out.contains("mtm_source"))
    }

    @Test fun `returns the input unchanged when nothing is stripped`() {
        val url = "https://ex.com/a?id=42&page=2"
        assertEquals(url, UrlCleaner.strip(url))
    }

    @Test fun `leaves non-http and query-less input untouched`() {
        assertEquals("mailto:a@b.com", UrlCleaner.strip("mailto:a@b.com"))
        assertEquals("https://ex.com/a", UrlCleaner.strip("https://ex.com/a"))
    }
}
