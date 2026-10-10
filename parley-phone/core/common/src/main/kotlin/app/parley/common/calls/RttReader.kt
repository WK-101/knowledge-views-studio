package app.parley.common.calls

/**
 * Which RTT stream the one reader thread of a call reads. Android builds a new `Call.RttCall` over the same pipe
 * on every RTT change (a mode switch, the other side's mode), without closing the old one; a second reader on the same
 * pipe would race the first and lose characters. So each call has exactly one reader: Telecom's latest stream is
 * handed in with [follow] (main thread), and the reader asks [next] after every read which stream to read from now.
 * Thread-safe; [S] is the stream type (`Call.RttCall` on the phone, anything in tests).
 */
class RttReader<S : Any> {
    private var current: S? = null
    private var running = false

    /**
     * Telecom's stream for the call now ([stream]), or null when RTT is off or the call went away. Returns true when a
     * reader thread has to be started for it (none is running); a running one switches over at its next read.
     */
    @Synchronized
    fun follow(stream: S?): Boolean {
        current = stream
        if (stream == null || running) return false
        running = true
        return true
    }

    /** The stream the reader reads now, or null once RTT is off (text that still arrives is then dropped). */
    @Synchronized
    fun current(): S? = current

    /**
     * After a read from [from] ([closed]: it ended), the stream to read next: the same one, Telecom's newer one, or
     * null to end the thread (RTT off, or the stream it read closed and nothing newer came). A thread that gets null
     * is no longer counted as running, so the next [follow] starts a new one.
     */
    @Synchronized
    fun next(from: S, closed: Boolean): S? {
        val now = current
        val n = when {
            now == null -> null
            closed && now === from -> null
            else -> now
        }
        if (n == null) running = false
        return n
    }

    /** Whether text read from any of this call's streams is still shown (RTT is on). */
    @Synchronized
    fun accepts(): Boolean = current != null
}
