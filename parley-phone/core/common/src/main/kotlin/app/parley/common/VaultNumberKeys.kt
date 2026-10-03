package app.parley.common

/**
 * What the vault fingerprints (HMACs) for a phone number, so caller ID can match without decrypting.
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
        PhoneIdentity.e164(number, countryIso)?.let { return listOf(E164_PREFIX + it) }
        return listOfNotNull(PhoneIdentity.legacyKey(number).takeIf { it.isNotEmpty() })
    }

    /** HMAC inputs for all numbers of one vault entry, without duplicates. */
    fun storedAll(numbers: List<String>, countryIso: String?): List<String> = numbers.flatMap { stored(it, countryIso) }.distinct()

    /**
     * [storedAll] plus the last-digits key of every number, as an extra fallback: when a national number was saved
     * with one region and the call arrives under another (roaming, a second SIM), the E.164 keys differ but the
     * non-exact lookup still finds the entry. Exact lookups never use these rows.
     */
    fun storedWithFallback(numbers: List<String>, countryIso: String?): List<String> =
        (storedAll(numbers, countryIso) + numbers.mapNotNull { n -> PhoneIdentity.legacyKey(n).takeIf { it.isNotEmpty() } }).distinct()

    /**
     * [storedWithFallback] plus, for each number, the E.164 form an older version derived when it differs
     * ([PhoneIdentity.previousE164]). Re-keying writes these, so an entry saved before libphonenumber read every
     * number is found under its new form and, until the next re-keying, its old one.
     */
    fun storedWithPrevious(numbers: List<String>, countryIso: String?): List<String> =
        (storedWithFallback(numbers, countryIso) + numbers.mapNotNull { n -> PhoneIdentity.previousE164(n, countryIso)?.let { E164_PREFIX + it } }).distinct()

    /**
     * Which numbers a vault lookup could find, decided without the Keystore: the call-log sweep looks up only those.
     *
     * [entries] are each private contact's numbers with the region they were saved with (null when unknown: the
     * phone's [region] was used). Every HMAC input the vault may hold for them ([storedWithPrevious], under the saved
     * region and the phone's) is kept in plain form here, in memory only; [mayMatch] is true exactly when one of
     * [lookup]'s inputs for a number is among them, so a call the lookup would match always passes. Matching on the
     * same identity keys as caller ID (E.164, the older E.164 form, the last digits) rather than on the last digits
     * alone matters where a national form has extra digits inside them (an Argentine "15" mobile).
     */
    class Prefilter(entries: Iterable<Pair<List<String>, String?>>, private val region: String?) {
        private val inputs = HashSet<String>()

        init {
            for ((numbers, saved) in entries) {
                for (r in listOf(saved ?: region, region).distinct()) inputs += storedWithPrevious(numbers, r)
            }
        }

        val isEmpty: Boolean get() = inputs.isEmpty()

        fun mayMatch(number: String?): Boolean = lookup(number, region).any { it in inputs }
    }

    /**
     * HMAC inputs to try for a caller, best first. [countryIso] is the country of the SIM that took the call when
     * known. [exact] leaves out the last-digits fallback entirely.
     */
    fun lookup(number: String?, countryIso: String?, exact: Boolean = false): List<String> {
        val e164 = PhoneIdentity.e164(number, countryIso)
        // Entries sealed before libphonenumber read every number may sit under the older E.164 form.
        val previous = PhoneIdentity.previousE164(number, countryIso)?.let { E164_PREFIX + it }
        val suffix = PhoneIdentity.legacyKey(number).takeIf { it.isNotEmpty() }
        return when {
            e164 != null && exact -> listOfNotNull(E164_PREFIX + e164, previous)
            // The E.164 row first; the suffix rows are the fallback (see storedWithFallback).
            e164 != null -> listOfNotNull(E164_PREFIX + e164, previous, suffix)
            exact -> emptyList()
            else -> listOfNotNull(suffix)
        }
    }

    /**
     * Which of several vault entries sharing a number wins: never an expired one, then the most recently
     * updated, then the newest created, then the highest id (deterministic).
     */
    data class Candidate(val id: Long, val updatedAt: Long, val createdAt: Long, val expiresAt: Long?)

    fun winner(candidates: List<Candidate>, now: Long): Candidate? =
        candidates.filter { it.expiresAt == null || it.expiresAt > now }
            .maxWithOrNull(compareBy<Candidate>({ it.updatedAt }, { it.createdAt }, { it.id }))
}
