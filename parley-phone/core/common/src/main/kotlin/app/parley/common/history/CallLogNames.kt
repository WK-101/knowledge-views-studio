package app.parley.common.history

import app.parley.common.PhoneIdentity
import app.parley.common.catching

/**
 * Which name Parley may write into Android's call log (its "cached name", readable by every app with call-log access),
 * when it puts calls back there: an undo of a call delete, a restore while Parley's archive is off, a CSV import, a
 * private contact made visible again. A name goes in only when a contact everyone can see owns the number now
 * ([visible]: in the address book, not archived, not a private contact's). Otherwise the call goes in without one, so
 * an archived or private person's name is never handed back to other apps. Lookups that fail count as not visible.
 *
 * One instance per batch: each number is looked up once.
 */
class CallLogNames(private val visible: suspend (String) -> Boolean) {
    private val known = HashMap<String, Boolean>()

    /** [name] for a call with [number], or null when it may not be written. */
    suspend fun keep(number: String?, name: String?): String? {
        if (name.isNullOrBlank() || number.isNullOrBlank()) return null
        // A lookup that fails keeps the name out (fails closed).
        val shown = known.getOrPut(number) { catching { visible(number) }.getOrDefault(false) }
        return name.takeIf { shown }
    }

    companion object {
        /**
         * Of [numbers] (archived or private people's), the ones whose call-log names may be taken off: those no contact
         * everyone can see owns ([owned]). Without contacts access ([canRead] false) or when a lookup fails, nothing is
         * known about who owns a number, so it is left alone: a visible contact's name is never cleared for want of an
         * answer. Each number is looked up once.
         */
        suspend fun hidden(numbers: Collection<String>, canRead: Boolean, owned: suspend (String) -> Boolean): List<String> {
            if (!canRead) return emptyList()
            return numbers.filter { it.isNotBlank() }.distinct().filter { n -> catching { !owned(n) }.getOrDefault(false) }
        }

        /**
         * The call-log rows ([rows]: id, number, whether a name is cached) whose cached name must go: the call is with
         * one of [hidden] (archived or private people's numbers that no visible contact owns), by caller-ID rules.
         */
        fun rowsToClear(rows: List<Triple<Long, String?, Boolean>>, hidden: Collection<String>, region: String?): List<Long> {
            if (hidden.isEmpty()) return emptyList()
            val set = PhoneIdentity.LineSet(hidden, region)
            return rows.filter { (_, number, named) -> named && !number.isNullOrBlank() && number in set }.map { it.first }
        }
    }
}
