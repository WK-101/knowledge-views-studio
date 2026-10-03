package app.parley.common.people

import java.util.Locale

/**
 * A contact's language (vCard `LANG`, RFC 9554 `LANGUAGE`), stored as a BCP 47 tag ("es", "pt-BR") so other apps
 * and cards can read it, and shown as the language's name in the user's language.
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
}
