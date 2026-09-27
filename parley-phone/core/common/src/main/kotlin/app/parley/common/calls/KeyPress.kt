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
 *
 * [deferPress] (a key inside a scrolling container, such as the in-call keypad): the press waits until the touch
 * has settled ([settle], after the tap timeout without scrolling) or is lifted as a tap, so a finger that starts a
 * scroll on a key never sends a DTMF digit. A scroll that takes over ([cancel]) or a cancelling drag ([move] with
 * `scrolled`, see [cancelsPending]) before then drops the press entirely; sliding off the key otherwise types it.
 * Times are any monotonic clock in milliseconds.
 */
class KeyPressTracker(
    val longPressMs: Long = LONG_PRESS_MS,
    val minToneMs: Long = MIN_TONE_MS,
    val deferPress: Boolean = false,
) {
    private var downAt = -1L
    private var toneAt = -1L
    private var toneOn = false
    private var longFired = false
    private var left = false
    private var pending = false
    private var typed = false

    val pressed: Boolean get() = downAt >= 0

    /** True once the long-press ran for the current touch (the key's normal release does nothing more). */
    val longPressed: Boolean get() = longFired

    /** True once this touch typed the key (its [KeyAction.Press] was emitted): a long-press may then replace it. */
    val typedThisTouch: Boolean get() = typed

    fun down(now: Long): List<KeyAction> {
        downAt = now
        longFired = false
        left = false
        typed = false
        if (deferPress) {
            pending = true
            toneOn = false
            return emptyList()
        }
        return press(now)
    }

    /** [deferPress]: the tap timeout passed and the touch is still on the key, unscrolled: press now. */
    fun settle(now: Long): List<KeyAction> {
        if (!pressed || !pending || left) return emptyList()
        return press(now)
    }

    /**
     * The finger moved; [inside] tells whether it's still over the key, [scrolled] whether it moved past the touch
     * slop (only matters while a deferred press is still pending: then it was the start of a scroll).
     */
    fun move(inside: Boolean, now: Long, scrolled: Boolean = false): List<KeyAction> {
        if (!pressed || left) return emptyList()
        if (pending && scrolled) {
            pending = false
            left = true
            return emptyList()
        }
        // v3.4 review #4: slid off the key without a cancelling drag (sideways or up): the tap still counts.
        if (pending && !inside) {
            left = true
            return press(now) + stopTone(now)
        }
        if (inside) return emptyList()
        left = true
        return stopTone(now)
    }

    /** The long-press timer ran out. Does nothing if the finger left the key or was lifted. */
    fun longPressDue(now: Long): List<KeyAction> {
        if (!pressed || left || longFired || now - downAt < longPressMs) return emptyList()
        val first = if (pending) press(now) else emptyList()
        longFired = true
        return first + stopTone(now) + KeyAction.LongPress
    }

    /** Finger lifted. A deferred press that hadn't settled yet was a quick tap: it presses and stops at once. */
    fun up(now: Long): List<KeyAction> {
        if (!pressed) return emptyList()
        val first = if (pending && !left) press(now) else emptyList()
        val out = first + stopTone(now)
        downAt = -1
        pending = false
        return out
    }

    /** The gesture was taken over (a scrolling parent) or cancelled: stops a running tone, never presses. */
    fun cancel(now: Long): List<KeyAction> {
        if (!pressed) return emptyList()
        pending = false
        val out = stopTone(now)
        downAt = -1
        return out
    }

    private fun press(now: Long): List<KeyAction> {
        pending = false
        typed = true
        toneOn = true
        toneAt = now
        return listOf(KeyAction.Press)
    }

    private fun stopTone(now: Long): List<KeyAction> {
        if (!toneOn) return emptyList()
        toneOn = false
        return listOf(KeyAction.StopTone((minToneMs - (now - toneAt)).coerceAtLeast(0)))
    }

    companion object {
        /**
         * v3.4 review #4: whether a move of ([dx], [dy]) from the down position cancels a deferred press. With
         * [downOnly] (the docked keypad, which only folds on a downward drag) only a downward move past [slop]
         * does; sideways and upward slides still type. Otherwise any move past [slop] does (a scroll either way).
         */
        fun cancelsPending(dx: Float, dy: Float, slop: Float, downOnly: Boolean): Boolean =
            if (downOnly) dy > slop else dx * dx + dy * dy > slop * slop

        const val LONG_PRESS_MS = 500L
        const val MIN_TONE_MS = 150L
        /** How long a deferred press waits for the touch to settle (Android's tap timeout). */
        const val TAP_TIMEOUT_MS = 100L
    }
}

/**
 * v3.4 review #4: keeps deferred key presses in the order the keys went down. A key whose press waits for the touch
 * to settle registers with [waiting]; when another key goes down first, every earlier waiting press is committed
 * (oldest first) before the new one waits, so rolling from one key to the next never swaps digits. A commit for a
 * press that was already typed or dropped does nothing (the tracker ignores it). Not thread-safe: one UI thread.
 */
class PressOrder {
    private val pending = ArrayList<Pair<Long, () -> Unit>>()
    private var next = 0L

    /** Commits earlier waiting presses, then registers [commit]; returns the token for [done]. */
    fun waiting(commit: () -> Unit): Long {
        flush()
        val id = next++
        pending += id to commit
        return id
    }

    /** The press for [token] was typed, dropped or lifted: forget it. */
    fun done(token: Long) {
        pending.removeAll { it.first == token }
    }

    /** Commits every waiting press, oldest first. */
    fun flush() {
        if (pending.isEmpty()) return
        val all = pending.toList()
        pending.clear()
        all.forEach { it.second() }
    }

    val waitingCount: Int get() = pending.size
}
