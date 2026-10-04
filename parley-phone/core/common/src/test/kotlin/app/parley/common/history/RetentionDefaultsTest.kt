package app.parley.common.history

import org.junit.Assert.assertEquals
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
}
