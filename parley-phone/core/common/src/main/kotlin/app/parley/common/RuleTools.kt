package app.parley.common

/**
 * Rule editor helpers (B16): normalise a pattern before saving, validate it, and preview what it will match
 * ("Will match +52 444…, 444…, 01 444…"). Pure and unit-tested.
 */
object RuleTools {

    data class Checked(
        /** What gets stored. */
        val pattern: String,
        /** Blocking problem; the rule can't be saved. */
        val error: String? = null,
        /** Non-blocking hints (foreign country code…). */
        val warnings: List<String> = emptyList(),
    )

    private val NUMBER_PUNCTUATION = setOf(' ', '-', '(', ')', '.', '/', ' ')

    /** Normalises and validates [pattern] for [type]. */
    fun check(pattern: String, type: RuleType, countryIso: String?): Checked {
        val raw = pattern.trim()
        if (raw.isEmpty() && type != RuleType.NOT_MY_REGION) return Checked(raw, "Enter something to match")
        return when (type) {
            RuleType.EXACT, RuleType.PREFIX, RuleType.WILDCARD -> checkNumber(raw, type, countryIso)
            RuleType.CALLER_NAME -> Checked(raw)
            RuleType.REGION -> {
                val codes = raw.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                val bad = codes.filter { it.length != 2 || CountryCodes.callingCode(it) == null }
                if (bad.isNotEmpty()) Checked(raw, "Unknown country code: ${bad.joinToString(", ")} (use two letters, e.g. GB)")
                else Checked(codes.distinct().joinToString(","))
            }
            RuleType.LINE_TYPE -> {
                val names = raw.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                val bad = names.filter { n -> LineType.entries.none { it.name == n } }
                if (bad.isNotEmpty()) Checked(raw, "Unknown line type: ${bad.joinToString(", ")}") else Checked(names.distinct().joinToString(","))
            }
            RuleType.LABEL -> if (raw.isBlank()) Checked(raw, "Pick a label") else Checked(LabelRefs.key(raw))
            RuleType.NOT_MY_REGION -> Checked("")
        }
    }

    private fun checkNumber(raw: String, type: RuleType, countryIso: String?): Checked {
        if (raw.any { it == '_' || it == '%' }) {
            return Checked(raw, "_ and % aren't wildcards here. Use * for any digits and ? for one digit (Pattern type).")
        }
        val allowedWild = type == RuleType.WILDCARD
        for ((i, c) in raw.withIndex()) {
            val ok = c.isDigit() || c in NUMBER_PUNCTUATION || (c == '+' && raw.substring(0, i).all { it in NUMBER_PUNCTUATION }) ||
                (allowedWild && (c == '*' || c == '?')) || (type == RuleType.EXACT && c.isLetter())
            if (!ok) {
                return if (!allowedWild && (c == '*' || c == '?')) Checked(raw, "Use the Pattern type for * and ?")
                else Checked(raw, "'$c' can't be part of a phone number")
            }
        }
        val warnings = ArrayList<String>()
        val stored = when (type) {
            RuleType.EXACT -> PhoneNumbers.toE164(raw, countryIso) ?: PhoneNumbers.clean(raw)
            RuleType.PREFIX -> canonicalPrefix(raw, countryIso)
            else -> canonicalWildcard(raw, countryIso).filter { it.isDigit() || it == '+' || it == '*' || it == '?' }
        }
        if (stored.isEmpty() || stored == "+") return Checked(raw, "Enter some digits")
        if (type == RuleType.WILDCARD && CallPolicy.wildcardRegex(stored) == null) return Checked(raw, "Not a valid pattern")
        if (type == RuleType.PREFIX && stored.length <= 2) warnings += "A very short prefix matches a lot of numbers"
        if (type == RuleType.WILDCARD && stored.trimStart('+').all { it == '*' || it == '?' }) warnings += "This matches almost every number"
        if (stored.startsWith("+") && countryIso != null) {
            val home = CountryCodes.callingCode(countryIso)
            val cc = CountryCodes.callingCodeOf(stored)
            if (cc != null && home != null && cc != home) {
                val regions = CountryCodes.regionsFor(cc).take(3).joinToString("/")
                warnings += "This is a +$cc number ($regions), not your country (+$home)"
            }
        }
        return Checked(stored, null, warnings)
    }

