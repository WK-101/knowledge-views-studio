package app.parley.common.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionDefaultsTest {
    @Test fun aNewInstallKeepsFiveYears() {
        assertEquals(1825, RetentionDefaults.resolve(stored = null, existingUser = false))
    }

    @Test fun anExistingUserWithoutAChoiceKeepsEverything() {
        assertEquals(0, RetentionDefaults.resolve(stored = null, existingUser = true))
    }

    @Test fun aStoredChoiceAlwaysWins() {
        assertEquals(90, RetentionDefaults.resolve(stored = 90, existingUser = false))
        assertEquals(0, RetentionDefaults.resolve(stored = 0, existingUser = false))
        assertEquals(365, RetentionDefaults.resolve(stored = 365, existingUser = true))
    }

    @Test fun theDefaultIsOneOfTheChoices() {
        assertTrue(RetentionDefaults.NEW_INSTALL_DAYS in RetentionDefaults.CHOICES)
        assertEquals(0, RetentionDefaults.CHOICES.first())
    }

    @Test fun aDefaultNeverTrimsTheSystemCallLog() {
        assertEquals(0, RetentionDefaults.systemLogDays(RetentionDefaults.NEW_INSTALL_DAYS, chosen = false))
        assertEquals(90, RetentionDefaults.systemLogDays(90, chosen = true))
    }

    @Test fun whatCountsAsAChoice() {
        // The mark wins whenever it is stored.
        assertTrue(RetentionDefaults.chosen(stored = RetentionDefaults.NEW_INSTALL_DAYS, chosen = true))
        assertFalse(RetentionDefaults.chosen(stored = 90, chosen = false))
        // Without it: a limit an earlier version stored was chosen; nothing, forever or a pinned five years wasn't.
        assertTrue(RetentionDefaults.chosen(stored = 90, chosen = null))
        assertFalse(RetentionDefaults.chosen(stored = null, chosen = null))
        assertFalse(RetentionDefaults.chosen(stored = 0, chosen = null))
        assertFalse(RetentionDefaults.chosen(stored = RetentionDefaults.NEW_INSTALL_DAYS, chosen = null))
    }

    @Test fun aBackupWithoutARetentionMeantForever() {
        assertEquals("i:0", RetentionDefaults.restored(mapOf("theme" to "s:DARK"), "r")["r"])
        assertEquals("i:30", RetentionDefaults.restored(mapOf("r" to "i:30"), "r")["r"])
    }
}
