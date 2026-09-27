package app.parley.common.calls

/**
 * The in-call service's per-call bookkeeping that needs no Telecom objects: since when each call has been on hold,
 * the last live state of each call (so an ended call knows where it was), and which held call to resume once the
 * call in front ends. [S] is the service's own call-state type; the sets say which states count as what.
 * Not thread-safe: the in-call service uses it on the main thread only.
 */
class CallBook<S>(
    /** The on-hold state. */
    private val held: S,
    /** States of the call in front: dialling or talking. When such a call ends, a lone held call is resumed. */
    private val front: Set<S>,
    /** States that keep a held call waiting (a call ringing, dialling, active or asking for a SIM). */
    private val busy: Set<S>,
    /** States of a call that is going away; they never replace its last live state. */
    private val ending: Set<S>,
) {
    private val heldSince = HashMap<String, Long>()
    private val lastLive = HashMap<String, S>()

    /** Top-level call [id] is in [state] at [now] (elapsed time): starts or stops its hold timer. */
    fun update(id: String, state: S, now: Long) {
        if (state == held) heldSince.getOrPut(id) { now } else heldSince.remove(id)
        if (state !in ending) lastLive[id] = state
    }

    /** When call [id] went on hold (elapsed time), or 0 when it isn't held. */
    fun heldSince(id: String): Long = heldSince[id] ?: 0L

    /** The last state call [id] was in before it started to go away. */
    fun lastLiveState(id: String): S? = lastLive[id]

    /** Call [id] left Telecom. Returns whether it was the call in front (then a lone held call may be resumed). */
    fun remove(id: String): Boolean {
        heldSince.remove(id)
        return lastLive.remove(id) in front
    }

    /** No call is left (the service unbound). */
    fun clear() {
        heldSince.clear()
        lastLive.clear()
    }

    /**
     * After the call in front ended: the one held call among the top-level [calls] to resume, or null when another
     * call still needs the line or more than one call is held (the user picks then).
     */
    fun <C> toResume(calls: List<Pair<C, S>>): C? {
        if (calls.any { it.second in busy }) return null
        return calls.filter { it.second == held }.singleOrNull()?.first
    }
}
