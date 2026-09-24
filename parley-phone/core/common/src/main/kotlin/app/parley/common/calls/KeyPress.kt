package app.parley.common.calls

/** What a keypad key should do in response to a touch event (V7). */
sealed interface KeyAction {
    /** Finger down: type the digit and start its tone (or in-call DTMF) straight away. */
    data object Press : KeyAction

    /** Stop the tone after [afterMs] (0 = now), so every tone lasts at least the minimum length. */
    data class StopTone(val afterMs: Long) : KeyAction

    /** Held long enough without leaving the key: run the key's long-press action. */
    data object LongPress : KeyAction
}

/**
 * Touch handling for one keypad key, as a small state machine that knows nothing about Android (V7):
 * - the tone starts on press, not on release;
 * - the tone stops on release, but never before [minToneMs] (short taps still sound like a key);
 * - sliding off the key stops the tone and cancels the long-press;
 * - each key tracks its own finger, so pressing the next key before releasing the previous one (roll-over) works.
 * Times are any monotonic clock in milliseconds.
 */
class KeyPressTracker(
    val longPressMs: Long = LONG_PRESS_MS,
    val minToneMs: Long = MIN_TONE_MS,
) {
    private var downAt = -1L
    private var toneOn = false
    private var longFired = false
    private var left = false

    val pressed: Boolean get() = downAt >= 0

    /** True once the long-press ran for the current touch (the key's normal release does nothing more). */
    val longPressed: Boolean get() = longFired

    fun down(now: Long): List<KeyAction> {
        downAt = now
        toneOn = true
        longFired = false
        left = false
        return listOf(KeyAction.Press)
    }

    /** The finger moved; [inside] tells whether it's still over the key. */
    fun move(inside: Boolean, now: Long): List<KeyAction> {
        if (!pressed || inside || left) return emptyList()
        left = true
        return stopTone(now)
    }

    /** The long-press timer ran out. Does nothing if the finger left the key or was lifted. */
    fun longPressDue(now: Long): List<KeyAction> {
        if (!pressed || left || longFired || now - downAt < longPressMs) return emptyList()
        longFired = true
        return stopTone(now) + KeyAction.LongPress
    }

    /** Finger lifted (or the gesture was cancelled). */
    fun up(now: Long): List<KeyAction> {
        if (!pressed) return emptyList()
        val out = stopTone(now)
        downAt = -1
        return out
    }

    private fun stopTone(now: Long): List<KeyAction> {
        if (!toneOn) return emptyList()
        toneOn = false
        return listOf(KeyAction.StopTone((minToneMs - (now - downAt)).coerceAtLeast(0)))
    }

    companion object {
        const val LONG_PRESS_MS = 500L
        const val MIN_TONE_MS = 150L
    }
}
