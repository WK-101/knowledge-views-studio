package app.parley.common

/**
 * The one answer to "is this the same phone line?" and "what do I store it under?".
 *
 * Every map, set, stored row and comparison of phone numbers goes through here, so the whole app agrees on which
 * numbers are one line. The rules, from strictest to loosest:
 *
 * - [key]: the key to store and to build maps with. The E.164 form whenever it can be derived (a national number is
 *   read with [region], ideally the country of the SIM that handled the call), otherwise the digits with a `~`
 *   prefix, so numbers from different countries that share their last digits never collide.
 * - [same] / [LineSet] / [LineMap]: "same line" for matching a call to a contact. E.164 when both sides have one,
 *   otherwise the last [PhoneNumbers.MIN_MATCH] digits (all digits for short numbers).
 * - [sameExact] / [exactKey]: for deletions and history grouping, which must never touch anyone else: E.164 when
 *   derivable, otherwise every digit.
 * - [portableKey]: a region-free fingerprint (the last digits) for data that travels between phones (backup files)
 *   and for "possible duplicate" candidates, where the other phone's country isn't known. Never a local stored key.
 *
 * Rows stored by older versions used the last 9 digits ([legacyKey]). [lookupKeys] returns both forms, so those rows
 * stay readable until [app.parley.common.PhoneKeyMigration] has moved them to [key].
 */
object PhoneIdentity {
    /** The stored key of a line (E.164, or `~` plus the digits). Empty for a blank or digit-less number. */
    fun key(raw: String?, region: String?): String = PhoneNumbers.lineKey(raw, region)

    /** E.164 form ("+33612345678"), or null for short codes, service codes and numbers too ambiguous to convert. */
    fun e164(raw: String?, region: String?): String? = PhoneNumbers.toE164(raw, region)

    /** Whether two numbers are the same line (see the class comment). */
    fun same(a: String?, b: String?, region: String?): Boolean = PhoneNumbers.same(a, b, region)

    /** Whether two numbers are exactly the same line: E.164 when both have one, otherwise every digit. */
    fun sameExact(a: String?, b: String?, region: String?): Boolean = PhoneNumbers.sameExact(a, b, region)

    /**
     * Grouping key with [sameExact]'s rules: E.164, or `#` plus every digit (service codes keep their `*` and `#`).
     * Null for a blank number.
     */
    fun exactKey(raw: String?, region: String?): String? {
        e164(raw, region)?.let { return it }
        val digits = PhoneNumbers.clean(raw).removePrefix("+")
        return if (digits.isEmpty()) null else "#$digits"
    }

    /**
     * Region-free fingerprint (the last digits), or null for numbers shorter than [PORTABLE_MIN_DIGITS] digits,
     * which are too short to mean the same line on another phone. For backup files and duplicate candidates only.
     */
    fun portableKey(raw: String?): String? = PhoneNumbers.matchKey(raw).takeIf { it.length >= PORTABLE_MIN_DIGITS }

    /** The key rows were stored under before [key] existed (the last 9 digits). For reading and migrating them. */
    fun legacyKey(raw: String?): String = PhoneNumbers.matchKey(raw)

    /** True for a stored key written by an older version ([legacyKey]: digits only), not by [key]. */
    fun isLegacyKey(stored: String): Boolean = stored.isNotEmpty() && stored.all { it in '0'..'9' }

    /** Every key a row about [raw] may be stored under: [key] first, then [legacyKey]. */
    fun lookupKeys(raw: String?, region: String?): List<String> = listOf(key(raw, region), legacyKey(raw)).filter { it.isNotEmpty() }.distinct()

    /** Whether a stored key (current or legacy) belongs to [raw]. */
    fun matchesStored(stored: String, raw: String?, region: String?): Boolean =
        stored.isNotEmpty() && (stored == key(raw, region) || (isLegacyKey(stored) && stored == legacyKey(raw)))

    /**
     * Identity of a call row across the system call log, Parley's archive and backups: the number's last digits plus
     * the start time in whole seconds (restores and re-imports can lose milliseconds, and change the number's format).
     */
    fun callRowKey(raw: String?, dateMillis: Long): String = PhoneNumbers.matchKey(raw) + "|" + Math.floorDiv(dateMillis, 1000L)

    /** A set of numbers answering "is this one of them?" with [same]'s rules, without comparing every pair. */
    class LineSet(numbers: Iterable<String?>, region: String?) {
        private val inner = PhoneNumbers.LineSet(numbers, region)
        val isEmpty: Boolean get() = inner.isEmpty
        operator fun contains(raw: String?): Boolean = raw in inner
    }

    /**
     * Numbers to values, looked up with [same]'s rules (a contact's "06 12 34 56 78" finds the call from
     * "+33 6 12 34 56 78"). The first value put for a line wins, like `putIfAbsent`.
     */
    class LineMap<V>(private val region: String?) {
        private val byE164 = HashMap<String, V>()
        private val looseWithoutE164 = HashMap<String, V>()
        private val looseAll = HashMap<String, V>()

        val isEmpty: Boolean get() = looseAll.isEmpty()

        fun putIfAbsent(raw: String?, value: V) {
            val loose = looseOf(raw) ?: return
            looseAll.putIfAbsent(loose, value)
            val e = e164(raw, region)
            if (e != null) byE164.putIfAbsent(e, value) else looseWithoutE164.putIfAbsent(loose, value)
        }

        operator fun get(raw: String?): V? {
            val loose = looseOf(raw) ?: return null
            val e = e164(raw, region)
            return if (e != null) byE164[e] ?: looseWithoutE164[loose] else looseAll[loose]
        }

        operator fun contains(raw: String?): Boolean = get(raw) != null

        private fun looseOf(raw: String?): String? {
            val d = PhoneNumbers.digits(raw)
            if (d.isEmpty()) return null
            return if (d.length < 7) "d$d" else "k" + PhoneNumbers.matchKey(d)
        }
    }

    const val PORTABLE_MIN_DIGITS = 7
}
