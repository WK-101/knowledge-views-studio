package app.parley.common.calls

import app.parley.common.PhoneNumbers

/**
 * I3 "Check it's really them": end the call and dial the number saved for that person or organisation, since caller
 * ID can be faked but a saved number reaches the real one. This picks which saved numbers to offer.
 */
object VerifyCallBack {
    /** A saved number: whose it is and how it's labelled ("Mobile", "Fraud line"). */
    data class Saved(val name: String, val number: String, val label: String? = null, val organisation: Boolean = false)

    /**
     * The saved numbers to offer, each line once (the same number saved twice, or in another format, counts once), the
     * one the call came from first: it is the saved line the caller claimed, and calling it back reaches the real owner.
     */
    fun choices(saved: List<Saved>, caller: String?, countryIso: String? = null): List<Saved> {
        val seen = HashSet<String>()
        val unique = saved.filter { it.number.any(Char::isDigit) && seen.add(key(it.number, countryIso)) }
        val callerKey = caller?.takeIf { it.any(Char::isDigit) }?.let { key(it, countryIso) } ?: return unique
        return unique.sortedByDescending { key(it.number, countryIso) == callerKey }
    }

    /** Organisations first by name, then the rest; for a caller who claims to be one of them. */
    fun organisations(saved: List<Saved>, countryIso: String? = null): List<Saved> =
        choices(saved.filter { it.organisation }, null, countryIso).sortedBy { it.name.lowercase() }

    private fun key(number: String, countryIso: String?): String =
        PhoneNumbers.toE164(number, countryIso) ?: PhoneNumbers.digits(number)
}
