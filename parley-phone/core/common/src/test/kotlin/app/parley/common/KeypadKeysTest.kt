package app.parley.common

import app.parley.common.KeypadKeys.Key
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadKeysTest {
    @Test fun standalone_keypad_takes_every_key() {
        Key.entries.forEach { assertTrue(KeypadKeys.keypadTakes(it, docked = false, expanded = false, recentsFocused = true)) }
    }

    @Test fun docked_enter_and_call_reach_a_focused_recents_row() {
        for (k in listOf(Key.ENTER, Key.CALL)) {
            assertFalse(KeypadKeys.keypadTakes(k, docked = true, expanded = true, recentsFocused = true))
            assertFalse(KeypadKeys.keypadTakes(k, docked = true, expanded = false, recentsFocused = false))
            assertTrue(KeypadKeys.keypadTakes(k, docked = true, expanded = true, recentsFocused = false))
        }
    }

    @Test fun docked_digits_always_go_to_the_keypad() {
        assertTrue(KeypadKeys.keypadTakes(Key.DIGIT, docked = true, expanded = false, recentsFocused = true))
    }
}
