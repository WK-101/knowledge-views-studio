package app.parley.common.calls

/** Which live call is in front and whether a second one is waiting. */
enum class LiveCallState { RINGING, ACTIVE, HOLDING, DIALING, OTHER }

object CallWaiting {
    data class Slots<T>(
        /** The call the screen is about: a ringing call first, then the active one, then one being dialled. */
        val primary: T?,
        /** While [primary] rings: the call that is already going (active, else held). */
        val current: T?,
        val held: List<T>,
        /** A ringing call with another call going: the call-waiting sheet, not the normal incoming screen. */
        val waiting: Boolean,
    )

    fun <T> slots(live: List<T>, state: (T) -> LiveCallState): Slots<T> {
        val at = listOf(LiveCallState.RINGING, LiveCallState.ACTIVE, LiveCallState.DIALING)
            .map { s -> live.indexOfFirst { state(it) == s } }.firstOrNull { it >= 0 } ?: if (live.isEmpty()) -1 else 0
        val primary = live.getOrNull(at)
        val others = live.filterIndexed { i, _ -> i != at }
        val held = others.filter { state(it) == LiveCallState.HOLDING }
        val current = others.firstOrNull { state(it) == LiveCallState.ACTIVE } ?: held.firstOrNull()
        return Slots(primary, current, held, primary != null && state(primary) == LiveCallState.RINGING && current != null)
    }

    /** Picture-in-picture only for a call that's going, never while one rings or asks for a SIM. */
    fun <T> pipAllowed(live: List<T>, state: (T) -> LiveCallState, askingForSim: (T) -> Boolean): Boolean =
        live.isNotEmpty() && live.none { state(it) == LiveCallState.RINGING || askingForSim(it) }
}
