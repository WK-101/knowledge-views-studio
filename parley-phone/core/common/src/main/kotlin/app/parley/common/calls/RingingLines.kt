package app.parley.common.calls

/** What a line under the caller is for: their order while a call rings. */
enum class LineGroup { SAFETY, WHO, AGENDA }

/**
 * The lines under the caller's name that compete for room while a call rings, in the order they are kept. The name,
 * its label-and-number line, the tags and the status pill always show; these come after.
 */
enum class CallerLine(val group: LineGroup) {
    /** "This number never calls you". */
    NEVER_CALLS_YOU(LineGroup.SAFETY),

    /** The fresh-number warning. */
    FRESH_NUMBER(LineGroup.SAFETY),

    /** "Looks like a sales line (your calls)". */
    REPUTATION(LineGroup.SAFETY),

    /** "Rang through: called twice in 3 min": why screening let it ring. */
    RANG_THROUGH(LineGroup.SAFETY),

    /** The name the network sent, when it differs from the saved one. */
    NETWORK_NAME(LineGroup.WHO),

    /** Their name in their own language. */
    NATIVE_NAME(LineGroup.WHO),

    /** The subject they sent with the call. */
    SUBJECT(LineGroup.WHO),

    /** "You said you'd call at 15:30". */
    PROMISED(LineGroup.WHO),

    /** "Birthday today". */
    OCCASION(LineGroup.WHO),

    /** What Parley remembers about a number that isn't saved. */
    NUMBER_MEMORY(LineGroup.WHO),

    /** Job and company. */
    SUBTITLE(LineGroup.WHO),

    /** "she/her". */
    PRONOUNS(LineGroup.WHO),

    /** Who is this, the pinned note and the last call. */
    CALLER_CARD(LineGroup.WHO),

    /** To talk about. */
    AGENDA(LineGroup.AGENDA),
}

/**
 * The incoming caller header: while a call rings, at most [MAX_RINGING] of the lines under the name, safety first, then
 * who it is, then what to talk about, so the answer controls keep their room on a small phone. Once answered (or for a
 * call you make) every line shows. Pure: the call screen says which lines it has and draws only the ones returned.
 */
object RingingLines {
    const val MAX_RINGING = 3

    /** The lines of [present] to draw: all of them unless [ringing], else the first [MAX_RINGING] in [CallerLine] order. */
    fun shown(present: Set<CallerLine>, ringing: Boolean): Set<CallerLine> =
        if (!ringing) present else CallerLine.entries.filter { it in present }.take(MAX_RINGING).toSet()
}
