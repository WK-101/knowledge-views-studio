package app.parley.ui.home

import android.app.Application
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hardware keys on the keypad, as the keypad's own part handles them now that it stands apart from the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KeypadKeyInputTest {
    private val pressed = StringBuilder()
    private val typed = StringBuilder()
    private var calls = 0

    private fun input(text: String, qwerty: Boolean = false, takes: Boolean = true): Pair<KeypadKeyInput, TextFieldState> {
        val field = TextFieldState(text, TextRange(text.length))
        return KeypadKeyInput(
            field, text, qwerty, takes = { takes }, call = { calls++ }, press = { pressed.append(it) }, type = { typed.append(it) },
            textSearch = { text.any { it.isLetter() } },
        ) to field
    }

    private fun down(code: Int) = KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, code))

    @Test fun digits_type_and_call_calls_unless_the_list_takes_it() {
        val (keys, _) = input("")
        assertTrue(keys.onKey(down(AndroidKeyEvent.KEYCODE_5)))
        assertTrue(keys.onKey(down(AndroidKeyEvent.KEYCODE_POUND)))
        assertEquals("5#", pressed.toString())
        assertTrue(keys.onKey(down(AndroidKeyEvent.KEYCODE_CALL)))
        assertEquals(1, calls)
        // Docked, with a Recents row focused: Enter belongs to the row.
        val (docked, _) = input("", takes = false)
        assertFalse(docked.onKey(down(AndroidKeyEvent.KEYCODE_ENTER)))
        assertEquals(1, calls)
    }

    @Test fun delete_edits_at_the_cursor_and_lets_go_when_empty() {
        val (keys, field) = input("123")
        assertTrue(keys.onKey(down(AndroidKeyEvent.KEYCODE_DEL)))
        assertEquals("12", field.text.toString())
        val (empty, _) = input("")
        assertFalse(empty.onKey(down(AndroidKeyEvent.KEYCODE_DEL)))
    }

    @Test fun letters_search_only_on_a_qwerty_keyboard() {
        val (t9, _) = input("")
        assertFalse(t9.onKey(down(AndroidKeyEvent.KEYCODE_A)))
        val (qwerty, _) = input("", qwerty = true)
        assertTrue(qwerty.onKey(down(AndroidKeyEvent.KEYCODE_A)))
        assertEquals("a", typed.toString())
    }
}
