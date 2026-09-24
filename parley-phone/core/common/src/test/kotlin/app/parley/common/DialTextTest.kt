package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** K4: pasting into the keypad and showing a formatted number over the typed digits. */
class DialTextTest {
    @Test fun sanitize_pasted_text() {
        assertEquals("+15551234567", DialText.sanitize("Tel: +1 (555) 123-4567"))
        assertEquals("0501234567", DialText.sanitize("٠٥٠-١٢٣-٤٥٦٧"))
        assertEquals("*100#", DialText.sanitize("*100#"))
        assertEquals("123,45;6", DialText.sanitize("123,45;6"))
        // Only a leading plus survives.
        assertEquals("+3312", DialText.sanitize("+33+12"))
    }

    @Test fun formatting_inserts() {
        assertEquals(listOf(2 to ' ', 4 to ' '), DialText.formattingInserts("061234", "06 12 34"))
        assertEquals(listOf(0 to '(', 3 to ')', 3 to ' ', 6 to '-'), DialText.formattingInserts("5551234567", "(555) 123-4567"))
        assertEquals(emptyList<Pair<Int, Char>>(), DialText.formattingInserts("112", "112"))
        // A formatter that changes digits (adds a country code) isn't used.
        assertNull(DialText.formattingInserts("0612", "+33 612"))
        assertNull(DialText.formattingInserts("0612", "06"))
    }
}