    /**
     * Canonical form of a prefix: digits and a leading '+'; an international dialling prefix ("00", "011")
     * becomes '+', and Mexico's legacy "+52 1" / "044" / "045" / "01" prefixes fold into today's form.
     */
    fun canonicalPrefix(pattern: String, countryIso: String?): String {
        var p = PhoneNumbers.clean(pattern).filter { it.isDigit() || it == '+' }
        if (!p.startsWith("+") && countryIso != null) {
            val intl = CountryCodes.internationalPrefixes(countryIso).firstOrNull { p.startsWith(it) && p.length > it.length }
            if (intl != null) p = "+" + p.substring(intl.length)
        }
        if (p.startsWith("+521") && p.length > 4) p = "+52" + p.substring(4)
        if (countryIso.equals("MX", ignoreCase = true) && !p.startsWith("+")) {
            p = when {
                (p.startsWith("044") || p.startsWith("045")) && p.length > 3 -> p.substring(3)
                p.startsWith("01") && p.length > 2 -> p.substring(2)
                else -> p
            }
        }
        return p
    }

    /** Same canonicalisation for the literal digits before the first wildcard. */
    fun canonicalWildcard(pattern: String, countryIso: String?): String {
        val p = pattern.trim()
        val cut = p.indexOfFirst { it == '*' || it == '?' }.let { if (it < 0) p.length else it }
        val head = p.substring(0, cut)
        val headDigits = head.filter { it.isDigit() || it == '+' }
        if (headDigits.isEmpty()) return p
        return canonicalPrefix(head, countryIso) + p.substring(cut)
    }

    /**
     * Example forms a number rule will match, most specific first.
     * "+52 444" in Mexico → ["+52 444…", "444…", "00 52 444…"].
     */
    fun preview(rule: BlockRule, countryIso: String?): List<String> {
        val iso = countryIso?.uppercase()
        val home = iso?.let { CountryCodes.callingCode(it) }
        val trunk = iso?.let { CountryCodes.trunkPrefix(it) }
        val intl = iso?.let { CountryCodes.internationalPrefixes(it).last() } ?: "00"
        return when (rule.type) {
            RuleType.EXACT -> {
                val e = PhoneNumbers.toE164(rule.pattern, iso) ?: return listOf(rule.pattern)
                val out = mutableListOf(e)
                if (home != null && e.startsWith("+$home")) {
                    val nat = e.substring(home.length + 1)
                    out += (trunk.orEmpty()) + nat
                }
                out += intl + e.substring(1)
                out.distinct()
            }
            RuleType.PREFIX, RuleType.WILDCARD -> {
                val canon = if (rule.type == RuleType.PREFIX) canonicalPrefix(rule.pattern, iso) else canonicalWildcard(rule.pattern, iso)
                val shown = canon.replace("*", "…").replace("?", "X")
                val suffix = if (rule.type == RuleType.PREFIX) "…" else ""
                val out = mutableListOf(shown + suffix)
                if (canon.startsWith("+")) {
                    if (home != null && canon.startsWith("+$home")) {
                        val nat = shown.substring(home.length + 1)
                        out += nat + suffix
                        if (trunk != null) out += trunk + nat + suffix
                    }
                    out += intl + " " + shown.substring(1) + suffix
                } else if (home != null) {
                    // A national prefix also matches the international form of the same numbers.
                    val nat = if (trunk != null && canon.startsWith(trunk)) shown.substring(trunk.length) else shown
                    out += "+$home $nat$suffix"
                }
                out.distinct()
            }
            else -> emptyList()
        }
    }

    /** Human description of what a rule does, for lists and previews. */
    fun describe(rule: BlockRule): String = when (rule.type) {
        RuleType.EXACT -> "This number"
        RuleType.PREFIX -> "Numbers starting with ${rule.pattern}"
        RuleType.WILDCARD -> "Numbers like ${rule.pattern}"
        RuleType.CALLER_NAME -> "Caller name contains \"${rule.pattern}\""
        RuleType.REGION -> "Numbers from ${rule.pattern.replace(",", ", ")}"
        RuleType.NOT_MY_REGION -> "Numbers from other countries"
        RuleType.LINE_TYPE -> rule.pattern.split(',').joinToString(", ") { lineTypeLabel(it) }
        RuleType.LABEL -> "Contacts in '${rule.label ?: rule.pattern}'"
    }
}
