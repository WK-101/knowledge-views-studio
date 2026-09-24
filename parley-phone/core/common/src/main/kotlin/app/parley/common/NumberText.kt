package app.parley.common

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber
import java.util.Locale

/**
 * Phone numbers in text and in international form, with libphonenumber's bundled metadata (no network).
 *
 * The region hint is the SIM country (then the network country, then the locale), so a national number such as the
 * Pakistani "0300 1234567" becomes +92 300 1234567, not a number in whichever country its digits happen to start
 * with. A number written with "+" or an international prefix keeps its own country.
 */
object NumberText {
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    data class Found(
        /** The number as written in the text. */
        val raw: String,
        /** International form, or null when it isn't a complete number (short codes). */
        val e164: String?,
        /** Where it is in the text. */
        val range: IntRange,
    )

    /**
     * International form (+CC…) of [raw], or null for short codes, service codes and anything that can't be a phone
     * number. [region] is a two-letter country code used for numbers written without a country code.
     */
    fun toE164(raw: String?, region: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = PhoneNumbers.clean(raw)
        if (cleaned.isEmpty() || cleaned.contains('*') || cleaned.contains('#')) return null
        val parsed = parse(cleaned, region)
        if (parsed != null && util.isPossibleNumber(parsed)) return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        return PhoneNumbers.toE164(cleaned, region)
    }

    /** International digits without "+" ("923001234567"), as messenger links want them. */
    fun e164Digits(e164: String): String = e164.removePrefix("+")

    /** Region of a number ("PK"), or null. */
    fun regionOf(e164: String): String? = parse(e164, null)?.let { util.getRegionCodeForNumber(it) }?.takeIf { it != "ZZ" }

    /** Human-friendly international form ("+92 300 1234567"). */
    fun formatInternational(e164: String): String =
        parse(e164, null)?.let { util.format(it, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL) } ?: e164

    /**
     * Every phone number in [text], in order, without duplicates. Finds "+1 555-123-4567" as one number (not
     * "+1" and "555…"), national numbers for [region], and numbers inside sentences. When nothing is found and the
     * whole text is a short code ("112", "*100#"), that is returned without an international form.
     * With [distinct] false, a number written twice (even in two forms) is returned each time (M11 marks repeats).
     */
    fun find(text: String, region: String?, distinct: Boolean = true): List<Found> {
        val out = ArrayList<Found>()
        val seen = HashSet<String>()
        if (text.isBlank()) return out
        val hint = region?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 } ?: UNKNOWN
        try {
            for (m in util.findNumbers(text, hint, PhoneNumberUtil.Leniency.POSSIBLE, Long.MAX_VALUE)) {
                val e164 = util.format(m.number(), PhoneNumberUtil.PhoneNumberFormat.E164)
                if (seen.add(e164) || !distinct) out += Found(m.rawString(), e164, m.start() until m.end())
            }
        } catch (_: RuntimeException) {
            // Malformed input: fall through to the short-code check.
        }
        if (out.isEmpty()) {
            val t = text.trim()
            val cleaned = PhoneNumbers.clean(t)
            val digitCount = cleaned.count { it in '0'..'9' }
            val looksLikeNumber = t.all { it.isDigit() || it in "+*#-()/. " }
            if (looksLikeNumber && digitCount >= 2 && digitCount <= 15) {
                val start = text.indexOf(t)
                out += Found(t, toE164(t, region), start until start + t.length)
            }
        }
        return out
    }

    /** Whether [e164] is a valid number for its country (not just a plausible length). */
    fun isValid(e164: String?): Boolean = e164 != null && parse(e164, null)?.let { util.isValidNumber(it) } == true

    /** A country for the country picker (F19): "FR", 33, "France". */
    data class Region(val code: String, val callingCode: Int, val name: String)

    /** Every region libphonenumber knows, named in [locale] and sorted by name. */
    fun regions(locale: Locale = Locale.getDefault()): List<Region> =
        util.supportedRegions.map { code -> Region(code, util.getCountryCodeForRegion(code), Locale("", code).getDisplayCountry(locale).ifBlank { code }) }
            .sortedBy { it.name.lowercase(locale) }

    /** Picker search: by name ("fra"), by code ("FR") or by calling code ("+33", "33"). */
    fun searchRegions(regions: List<Region>, query: String): List<Region> {
        val q = query.trim()
        if (q.isEmpty()) return regions
        val digits = q.removePrefix("+").takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        return regions.filter { r ->
            if (digits != null) r.callingCode.toString().startsWith(digits)
            else r.name.contains(q, ignoreCase = true) || r.code.equals(q, ignoreCase = true)
        }
    }

    private fun parse(number: String, region: String?): Phonenumber.PhoneNumber? = try {
        util.parse(number, region?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 } ?: UNKNOWN)
    } catch (_: NumberParseException) {
        null
    }

    private const val UNKNOWN = "ZZ"
}
