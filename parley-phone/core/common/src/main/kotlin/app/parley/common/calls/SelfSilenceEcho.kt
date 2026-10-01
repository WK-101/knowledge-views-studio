package app.parley.common.calls

/**
 * Tells Parley's own "silence the ringer" apart from the user's. Telecom answers every
 * TelecomManager.silenceRinger() by calling onSilenceRinger() on every bound in-call service, the caller included
 * (AOSP TelecomServiceImpl.silenceRinger → InCallController.silenceRinger, Android 10 to now). So when Parley silences
 * Telecom to ring its own way (a rule's tone, the unknown-caller tone, a caller's vibration), the same callback the
 * volume key sends comes straight back to it, a few milliseconds later.
 *
 * Each silence Parley asks for is [noted]; the first callback within [WINDOW_MS] of one is its echo and is
 * [consumed]; anything else (the volume key or the power button) is the user's. A user silence inside that window
 * after an echo already came back is still the user's, since every request echoes once. Not thread-safe: the call
 * path's main thread only.
 */
class SelfSilenceEcho(private val windowMs: Long = WINDOW_MS) {
    private val pending = ArrayDeque<Long>()

    /** Parley asked Telecom to silence the ringer at [now] (elapsedRealtime). */
    fun noted(now: Long) {
        drop(now)
        pending.addLast(now)
    }

    /** A silence callback arrived at [now]: true when it is the echo of Parley's own request (and is used up). */
    fun consumed(now: Long): Boolean {
        drop(now)
        if (pending.isEmpty()) return false
        pending.removeFirst()
        return true
    }

    private fun drop(now: Long) {
        while (pending.isNotEmpty() && (now - pending.first() > windowMs || now < pending.first())) pending.removeFirst()
    }

    companion object {
        /** The echo comes back within milliseconds; a second is generous even on a busy phone. */
        const val WINDOW_MS = 1_000L
    }
}
