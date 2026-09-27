package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagingExpiryTest {
    @Test fun expiry_takes_the_stricter_of_record_expiry_and_retention() {
        val now = 100 * 86_400_000L
        assertNull(MessagedRecord.cutoff(0, 0, now))
        assertEquals(now - 7 * 86_400_000L, MessagedRecord.cutoff(7, 0, now))
        assertEquals(now - 30 * 86_400_000L, MessagedRecord.cutoff(90, 30, now))
        assertEquals(now - 30 * 86_400_000L, MessagedRecord.cutoff(0, 30, now))
        val entries = listOf(MessagedEntry("a", "+1", null, "SMS", now - 8 * 86_400_000L), MessagedEntry("b", "+2", null, "SMS", now - 86_400_000L))
        assertEquals(listOf("b"), MessagedRecord.prune(entries, MessagedRecord.cutoff(7, 0, now)!!).map { it.key })
        assertEquals("Never", MessagedRecord.expiryLabel(0))
        assertEquals("After 30 days", MessagedRecord.expiryLabel(30))
    }
}
