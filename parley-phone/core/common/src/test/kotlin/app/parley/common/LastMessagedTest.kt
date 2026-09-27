package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastMessagedTest {
    /** A French and a Spanish mobile that share their last 9 digits. */
    private val fr = "+33612345678"

    private val es = "+34612345678"

    @Test fun record_find_forget_prune() {
        var list = MessagedRecord.record(emptyList(), "06 12 34 56 78", "com.whatsapp", "WhatsApp", 10, "FR")
        list = MessagedRecord.record(list, es, "org.thoughtcrime.securesms", "Signal", 20, "FR")
        assertEquals(2, list.size)
        assertEquals("WhatsApp", MessagedRecord.find(list, fr, "FR")?.label)
        assertEquals("Signal", MessagedRecord.find(list, es, "FR")?.label)
        // Re-recording refreshes the same line instead of adding a second entry.
        list = MessagedRecord.record(list, "+33 6 12 34 56 78", null, "SMS", 30, "FR")
        assertEquals(2, list.size)
        assertEquals("SMS", MessagedRecord.find(list, "0612345678", "FR")?.label)
        list = MessagedRecord.forget(list, fr, "FR")
        assertNull(MessagedRecord.find(list, fr, "FR"))
        assertEquals("Signal", MessagedRecord.find(list, es, "FR")?.label)
        assertTrue(MessagedRecord.prune(list, 21).isEmpty())
    }

    @Test fun capped_and_legacy_entries() {
        var list = emptyList<MessagedEntry>()
        repeat(MessagedRecord.MAX_ENTRIES + 5) { i -> list = MessagedRecord.record(list, "+3361234" + (1000 + i), null, "SMS", i.toLong(), "FR") }
        assertEquals(MessagedRecord.MAX_ENTRIES, list.size)
        assertEquals(5L, list.first().at)
        // An entry of the old plain record (key = last 9 digits) is still found and can be forgotten.
        val legacy = listOfNotNull(MessagedRecord.fromLegacy("612345678", "com.whatsapp", "WhatsApp", 1))
        assertEquals("WhatsApp", MessagedRecord.find(legacy, fr, "FR")?.label)
        assertTrue(MessagedRecord.forget(legacy, fr, "FR").isEmpty())
        // Recording the number again replaces the old entry.
        assertEquals(1, MessagedRecord.record(legacy, fr, null, "SMS", 2, "FR").size)
    }
}
