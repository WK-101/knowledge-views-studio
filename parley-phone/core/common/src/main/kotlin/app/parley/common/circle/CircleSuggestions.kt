package app.parley.common.circle

/**
 * "Suggested for your Circle": the contacts you call most who aren't in your Circle yet, each with the rhythm
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

    /**
     * Whether the Circle's section in Favourites or Contacts (used while the Circle tab is hidden) offers
     * suggestions. The Circle is opt-in, so an empty one shows nothing there: its suggestions would only repeat
     * Frequent, just below. The Circle tab itself still offers them while it's empty.
     */
    fun offerInSection(members: Int, suggestions: Int, dismissed: Boolean, searching: Boolean): Boolean =
        !searching && members > 0 && suggestions > 0 && !dismissed

    fun daysFor(c: Candidate): Int = c.suggestedDays?.coerceIn(NaturalRhythm.MIN_DAYS, NaturalRhythm.MAX_DAYS) ?: DEFAULT_DAYS
}
