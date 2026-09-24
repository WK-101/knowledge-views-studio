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
 * - [onPress] runs as soon as the finger touches the key (type the digit, start the tone or DTMF); with
 *   [deferPress] (keys inside a scrolling container) only once the touch settled without scrolling, or on a tap;
 * - [onToneStop] runs when the finger lifts or slides off, with the delay that makes every tone at least
 *   [KeyPressTracker.MIN_TONE_MS] long; it always runs for a started tone, also when the gesture is cancelled;
 * - [onLongPress] runs after a long press, unless the finger slid off the key first. Its argument tells whether
 *   this same touch typed the key first (so the long-press may replace that digit); TalkBack's long click passes
 *   false, since nothing was typed;
 * - each key follows its own finger, so a second key can be pressed before the first is released (roll-over).
 * TalkBack gets a normal click (and long click) action.
 */
@Composable
fun Modifier.keypadKey(
    onPress: () -> Unit,
    onToneStop: (afterMs: Long) -> Unit,
    onLongPress: ((typedThisTouch: Boolean) -> Unit)? = null,
    longPressLabel: String? = null,
    deferPress: Boolean = false,
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
            // Nothing was typed by this action: the long-press must not replace (delete) a digit.
            if (hasLong) onLongClick(longPressLabel) { long?.invoke(false); true }
        }
        .indication(source, ripple())
        .pointerInput(hasLong, deferPress) {
            val longMs = viewConfiguration.longPressTimeoutMillis
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = KeyPressTracker(longPressMs = longMs, deferPress = deferPress)
                fun run(actions: List<KeyAction>) = actions.forEach { a ->
                    when (a) {
                        KeyAction.Press -> press()
                        is KeyAction.StopTone -> stop(a.afterMs)
                        KeyAction.LongPress -> long?.invoke(tracker.typedThisTouch)
                    }
                }
                val interaction = PressInteraction.Press(down.position)
                scope.launch { source.emit(interaction) }
                var timer: Job? = null
                var settleTimer: Job? = null
                var taken = false
                try {
                    run(tracker.down(SystemClock.uptimeMillis()))
                    if (deferPress) {
                        settleTimer = scope.launch {
                            delay(KeyPressTracker.TAP_TIMEOUT_MS)
                            run(tracker.settle(SystemClock.uptimeMillis()))
                        }
                    }
                    if (hasLong) {
                        timer = scope.launch {
                            delay(longMs)
                            run(tracker.longPressDue(SystemClock.uptimeMillis()))
                        }
                    }
                    while (true) {
                        // Final pass: a scrolling parent has had its turn, so a scroll that took over shows as consumed.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        // Taken over by a scrolling parent: the press is over, and a deferred one never happens.
                        if (change.isConsumed) { taken = true; break }
                        val inside = !change.isOutOfBounds(size, extendedTouchPadding)
                        val scrolled = (change.position - down.position).getDistance() > slop
                        if (!inside || (deferPress && scrolled && !tracker.typedThisTouch)) {
                            timer?.cancel()
                            timer = null
                            settleTimer?.cancel()
                        }
                        run(tracker.move(inside, SystemClock.uptimeMillis(), scrolled))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    taken = true
                    throw e
                } finally {
                    // Always, also when the gesture is cancelled (the key left the screen): no tone keeps playing.
                    timer?.cancel()
                    settleTimer?.cancel()
                    val now = SystemClock.uptimeMillis()
                    run(if (taken) tracker.cancel(now) else tracker.up(now))
                    scope.launch { source.emit(PressInteraction.Release(interaction)) }
                }
            }
        }
}
