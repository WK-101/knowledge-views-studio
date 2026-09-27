package app.parley.common

import app.parley.common.people.TemporaryExpiry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryMergeTest {
    @Test fun merge_keeps_every_raw_and_the_earlier_expiry() {
        val (ids, at) = TemporaryExpiry.merge("12,15" to 500L, "15,20" to 300L)
        assertEquals(setOf(12L, 15L, 20L), TemporaryExpiry.decodeIds(ids))
        assertEquals(300L, at)
    }

    @Test fun merge_with_missing_ids_keeps_the_other() {
        assertEquals("7" to 100L, TemporaryExpiry.merge(null to 100L, "7" to Long.MAX_VALUE))
        assertEquals(null to 100L, TemporaryExpiry.merge(null to 100L, null to 200L))
    }

    @Test fun pending_key_is_recognisable() {
        val k = TemporaryExpiry.pendingKey(42)
        assertTrue(TemporaryExpiry.isPendingKey(k))
        assertFalse(TemporaryExpiry.isPendingKey("0r1-2A3B"))
    }
}
