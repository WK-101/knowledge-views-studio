package app.parley.common

/**
 * A second alphabet on the phone keypad. Latin letters are always on the keys; a layout adds one local alphabet
 * (shown as a second row of letters) and decides which key its letters are on.
 *
 * Each layout has its own table because the alphabets disagree: Ukrainian puts И and І on 4 but has no Ы, Bulgarian
 * keeps Ъ on 8, Belarusian puts Ў on 6. Hebrew groups the final letter forms (ך ם ן ף ץ) with their base letters.
 * Arabic ignores vowel marks (harakat) and tatweel; Arabic-Indic digits are handled by [T9.asciiDigit].
 *
 * [keys] are the letters printed on keys 2–9; [variants] are extra letters found on the same key but not printed
 * (letter variants such as Ё, Arabic hamza forms, Hebrew final forms).
 */
enum class KeypadLayout(
    val label: String,
    private val keys: List<String>,
    private val variants: List<String> = List(8) { "" },
) {
    LATIN("Latin only (ABC)", List(8) { "" }),
    RUSSIAN(
        "Russian (АБВГ)",
        listOf("абвг", "дежз", "ийкл", "мноп", "рсту", "фхцч", "шщъы", "ьэюя"),
        listOf("", "ё", "", "", "", "", "", ""),
    ),
    UKRAINIAN(
        "Ukrainian (АБВГҐ)",
        listOf("абвгґ", "деєжз", "иіїйкл", "мноп", "рсту", "фхцч", "шщ", "ьюя"),
    ),
    BELARUSIAN(
        "Belarusian (АБВГ)",
        listOf("абвг", "деёжз", "ійкл", "мноп", "рстуў", "фхцч", "шы", "ьэюя"),
    ),
    BULGARIAN(
        "Bulgarian (АБВГ)",
        listOf("абвг", "дежз", "ийкл", "мноп", "рсту", "фхцч", "шщъ", "ьюя"),
        listOf("", "", "ѝ", "", "", "", "", ""),
    ),
    SERBIAN(
        "Serbian (АБВГ)",
        listOf("абвг", "дђежз", "ијклљ", "мнњоп", "рстћу", "фхцчџ", "ш", ""),
    ),
    GREEK(
        "Greek (ΑΒΓ)",
        listOf("αβγ", "δεζ", "ηθι", "κλμ", "νξο", "πρσ", "τυφ", "χψω"),
        listOf("ά", "έ", "ήίϊΐ", "", "ό", "ς", "ύϋΰ", "ώ"),
    ),
    HEBREW(
        "Hebrew (אבג)",
        listOf("דהו", "אבג", "מנ", "יכל", "זחט", "רשת", "צק", "סעפ"),
        listOf("", "", "םן", "ך", "", "", "ץ", "ף"),
    ),
    ARABIC(
        "Arabic (ابت)",
        listOf("بتث", "ا", "سشصض", "دذرز", "جحخ", "نهوي", "فقكلم", "طظعغ"),
        listOf("ة", "أإآٱءى", "", "", "", "ؤئ", "", ""),
    ),
    ;

    /** Letter → keypad digit for this layout's alphabet (lower case). Latin is handled by [T9]. */
    val keyMap: Map<Char, Char> by lazy {
        val m = HashMap<Char, Char>()
        keys.forEachIndexed { i, g -> g.forEach { m[it] = '2' + i } }
        variants.forEachIndexed { i, g -> g.forEach { m[it] = '2' + i } }
        m
    }

    /** Letters printed under the Latin ones on key [digit] ("" when none). */
    fun lettersFor(digit: Char): String {
        if (digit !in '2'..'9') return ""
        val g = keys[digit - '2']
        return if (this == HEBREW || this == ARABIC) g else g.uppercase()
    }

    companion object {
        /** The layout matching a system language ("uk", "sr-Cyrl"…); [LATIN] for everything else. */
        fun forLocale(language: String, script: String = ""): KeypadLayout = when (language.lowercase()) {
            "ru" -> RUSSIAN
            "uk" -> UKRAINIAN
            "be" -> BELARUSIAN
            "bg" -> BULGARIAN
            "sr" -> if (script.equals("Latn", ignoreCase = true)) LATIN else SERBIAN
            "el" -> GREEK
            "he", "iw" -> HEBREW
            "ar" -> ARABIC
            else -> LATIN
        }

        /**
         * Layouts worth suggesting for these contact names, most useful first: one per script found in at least
         * [minNames] names. Cyrillic is narrowed to a language by its tell-tale letters (Ukrainian і/ї/є/ґ,
         * Belarusian ў, Serbian ђ/ћ/џ/љ/њ/ј, otherwise Russian).
         */
        fun suggest(names: Sequence<String>, minNames: Int = 1): List<KeypadLayout> {
            val counts = HashMap<KeypadLayout, Int>()
            for (name in names) {
                val found = HashSet<KeypadLayout>()
                for (ch in name) {
                    val c = ch.lowercaseChar()
                    when (Character.UnicodeScript.of(c.code)) {
                        Character.UnicodeScript.CYRILLIC -> found += cyrillicLanguage(c)
                        Character.UnicodeScript.GREEK -> found += GREEK
                        Character.UnicodeScript.HEBREW -> found += HEBREW
                        Character.UnicodeScript.ARABIC -> found += ARABIC
                        else -> Unit
                    }
                }
                // A name with a Ukrainian letter is Ukrainian even though most of its letters look Russian.
                val specific = found.filter { it != RUSSIAN }
                val picked = if (RUSSIAN in found && specific.any { it.isCyrillic }) found - RUSSIAN else found
                picked.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            }
            return counts.filter { it.value >= minNames }.entries.sortedByDescending { it.value }.map { it.key }
        }

        private fun cyrillicLanguage(c: Char): KeypadLayout = when (c) {
            'і', 'ї', 'є', 'ґ' -> UKRAINIAN
            'ў' -> BELARUSIAN
            'ђ', 'ћ', 'џ', 'љ', 'њ', 'ј' -> SERBIAN
            else -> RUSSIAN
        }
    }

    val isCyrillic: Boolean get() = this == RUSSIAN || this == UKRAINIAN || this == BELARUSIAN || this == BULGARIAN || this == SERBIAN
}
