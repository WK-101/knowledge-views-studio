package app.parley.common.calls

/**
 * "Calls to Ana drop less on SIM 2": on a phone with two SIMs, calls with one person that keep dropping (or failing)
 * on one SIM and going well on the other. The suggestion sets the SIM Parley already remembers per number, and only
 * when the user taps it; once answered (either way) the same suggestion never comes back.
 */
object SimAdvice {
    /** The SIM to leave needs at least this many dropped or failed calls, ... */
    const val MIN_BAD = 3

    /** ... at least half its calls with the person, ... */
    private const val MIN_BAD_SHARE = 0.5

    /** ... and the SIM to suggest at least this many calls that went well, ... */
    const val MIN_GOOD = 3

    /** ... going wrong at most half as often. */
    private const val RATE_FACTOR = 2.0

    /** One call with the person on [simId] (the SIM's phone account). */
    data class SimCall(val simId: String, val facts: CallQualityFacts)

    /** Calls with the person on one SIM. */
    data class Tally(val good: Int, val bad: Int) {
        val calls: Int get() = good + bad
        val badRate: Double get() = if (calls == 0) 0.0 else bad.toDouble() / calls
    }

    data class Suggestion(
        /** The SIM to use for this person from now on. */
        val simId: String,
        /** The SIM their calls drop on. */
        val fromSimId: String,
        val from: Tally,
        val to: Tally,
    )

    /** It dropped, or it was placed and failed with an error (not busy, not cancelled). */
    fun isBad(f: CallQualityFacts): Boolean =
        f.drop != null || (!f.incoming && !f.connected && f.end in setOf(EndCode.ERROR, EndCode.OTHER, EndCode.UNKNOWN))

    /** It connected and ended normally. */
    fun isGood(f: CallQualityFacts): Boolean = f.connected && f.drop == null

    /**
     * The suggestion for one person, or null. [sims]: the phone's SIMs now (fewer than two: never). [current]: the SIM
     * remembered for their number now (null: Parley asks, or the phone's default). [answered]: SIMs already suggested
     * for them and answered, which are never suggested again.
     */
    fun suggest(calls: List<SimCall>, sims: Set<String>, current: String?, answered: Set<String> = emptySet()): Suggestion? {
        if (sims.size < 2) return null
        val tallies = calls.filter { it.simId in sims }.groupBy { it.simId }.mapValues { (_, list) ->
            Tally(good = list.count { isGood(it.facts) }, bad = list.count { isBad(it.facts) })
        }
        val worst = tallies.filter { (_, t) -> t.bad >= MIN_BAD && t.badRate >= MIN_BAD_SHARE }.maxByOrNull { (_, t) -> t.badRate } ?: return null
        val best = tallies.filter { (id, t) -> id != worst.key && t.good >= MIN_GOOD && t.badRate * RATE_FACTOR <= worst.value.badRate }
            .maxByOrNull { (_, t) -> t.good } ?: return null
        if (current == best.key || best.key in answered) return null
        return Suggestion(best.key, worst.key, worst.value, best.value)
    }
}
