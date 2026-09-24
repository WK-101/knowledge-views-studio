package app.parley.common

/**
 * Offline phone-number helpers. Deliberately dependency-free: good enough to match and block
 * numbers written as "+33 6 12 34 56 78", "0033612345678" and "06 12 34 56 78" as the same number.
 */
object PhoneNumbers {

    /** Keeps digits and a single leading '+'. Letters are converted with the T9 keypad. */
    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        for ((i, c) in raw.trim().withIndex()) {
            val digit = T9.asciiDigit(c)
            when {
                digit != null -> sb.append(digit)
                c == '+' && sb.isEmpty() && i <= 1 -> sb.append('+')
                c == '*' || c == '#' -> sb.append(c)
                c.isLetter() -> T9.digitFor(c)?.let { sb.append(it) }
            }
        }
        return sb.toString()
    }

    /** Only the digits of a number (Arabic-Indic and other decimal digits become 0–9). */
    fun digits(raw: String?): String = buildString { raw.orEmpty().forEach { c -> T9.asciiDigit(c)?.let { append(it) } } }

    /** True for USSD/MMI style codes such as *#06# or *100#. */
    fun isServiceCode(raw: String): Boolean {
        val s = raw.trim()
        return s.length >= 2 && (s.startsWith("*") || s.startsWith("#")) && s.endsWith("#")
    }

    /**
     * Best-effort E.164 conversion. Returns null when the number is too short or ambiguous
     * (short codes, service codes).
     */
    fun toE164(raw: String?, countryIso: String?): String? {
        val c = clean(raw)
        if (c.isEmpty() || c.contains('*') || c.contains('#')) return null
        if (c.startsWith("+")) return c.takeIf { it.length >= 8 }
        val iso = countryIso?.uppercase()
        val cc = iso?.let { CountryCodes.callingCode(it) }
        // International dialling prefix (00 in most places, 011 in NANP, 0011 in Australia, 810 in Russia…).
        val intl = iso?.let { CountryCodes.internationalPrefixes(it) } ?: listOf("00")
        intl.firstOrNull { c.startsWith(it) && c.length > it.length + 7 }?.let { return "+" + c.substring(it.length) }
        if (iso == null || cc == null) return null
        if (cc == "1") {
            return when {
                c.length == 10 -> "+1$c"
                c.length == 11 && c.startsWith("1") -> "+$c"
                else -> null
            }
        }
        if (c.length < 6) return null
        val trunk = CountryCodes.trunkPrefix(iso)
        return when {
            iso in CountryCodes.KEEPS_TRUNK_ZERO -> "+$cc$c"
            trunk != null && c.startsWith(trunk) -> "+$cc${c.substring(trunk.length)}"
            // Written as international digits without '+' (e.g. 33612345678 in France).
            c.startsWith(cc) && c.length - cc.length in 8..12 -> "+$c"
            iso in CountryCodes.NO_TRUNK_PREFIX -> "+$cc$c"
            // Number written without trunk prefix in a trunk-prefix country: assume national significant number.
            c.length >= 8 -> "+$cc$c"
            else -> null
        }
    }

    /** Key used for fast fuzzy equality: the last [MIN_MATCH] digits. */
    fun matchKey(raw: String?): String {
        val d = digits(raw)
        return if (d.length > MIN_MATCH) d.substring(d.length - MIN_MATCH) else d
    }

    /** Whether two numbers refer to the same line. */
    fun same(a: String?, b: String?, countryIso: String?): Boolean {
        val ea = toE164(a, countryIso)
        val eb = toE164(b, countryIso)
        if (ea != null && eb != null) return ea == eb
        val da = digits(a)
        val db = digits(b)
        if (da.isEmpty() || db.isEmpty()) return false
        if (da.length < 7 || db.length < 7) return da == db
        return matchKey(da) == matchKey(db)
    }

    /**
     * Neighbour spoofing: an unknown caller whose number differs from one of the user's own
     * numbers only in the last few digits.
     */
    fun looksLikeNeighbourSpoof(caller: String?, ownNumbers: List<String>, countryIso: String?, differingDigits: Int = 4): Boolean {
        val c = toE164(caller, countryIso) ?: return false
        return ownNumbers.any { own ->
            val o = toE164(own, countryIso) ?: return@any false
            o != c && o.length == c.length && o.length > differingDigits + 4 &&
                o.substring(0, o.length - differingDigits) == c.substring(0, c.length - differingDigits)
        }
    }

    const val MIN_MATCH = 9
}
