package app.parley.common.calls

import app.parley.common.CallEntry
import app.parley.common.CallType

/** P5: which calls "Clear call history" removes. */
enum class ClearScope {
    ALL,

    /** Numbers that aren't contacts (or private contacts), private numbers included. */
    UNKNOWN_NUMBERS,
    MISSED,

    /** Exactly what Recents shows now (its filters and search). */
    SHOWN,
}

/**
 * P5: picks the calls to clear. Private-contact calls never are: the vault's own ones (negative ids), and a private
 * contact's call still in the system log ([isPrivate]; the vault moves it out a little later, or never without
 * WRITE_CALL_LOG).
 */
object ClearHistory {
    /**
     * "Unknown numbers" needs the contacts: while they aren't loaded (or can't be read) every number would look
     * unknown, so that scope then picks nothing and isn't offered.
     */
    fun available(scope: ClearScope, contactsReady: Boolean): Boolean = scope != ClearScope.UNKNOWN_NUMBERS || contactsReady

    fun select(
        calls: List<CallEntry>, scope: ClearScope, isKnown: (String) -> Boolean, shownIds: Set<Long> = emptySet(),
        isPrivate: (String) -> Boolean = { false }, contactsReady: Boolean = true,
    ): List<CallEntry> {
        if (!available(scope, contactsReady)) return emptyList()
        return calls.filter { e ->
            e.id > 0 && (e.number.isBlank() || !isPrivate(e.number)) && when (scope) {
                ClearScope.ALL -> true
                ClearScope.UNKNOWN_NUMBERS -> e.presentationHidden || e.number.isBlank() || !isKnown(e.number)
                ClearScope.MISSED -> e.type == CallType.MISSED || e.type == CallType.REJECTED
                ClearScope.SHOWN -> e.id in shownIds
            }
        }
    }
}
