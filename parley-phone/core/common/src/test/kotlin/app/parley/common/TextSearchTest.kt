package app.parley.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    @Test fun substring_and_accents() {
        assertTrue(TextSearch.matches("stone", "Caitlin Kingstone"))
        assertTrue(TextSearch.matches("jose", "José Álvarez"))
        assertTrue(TextSearch.matches("alv jo", "José Álvarez"))
        assertTrue(TextSearch.matches("1234", "Bob", listOf("+33 6 12 34 56 78")))
        assertFalse(TextSearch.matches("zzz", "Bob"))
    }
}
