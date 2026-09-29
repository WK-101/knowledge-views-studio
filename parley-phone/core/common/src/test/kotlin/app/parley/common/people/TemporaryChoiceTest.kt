package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryChoiceTest {
    @Test fun defaults_match_the_keypad_dialog() {
        val c = TemporaryChoice()
        assertEquals(7, c.days)
        assertTrue(c.private)
        assertTrue(c.purgeHistory)
        assertEquals(listOf(1, 7, 30), TemporaryChoice.presets)
    }

    @Test fun expiry_is_days_after_now() {
        assertEquals(1_000L + 2 * 86_400_000L, TemporaryChoice(days = 2).expiresAt(1_000L))
    }

    @Test fun custom_days_are_bounded() {
        assertEquals(14, TemporaryChoice.parseDays(" 14 "))
        assertEquals(3650, TemporaryChoice.parseDays("3650"))
        assertNull(TemporaryChoice.parseDays("0"))
        assertNull(TemporaryChoice.parseDays("3651"))
        assertNull(TemporaryChoice.parseDays(""))
        assertNull(TemporaryChoice.parseDays("7d"))
    }

    @Test fun expiry_change_resolution() {
        assertNull(ExpiryChange.resolve(currentlyTemporary = true, picked = null))
        assertNull(ExpiryChange.resolve(currentlyTemporary = false, picked = ExpiryChange.Keep))
        assertEquals(ExpiryChange.Keep, ExpiryChange.resolve(currentlyTemporary = true, picked = ExpiryChange.Keep))
        assertEquals(ExpiryChange.After(30), ExpiryChange.resolve(currentlyTemporary = false, picked = ExpiryChange.After(30)))
        assertEquals(ExpiryChange.After(1), ExpiryChange.resolve(currentlyTemporary = true, picked = ExpiryChange.After(1)))
    }
}
