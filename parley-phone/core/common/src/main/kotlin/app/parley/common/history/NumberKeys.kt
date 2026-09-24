package app.parley.common.history

import app.parley.common.PhoneNumbers

/**
 * Canonical keys for grouping call history.
 *
 * A number is keyed by its E.164 form whenever it can be derived, interpreting national numbers with the
 * SIM country (so "06 12 34 56 78", "+33 6 12 34 56 78" and "0033612345678" are one key in France: the
 * national trunk prefix is stripped). Numbers that can't be converted (short codes, service numbers) fall
 * back to their digits with a `#` prefix so they never collide with an E.164 key.
 */
object NumberKeys {
    /** Key for calls whose number is withheld, blank or unknown. */
    const val HIDDEN = "hidden"

    fun of(raw: String?, countryIso: String?): String {
        val e164 = PhoneNumbers.toE164(raw, countryIso)
        if (e164 != null) return e164
        val digits = PhoneNumbers.clean(raw).removePrefix("+")
        return if (digits.isEmpty()) HIDDEN else "#$digits"
    }

    /** True if [key] came from a real number (not [HIDDEN]). */
    fun isNumber(key: String): Boolean = key != HIDDEN

    /** The E.164 number behind [key], or null for fallback/hidden keys. */
    fun e164(key: String): String? = key.takeIf { it.startsWith("+") }

    /**
     * Identity of a call row across the system call log and Parley's archive: the last digits of the number
     * plus the call's start time in whole seconds (restores and re-imports can lose milliseconds).
     */
    fun dedupe(number: String?, dateMillis: Long): String = PhoneNumbers.matchKey(number) + "|" + Math.floorDiv(dateMillis, 1000L)
}
