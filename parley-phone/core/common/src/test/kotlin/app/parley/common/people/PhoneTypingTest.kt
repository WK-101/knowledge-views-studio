package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTypingTest {
    @Test fun formats_plain_digits_for_the_region() {
        assertEquals("(202) 555-0134", PhoneTyping.of("2025550134", "US").shown)
        assertEquals("+44 7700 900123", PhoneTyping.of("+447700900123", "US").shown)
    }

    @Test fun keeps_what_the_person_formatted_themselves() {
        assertEquals("020 7946-0018", PhoneTyping.of("020 7946-0018", "GB").shown)
        assertEquals("*21#", PhoneTyping.of("*21#", "GB").shown)
        assertEquals("555,123", PhoneTyping.of("555,123", "US").shown)
        assertFalse(PhoneTyping.formattable("+"))
        assertFalse(PhoneTyping.formattable("1"))
        assertTrue(PhoneTyping.formattable("+1"))
    }

    @Test fun maps_cursor_positions_both_ways() {
        val p = PhoneTyping.of("2025550134", "US")
        // "(202) 555-0134": typed 0 → before "2" at 1, typed 3 → before "5" at 6, end → end.
        assertEquals(1, p.toShown(0))
        assertEquals(6, p.toShown(3))
        assertEquals(p.shown.length, p.toShown(10))
        assertEquals(3, p.toTyped(6))
        assertEquals(0, p.toTyped(0))
        assertEquals(10, p.toTyped(p.shown.length))
        // Every typed position survives a round trip.
        for (i in 0..10) assertEquals(i, p.toTyped(p.toShown(i)))
    }

    @Test fun refuses_a_format_that_changes_characters() {
        assertNull(PhoneTyping.mapped("123", "1 2 4"))
        assertNull(PhoneTyping.mapped("123", "12"))
        assertNull(PhoneTyping.mapped("123", "123x"))
        assertEquals("1 23", PhoneTyping.mapped("123", "1 23")?.shown)
    }
}
