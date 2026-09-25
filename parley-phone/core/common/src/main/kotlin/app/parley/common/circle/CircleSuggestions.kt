package app.parley.common.circle

/**
 * R1: "Suggested from your calls": the contacts you call most who aren't in your Circle yet, each with the rhythm
 * your history suggests. One tap adds them; nothing is added on its own.
 */
object CircleSuggestions {
    const val MAX = 10

    /** Fewer calls than this in the last year isn't a pattern. */
    const val MIN_CALLS = 2

    /** Rhythm offered when the history is too thin to suggest one. */
    const val DEFAULT_DAYS = 30

    data class Candidate(val lookupKey: String, val calls: Int, val suggestedDays: Int?)

    /** The [max] most-called [candidates] not in [inCircle], most calls first (ties by key, so the list is stable). */
    fun pick(candidates: List<Candidate>, inCircle: Set<String>, max: Int = MAX): List<Candidate> =
        candidates.filter { it.lookupKey.isNotEmpty() && it.lookupKey !in inCircle && it.calls >= MIN_CALLS }
            .distinctBy { it.lookupKey }
            .sortedWith(compareByDescending<Candidate> { it.calls }.thenBy { it.lookupKey })
            .take(max)

    fun daysFor(c: Candidate): Int = c.suggestedDays?.coerceIn(NaturalRhythm.MIN_DAYS, NaturalRhythm.MAX_DAYS) ?: DEFAULT_DAYS
}
