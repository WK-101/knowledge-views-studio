package app.parley.common

/**
 * F7: what the vault fingerprints (HMACs) for a phone number, so caller ID can match without decrypting.
 *
 * Numbers are keyed by their E.164 form, prefixed with [E164_PREFIX] so these rows never collide with the older
 * rows keyed by the last digits. The last-digits key is only stored for a number whose E.164 form can't be derived
 * (a short code, a national number with no known country); lookups use it only as a fallback, and exact lookups
 * (the private-name provider) never do.
 */
object VaultNumberKeys {
    const val E164_PREFIX = "e164:"

    /** HMAC inputs stored for one vault number ([countryIso]: the region the number was entered in). */
    fun stored(number: String?, countryIso: String?): List<String> {
        PhoneNumbers.toE164(number, countryIso)?.let { return listOf(E164_PREFIX + it) }
        return listOfNotNull(PhoneNumbers.matchKey(number).takeIf { it.isNotEmpty() })
    }

    /** HMAC inputs for all numbers of one vault entry, without duplicates. */
    fun storedAll(numbers: List<String>, countryIso: String?): List<String> = numbers.flatMap { stored(it, countryIso) }.distinct()

    /**
     * HMAC inputs to try for a caller, best first. [countryIso] is the country of the SIM that took the call when
     * known. [exact] leaves out the last-digits fallback entirely.
     */
    fun lookup(number: String?, countryIso: String?, exact: Boolean = false): List<String> {
        val e164 = PhoneNumbers.toE164(number, countryIso)
        val suffix = PhoneNumbers.matchKey(number).takeIf { it.isNotEmpty() }
        return when {
            e164 != null && exact -> listOf(E164_PREFIX + e164)
            // The suffix rows left after migration belong only to numbers stored without an E.164 form.
            e164 != null -> listOfNotNull(E164_PREFIX + e164, suffix)
            exact -> emptyList()
            else -> listOfNotNull(suffix)
        }
    }

    /**
     * F15: which of several vault entries sharing a number wins: never an expired one, then the most recently
     * updated, then the newest created, then the highest id (deterministic).
     */
    data class Candidate(val id: Long, val updatedAt: Long, val createdAt: Long, val expiresAt: Long?)

    fun winner(candidates: List<Candidate>, now: Long): Candidate? =
        candidates.filter { it.expiresAt == null || it.expiresAt > now }
            .maxWithOrNull(compareBy<Candidate>({ it.updatedAt }, { it.createdAt }, { it.id }))
}
