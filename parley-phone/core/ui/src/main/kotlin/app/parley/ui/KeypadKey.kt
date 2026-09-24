package app.parley.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.parley.common.calls.KeyAction
import app.parley.common.calls.KeyPressTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Touch handling for a keypad key (V7), shared by the dialer keypad and the in-call keypad:
 * - [onPress] runs as soon as the finger touches the key (type the digit, start the tone or DTMF);
 * - [onToneStop] runs when the finger lifts or slides off, with the delay that makes every tone at least
 *   [KeyPressTracker.MIN_TONE_MS] long;
 * - [onLongPress] runs after a long press, unless the finger slid off the key first;
 * - each key follows its own finger, so a second key can be pressed before the first is released (roll-over).
 * TalkBack gets a normal click (and long click) action.
 */
@Composable
fun Modifier.keypadKey(
    onPress: () -> Unit,
    onToneStop: (afterMs: Long) -> Unit,
    onLongPress: (() -> Unit)? = null,
    longPressLabel: String? = null,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val press by rememberUpdatedState(onPress)
    val stop by rememberUpdatedState(onToneStop)
    val long by rememberUpdatedState(onLongPress)
    val hasLong = onLongPress != null
    return this
        .semantics {
            role = Role.Button
            onClick {
                press()
                stop(KeyPressTracker.MIN_TONE_MS)
                true
            }
            if (hasLong) onLongClick(longPressLabel) { long?.invoke(); true }
        }
        .indication(source, ripple())
        .pointerInput(hasLong) {
            val longMs = viewConfiguration.longPressTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = KeyPressTracker(longPressMs = longMs)
                fun run(actions: List<KeyAction>) = actions.forEach { a ->
                    when (a) {
                        KeyAction.Press -> press()
                        is KeyAction.StopTone -> stop(a.afterMs)
                        KeyAction.LongPress -> long?.invoke()
                    }
                }
                val interaction = PressInteraction.Press(down.position)
                scope.launch { source.emit(interaction) }
                run(tracker.down(SystemClock.uptimeMillis()))
                var timer: Job? = if (hasLong) {
                    scope.launch {
                        delay(longMs)
                        run(tracker.longPressDue(SystemClock.uptimeMillis()))
                    }
                } else {
                    null
                }
                while (true) {
                    // Final pass: a scrolling parent has had its turn, so a scroll that took over shows as consumed.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Lifted, or taken over by a scrolling parent: either way the press is over.
                    if (!change.pressed || change.isConsumed) break
                    val inside = !change.isOutOfBounds(size, extendedTouchPadding)
                    if (!inside) {
                        timer?.cancel()
                        timer = null
                    }
                    run(tracker.move(inside, SystemClock.uptimeMillis()))
                }
                timer?.cancel()
                run(tracker.up(SystemClock.uptimeMillis()))
                scope.launch { source.emit(PressInteraction.Release(interaction)) }
            }
        }
}
