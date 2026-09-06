package com.cairn.reader.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemStatusTest {

    // -- ExtractStatus --------------------------------------------------------
    @Test fun `extract status resolves and reports extracted`() {
        assertEquals(ExtractStatus.OK, ExtractStatus.fromRaw("OK"))
        assertEquals(ExtractStatus.NONE, ExtractStatus.fromRaw("NONE"))
        assertNull(ExtractStatus.fromRaw("nope"))
        assertNull(ExtractStatus.fromRaw(null))
        assertTrue(ExtractStatus.isExtracted("OK"))
        assertFalse(ExtractStatus.isExtracted("NONE"))
        assertFalse(ExtractStatus.isExtracted("FAILED"))
        assertFalse(ExtractStatus.isExtracted(null))
    }

    @Test fun `extract status raw values are stable`() {
        assertEquals("NONE", ExtractStatus.NONE.raw)
        assertEquals("PENDING", ExtractStatus.PENDING.raw)
        assertEquals("OK", ExtractStatus.OK.raw)
        assertEquals("FAILED", ExtractStatus.FAILED.raw)
    }

    // -- CacheStatus ----------------------------------------------------------
    @Test fun `cache status only permanent counts as permanent`() {
        assertTrue(CacheStatus.isPermanent("PERMANENT"))
        assertFalse(CacheStatus.isPermanent(null))
        assertFalse(CacheStatus.isPermanent(""))
        assertFalse(CacheStatus.isPermanent("CACHED"))
        assertEquals("PERMANENT", CacheStatus.PERMANENT.raw)
    }

    // -- ContentSource --------------------------------------------------------
    @Test fun `content source resolves every known origin`() {
        assertEquals(ContentSource.FEED, ContentSource.fromRaw("FEED"))
        assertEquals(ContentSource.READABLE, ContentSource.fromRaw("READABLE"))
        assertEquals(ContentSource.SHARED, ContentSource.fromRaw("SHARED"))
        assertEquals(ContentSource.PDF, ContentSource.fromRaw("PDF"))
        assertNull(ContentSource.fromRaw("MYSTERY"))
        assertNull(ContentSource.fromRaw(null))
    }

    // -- LinkStatus -----------------------------------------------------------
    @Test fun `link status resolves and reports broken`() {
        assertEquals(LinkStatus.OK, LinkStatus.fromRaw("OK"))
        assertEquals(LinkStatus.BROKEN, LinkStatus.fromRaw("BROKEN"))
        assertNull(LinkStatus.fromRaw(null))
        assertTrue(LinkStatus.isBroken("BROKEN"))
        assertFalse(LinkStatus.isBroken("OK"))
        assertFalse(LinkStatus.isBroken(null))
    }
}
