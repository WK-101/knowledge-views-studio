package app.parley.common.calls

import app.parley.common.CallEntry
import app.parley.common.CallType

/**
 * The "who called" chips of Recents: **Unknown** (numbers that aren't a contact or a private contact, and hidden
 * numbers) and **Contacts**, beside the chips by call type; and which chip Recents may open on next time.
 */
object RecentsCallers {
    enum class Who { UNKNOWN, CONTACTS }

    /** Whether a call from someone who [isContact] (or a [hidden] number) belongs under [who]. */
    fun matches(who: Who, isContact: Boolean, hidden: Boolean): Boolean = when (who) {
        Who.CONTACTS -> isContact && !hidden
        Who.UNKNOWN -> !isContact || hidden
    }

    /** Calls that came in (answered, missed, declined, blocked or left a voicemail). */
    private val INCOMING = setOf(CallType.INCOMING, CallType.MISSED, CallType.REJECTED, CallType.BLOCKED, CallType.VOICEMAIL, CallType.ANSWERED_EXTERNALLY)

    /**
     * How many unknown callers called since [since] (the start of today): each unknown number once, and each call from
     * a hidden number on its own (they can't be told apart). [key] gives a number's line key; [isContact] says whether
     * a call is from a contact.
     */
    fun unknownCallersSince(calls: List<CallEntry>, since: Long, key: (String) -> String, isContact: (CallEntry) -> Boolean): Int {
        val numbers = HashSet<String>()
        var hidden = 0
        for (c in calls) {
            if (c.date < since || c.type !in INCOMING) continue
            val k = if (c.presentationHidden || c.number.isBlank()) "" else key(c.number)
            if (k.isEmpty()) {
                hidden++
            } else if (!isContact(c)) {
                numbers += k
            }
        }
        return numbers.size + hidden
    }

    /**
     * The chip (by name) Recents opens on: the one used last when "Remember the filter" is on, except Blocked and
     * Voicemail, which are for a look now and then (opening on them would look like the calls had gone). Anything
     * else opens on All.
     */
    fun restored(remember: Boolean, last: String?, all: String, transient: Set<String>): String =
        if (remember && !last.isNullOrBlank() && last !in transient) last else all
}
