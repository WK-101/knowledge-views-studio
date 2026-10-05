package app.parley.common.people

import java.util.Locale

/**
 * A contact's languages (vCard `LANG`, RFC 9554 `LANGUAGE`), each stored as a BCP 47 tag ("es", "pt-BR") so other apps
 * and cards can read it, and shown as the language's name in the user's language. A contact speaks several, in order:
 * the first is the one to use with them ("Russian, English"), one address-book row each.
 */
object Languages {
    private val TAG = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

    /**
     * What to store for [typed]: a tag as typed is tidied ("PT-br" → "pt-BR"); a language's name in English or in
     * [userLocale] ("Spanish", "español") becomes its tag; anything else is kept as typed.
     */
    fun toStored(typed: String, userLocale: Locale = Locale.getDefault()): String {
        val t = typed.trim()
        if (t.isEmpty()) return ""
        if (TAG.matches(t)) {
            val l = Locale.forLanguageTag(t)
            if (l.language.isNotEmpty() && (l.getDisplayLanguage(Locale.ENGLISH) != l.language || t.length == 2)) return l.toLanguageTag()
        }
        val lower = t.lowercase(userLocale)
        val match = Locale.getISOLanguages().asSequence().map { Locale.forLanguageTag(it) }.firstOrNull { l ->
            l.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ENGLISH) == lower ||
                l.getDisplayLanguage(userLocale).lowercase(userLocale) == lower ||
                l.getDisplayLanguage(l).lowercase(l) == lower
        }
        return match?.toLanguageTag() ?: t
    }

    /** How [stored] is shown: the language's name ("Portuguese (Brazil)") for a tag, else the text as stored. */
    fun display(stored: String, userLocale: Locale = Locale.getDefault()): String {
        val s = stored.trim()
        if (!TAG.matches(s)) return s
        val l = Locale.forLanguageTag(s)
        val name = l.getDisplayName(userLocale)
        return if (l.language.isEmpty() || name.isBlank() || name.equals(l.language, ignoreCase = true)) s
        else name.replaceFirstChar { it.titlecase(userLocale) }
    }

    /** Whether [stored] is a language tag ("ru", "pt-BR") rather than a name kept as typed. */
    fun isTag(stored: String): Boolean = TAG.matches(stored.trim())

    /**
     * The languages typed in one field ("Russian, English"), in order, as typed: commas, semicolons and slashes part
     * them. Blank and repeated entries are left out.
     */
    fun split(typed: String): List<String> =
        typed.split(',', ';', '/', '、', '،').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }

    /** [languages] as one field's text ("Russian, English"). */
    fun join(languages: List<String>): String = languages.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    /** The stored form of each typed language ([toStored]), in order, without blanks or repeats. */
    fun toStoredList(typed: List<String>, userLocale: Locale = Locale.getDefault()): List<String> =
        typed.map { toStored(it, userLocale) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }

    /** How [stored] languages are shown together: "Russian, English" (the first is the preferred one). */
    fun displayList(stored: List<String>, userLocale: Locale = Locale.getDefault()): String =
        stored.map { display(it, userLocale) }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
}
