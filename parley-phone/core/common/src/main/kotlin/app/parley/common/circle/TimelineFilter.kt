package app.parley.common.circle

import app.parley.common.CallType
import app.parley.common.PhoneIdentity
import app.parley.common.TextSearch

/** The kinds of entries the full timeline filters by. */
enum class TimelineKind { CALL, MISSED, LOGGED, NOTE, DATE }

/** The full-screen timeline's search and type filter. */
data class TimelineFilter(val query: String = "", val kinds: Set<TimelineKind> = emptySet()) {
    val isEmpty: Boolean get() = query.isBlank() && kinds.isEmpty()

    /** Missed (and rejected) calls count as [TimelineKind.MISSED], other calls as [TimelineKind.CALL]. */
    fun kindOf(e: TimelineEntry): TimelineKind = when (e) {
        is TimelineEntry.Call -> if (e.call.type == CallType.MISSED || e.call.type == CallType.REJECTED) TimelineKind.MISSED else TimelineKind.CALL
        is TimelineEntry.Logged -> TimelineKind.LOGGED
        is TimelineEntry.Note -> TimelineKind.NOTE
        is TimelineEntry.Date -> TimelineKind.DATE
    }

    /**
     * Entries of the chosen kinds (all when none is chosen) whose text has every query word (accents and case
     * ignored), or whose number holds the query's digits. [text] gives an entry's words as shown (type, note…).
     */
    fun apply(entries: List<TimelineEntry>, text: (TimelineEntry) -> String): List<TimelineEntry> {
        val words = TextSearch.normalize(query.trim()).split(' ').filter { it.isNotEmpty() }
        val digits = PhoneIdentity.digits(query)
        val numeric = digits.length >= 2 && digits.length * 2 >= query.count { !it.isWhitespace() }
        return entries.filter { e ->
            if (kinds.isNotEmpty() && kindOf(e) !in kinds) return@filter false
            if (words.isEmpty()) return@filter true
            if (numeric && e is TimelineEntry.Call && PhoneIdentity.digits(e.call.number).contains(digits)) return@filter true
            val t = TextSearch.normalize(text(e) + " " + ownText(e))
            words.all { t.contains(it) }
        }
    }

    private fun ownText(e: TimelineEntry): String = when (e) {
        is TimelineEntry.Logged -> e.note.orEmpty()
        is TimelineEntry.Note -> e.text
        is TimelineEntry.Date -> e.label.orEmpty()
        is TimelineEntry.Call -> e.call.number
    }
}
