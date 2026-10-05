package app.parley.common.people

import java.util.Locale

/**
 * The countries a person is a citizen of ([app.parley.common.record.Mime.CITIZENSHIP]), one ISO 3166 code each ("PT"),
 * several for dual citizenship, shown by the country's name in the user's language. Personal data: the contact page's
 * More section, search, filters, cards and backups, never a call screen or the lock screen.
 */
object Citizenship {
    private val CODES: Set<String> by lazy { Locale.getISOCountries().toSet() }

    /**
     * The code to store for [raw]: an ISO code as typed ("pt" → "PT"), or a country's name in English, the phone's
     * language or its own ("Portugal", "Deutschland" → "DE"); null when it names no country.
     */
    fun toCode(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val up = t.uppercase(Locale.ROOT)
        if (up.length == 2 && up in CODES) return up
        val english = Countries.canonical(t)
        return byEnglishName[ContactFacets.key(english)]
    }

    private val byEnglishName: Map<String, String> by lazy {
        CODES.associateBy { ContactFacets.key(Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH)) }
    }

    /** The codes of [raw] values, in order, without unknown or repeated ones. */
    fun codes(raw: List<String>): List<String> = raw.mapNotNull(::toCode).distinct()

    /** The country's name for [code] in [locale] ("Portugal"); the code itself when unknown. */
    fun display(code: String, locale: Locale = Locale.getDefault()): String {
        val c = code.trim().uppercase(Locale.ROOT)
        if (c !in CODES) return code.trim()
        return Locale.Builder().setRegion(c).build().getDisplayCountry(locale).ifBlank { c }
    }

    /** "Portugal, Brazil": the countries of [codes] by name, in order. */
    fun displayList(codes: List<String>, locale: Locale = Locale.getDefault()): String =
        codes.map { display(it, locale) }.filter { it.isNotEmpty() }.distinct().joinToString(", ")

    /** The country's English name for [code], the value the Contacts filters group countries by ([Countries.canonical]). */
    fun facetName(code: String): String = Countries.canonical(code.trim())
}
