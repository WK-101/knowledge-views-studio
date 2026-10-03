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
 *   otherwise the last [MIN_MATCH] digits (all digits for short numbers).
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

    /**
     * E.164 form ("+33612345678"), or null for short codes, service codes, emergency numbers, sender names and numbers
     * too ambiguous to convert. libphonenumber reads it ([NumberText.toE164]); an offline heuristic covers the rest.
     */
    fun e164(raw: String?, region: String?): String? = NumberText.toE164(raw, region)

    /**
     * What [e164] answered before libphonenumber read every number, when that differs (an Argentine "15" mobile, a
     * country missing from the old table). Keys stored by older versions may hold it, so lookups try it too.
     */
    internal fun previousE164(raw: String?, region: String?): String? =
        PhoneNumbers.heuristicE164(raw, region)?.takeIf { it != e164(raw, region) }

    /** Only the digits of a number (other scripts' digits become 0–9). For display rules and digit search. */
    fun digits(raw: String?): String = PhoneNumbers.digits(raw)

    /** The dialable form: digits, a leading '+', '*' and '#', with letters read from the keypad ("1-800-FLOWERS"). */
    fun clean(raw: String?): String = PhoneNumbers.clean(raw)

    /** True for USSD/MMI style codes such as *#06# or *100#: dialled, never matched, saved or messaged. */
    fun isServiceCode(raw: String): Boolean = PhoneNumbers.isServiceCode(raw)

    /** The parts of a forwarded caller ID ("A&B": the caller and the forwarding line); a plain number gives itself. */
    fun forwardedParts(raw: String): List<String> = PhoneNumbers.forwardedParts(raw)

    /** One E.164 form for legacy spellings of the same line (Mexico's old mobile "1" after +52). */
    fun canonicalE164(e164: String): String = PhoneNumbers.canonicalE164(e164)

    /** A caller whose number differs from one of [ownNumbers] only in the last [differingDigits] digits. */
    fun looksLikeNeighbourSpoof(caller: String?, ownNumbers: List<String>, region: String?, differingDigits: Int = 4): Boolean =
        PhoneNumbers.looksLikeNeighbourSpoof(caller, ownNumbers, region, differingDigits)

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

    /**
     * The digits-only form of [key] ("~" plus the last digits), whether or not an E.164 form exists: for reading
     * records keyed that way before E.164 keys.
     */
    fun fallbackKey(raw: String?): String = PhoneNumbers.fallbackLineKey(raw)

    /**
     * Every key a row about [raw] may be stored under: [key] first, then the key an older version derived when it
     * differs ([previousE164]), then [legacyKey].
     */
    fun lookupKeys(raw: String?, region: String?): List<String> =
        listOfNotNull(key(raw, region), previousE164(raw, region), legacyKey(raw)).filter { it.isNotEmpty() }.distinct()

    /**
     * [key], then the key an older version stored the line under when it differs ([previousE164]: an Argentine "15"
     * mobile, a Slovak or Ivorian number). For stores keyed by [key] that were written before 5.4: read with every
     * form, so their rows still attach.
     */
    fun keyForms(raw: String?, region: String?): List<String> =
        listOfNotNull(key(raw, region).takeIf { it.isNotEmpty() }, previousE164(raw, region)).distinct()

    /** [exactKey], then the older E.164 form when it differs ([previousE164]), as [keyForms] does for [key]. */
    fun exactKeyForms(raw: String?, region: String?): List<String> =
        listOfNotNull(exactKey(raw, region), previousE164(raw, region)).distinct()

    /** Whether a stored key (current or legacy) belongs to [raw]'s line, with [same]'s rules. */
    fun matchesStored(stored: String, raw: String?, region: String?): Boolean = raw in KeySet(listOf(stored), region)

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
     * "Is this one of these numbers?" for filters that delete what is *not* in the set ("Clear unknown numbers"): the
     * caller-ID rules of [LineSet], and also any number sharing the last digits ([portableKey]) with one of them. A
     * contact saved in another country's national format ("0171 1234567" on a French phone) reads as the wrong E.164
     * form, yet its calls must still count as known. Errs on the side of keeping.
     */
    class KnownSet(numbers: Iterable<String?>, region: String?) {
        private val list = numbers.toList()
        private val lines = LineSet(list, region)
        private val portable = list.mapNotNullTo(HashSet()) { portableKey(it) }
        val isEmpty: Boolean get() = lines.isEmpty
        operator fun contains(raw: String?): Boolean = raw in lines || portableKey(raw)?.let { it in portable } == true
    }

    /**
     * Stored keys ([key], or the older [legacyKey]) answering "is this number one of them?" with [same]'s rules. A key
     * is never read back as a number: "~k612345678" has letters that would dial as digits.
     */
    class KeySet(keys: Iterable<String>, private val region: String?) {
        private val e164 = HashSet<String>()
        private val looseOfE164 = HashSet<String>()
        private val fallback = HashSet<String>()
        private val legacy = HashSet<String>()

        init {
            for (k in keys) when {
                k.startsWith("+") -> {
                    e164 += k
                    looseOf(k)?.let { looseOfE164 += it }
                }
                k.startsWith("~") -> fallback += k.substring(1)
                isLegacyKey(k) -> legacy += k
            }
        }

        operator fun contains(raw: String?): Boolean {
            val loose = looseOf(raw) ?: return false
            if (loose in fallback || legacyKey(raw) in legacy) return true
            val e = e164(raw, region)
            if (e == null) return loose in looseOfE164
            return e in e164 || previousE164(raw, region)?.let { it in e164 } == true
        }
    }

    /** "d" plus every digit of a short number, "k" plus the last digits of a longer one (the `~` part of [key]). */
    private fun looseOf(raw: String?): String? {
        val d = PhoneNumbers.digits(raw)
        if (d.isEmpty()) return null
        return if (d.length < 7) "d$d" else "k" + PhoneNumbers.matchKey(d)
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
    }

    const val PORTABLE_MIN_DIGITS = 7

    /** How many trailing digits [same] compares when a side has no E.164 form. */
    const val MIN_MATCH = PhoneNumbers.MIN_MATCH
}
