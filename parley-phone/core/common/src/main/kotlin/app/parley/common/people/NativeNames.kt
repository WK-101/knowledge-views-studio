package app.parley.common.people

import app.parley.common.record.Col
import app.parley.common.record.Mime
import java.util.Locale

/**
 * A person's name in their own language and script ("Иван Петров" beside the everyday "Ivan Petrov", "王伟" beside
 * "Wang Wei"). [full] is the name as written; [given] and [family] are optional parts; [language] a BCP 47 tag ("ru").
 */
data class NativeName(val full: String = "", val given: String = "", val family: String = "", val language: String = "") {
    val isBlank: Boolean get() = full.isBlank() && given.isBlank() && family.isBlank()

    /** The name as shown: [full], or the parts in the order the language writes them (family first in CJK). */
    val shown: String
        get() = full.trim().ifEmpty {
            val parts = if (NativeNames.familyFirst(language)) listOf(family, given) else listOf(given, family)
            val glue = if (NativeNames.noSpaces(language)) "" else " "
            parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(glue)
        }
}

/**
 * How a [NativeName] is kept in the address book, so it syncs and other apps see it without the main name changing:
 * a nickname row of the custom type labelled "Name in Russian" (Android's Nickname: DATA1 the name, DATA2 the type,
 * DATA3 the label). Parley adds the language tag ([LANGUAGE_COLUMN]) and the parts ([GIVEN_COLUMN], [FAMILY_COLUMN])
 * in columns Nickname doesn't use; an account whose sync keeps only the name and label still says the language, which
 * [languageOfLabel] reads back. The main name (StructuredName) stays the everyday one.
 */
object NativeNames {
    /** Nickname.TYPE_CUSTOM. */
    const val TYPE_CUSTOM = "0"
    const val LANGUAGE_COLUMN = Col.D4
    const val GIVEN_COLUMN = Col.D5
    const val FAMILY_COLUMN = Col.D6

    // The label is data other apps show, not Parley's UI: it is written in English, as Parley is.
    private const val LABEL_PREFIX = "Name in "
    private const val LABEL_UNKNOWN = "Name in their language"

    /** The row's label for [language]: "Name in Russian", or "Name in their language" without one. */
    fun label(language: String): String {
        val name = Languages.display(language.trim(), Locale.ENGLISH)
        return if (name.isEmpty()) LABEL_UNKNOWN else LABEL_PREFIX + name
    }

    /** Whether a nickname [label] marks a name in the person's language. */
    fun isLabel(label: String?): Boolean = label != null && label.trim().startsWith(LABEL_PREFIX, ignoreCase = true)

    /** The language a [label] names ("Name in Russian" → "ru"); "" for "their language" or a name Parley doesn't know. */
    fun languageOfLabel(label: String?): String {
        val l = label?.trim().orEmpty()
        if (!isLabel(l) || l.equals(LABEL_UNKNOWN, ignoreCase = true)) return ""
        val stored = Languages.toStored(l.substring(LABEL_PREFIX.length), Locale.ENGLISH)
        return if (Languages.isTag(stored)) stored else ""
    }

    /** Whether [label] is exactly one Parley writes: "Name in" a language it knows, or "Name in their language". */
    fun isExactLabel(label: String?): Boolean {
        val l = label?.trim().orEmpty()
        if (l.equals(LABEL_UNKNOWN, ignoreCase = true)) return true
        val language = languageOfLabel(l)
        return language.isNotEmpty() && l.equals(label(language), ignoreCase = true)
    }

    /**
     * Whether a nickname row of [type] (DATA2), [label] (DATA3), [language] ([LANGUAGE_COLUMN]) and [value] (DATA1) is
     * a name in the person's language. Parley's own marker says so: the language tag, which Nickname doesn't use.
     * Without it (an account whose sync keeps only the name and label) the label must be exactly Parley's and the
     * name in another script than the main name [mainName] (unknown: any script but Latin). Anything else, such as a
     * nickname the person labelled "Name in school", stays a nickname.
     */
    fun isRow(type: String?, label: String?, language: String?, value: String?, mainName: String? = null): Boolean {
        if (type?.trim() != TYPE_CUSTOM || !isLabel(label)) return false
        if (!language.isNullOrBlank()) return true
        if (!isExactLabel(label)) return false
        val script = Scripts.of(value.orEmpty()) ?: return false
        val main = mainName?.let(Scripts::of)
        return if (main != null) script != main else script != Character.UnicodeScript.LATIN
    }

    /** [isRow] for a row read by column ([get]: column → value). */
    fun isRow(get: (String) -> String?, mainName: String? = null): Boolean = isRow(get(Col.D2), get(Col.D3), get(LANGUAGE_COLUMN), get(Col.D1), mainName)

    /** Whether data row [mime] with [get] (column → value) is a name in the person's language. */
    fun isRow(mime: String, get: (String) -> String?, mainName: String? = null): Boolean = mime == Mime.NICKNAME && isRow(get, mainName)

    /** The name a native-name row holds; its language from [LANGUAGE_COLUMN], else from the label. */
    fun fromRow(get: (String) -> String?): NativeName = NativeName(
        full = get(Col.D1).orEmpty().trim(),
        given = get(GIVEN_COLUMN).orEmpty().trim(),
        family = get(FAMILY_COLUMN).orEmpty().trim(),
        language = get(LANGUAGE_COLUMN)?.trim()?.takeIf { it.isNotEmpty() } ?: languageOfLabel(get(Col.D3)),
    )

    /** The columns of [n]'s row (blank parts as null, so they are cleared). */
    fun rowValues(n: NativeName): Map<String, String?> {
        val tag = Languages.toStored(n.language)
        return linkedMapOf(
            Col.D1 to n.shown.ifEmpty { null },
            Col.D2 to TYPE_CUSTOM,
            Col.D3 to label(tag),
            LANGUAGE_COLUMN to tag.ifEmpty { null },
            GIVEN_COLUMN to n.given.trim().ifEmpty { null },
            FAMILY_COLUMN to n.family.trim().ifEmpty { null },
        )
    }

    /** Languages that write the family name first (Chinese, Japanese, Korean, Hungarian, Vietnamese). */
    fun familyFirst(language: String): Boolean = Locale.forLanguageTag(language.trim()).language in FAMILY_FIRST

    /** Languages whose names are written without a space between the parts (Chinese, Japanese). */
    fun noSpaces(language: String): Boolean = Locale.forLanguageTag(language.trim()).language in NO_SPACES

    private val FAMILY_FIRST = setOf("zh", "ja", "ko", "hu", "vi")
    private val NO_SPACES = setOf("zh", "ja")
}

/**
 * The writing system of a name: which script it is in, whether it needs a Latin spelling, and the language it most
 * likely is ("Иван" → Russian, "Іван" → Ukrainian, "王伟" → Chinese). Script only, no dictionary: a guess the editor
 * offers, never applies by itself.
 */
object Scripts {
    /** The script most of [text]'s letters are in; null when it has no letters. */
    fun of(text: String): Character.UnicodeScript? {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val s = if (Character.isLetter(cp)) Character.UnicodeScript.of(cp) else null
            if (s != null && s != Character.UnicodeScript.COMMON && s != Character.UnicodeScript.INHERITED) counts[s] = (counts[s] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /** Whether [text] holds a letter outside the Latin script (so a Latin spelling helps people who can't read it). */
    fun hasNonLatin(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            // Latin letters (with their accents) are below U+0250, and Latin Extended Additional sits at U+1E00–U+1EFF.
            val latinRange = cp < 0x250 || cp in 0x1E00..0x1EFF
            if (!latinRange && Character.isLetter(cp) && Character.UnicodeScript.of(cp) !in LATIN_OR_COMMON) return true
        }
        return false
    }

    /** Whether most of [text]'s letters are outside the Latin script. */
    fun isNonLatin(text: String): Boolean = of(text).let { it != null && it != Character.UnicodeScript.LATIN }

    /** The language [text] is most likely written in, as a BCP 47 tag; null for Latin text or a script Parley can't place. */
    @Suppress("CyclomaticComplexMethod") // One branch per script.
    fun suggestLanguage(text: String): String? {
        val script = of(text) ?: return null
        val t = text.lowercase(Locale.ROOT)
        fun has(letters: String) = t.any { it in letters }
        return when (script) {
            Character.UnicodeScript.CYRILLIC -> when {
                has("їєґ") -> "uk"
                has("ў") -> "be"
                has("ђћџ") -> "sr"
                has("ѓќѕ") -> "mk"
                has("әғқңөұһ") -> "kk"
                has("і") -> "uk"
                else -> "ru"
            }
            Character.UnicodeScript.GREEK -> "el"
            Character.UnicodeScript.ARABIC -> when {
                has("ٹڈڑںےۓ") -> "ur"
                has("پچژگ") || has("ی") && !has("ي") -> "fa"
                else -> "ar"
            }
            Character.UnicodeScript.HEBREW -> "he"
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "ja"
            Character.UnicodeScript.HAN -> if (text.any { Character.UnicodeScript.of(it.code) in KANA }) "ja" else "zh"
            Character.UnicodeScript.HANGUL -> "ko"
            else -> OTHER[script]
        }
    }

    private val LATIN_OR_COMMON = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED)
    private val KANA = setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)

    private val OTHER: Map<Character.UnicodeScript, String> = mapOf(
        Character.UnicodeScript.ARMENIAN to "hy",
        Character.UnicodeScript.GEORGIAN to "ka",
        Character.UnicodeScript.THAI to "th",
        Character.UnicodeScript.LAO to "lo",
        Character.UnicodeScript.KHMER to "km",
        Character.UnicodeScript.MYANMAR to "my",
        Character.UnicodeScript.DEVANAGARI to "hi",
        Character.UnicodeScript.BENGALI to "bn",
        Character.UnicodeScript.GURMUKHI to "pa",
        Character.UnicodeScript.GUJARATI to "gu",
        Character.UnicodeScript.TAMIL to "ta",
        Character.UnicodeScript.TELUGU to "te",
        Character.UnicodeScript.KANNADA to "kn",
        Character.UnicodeScript.MALAYALAM to "ml",
        Character.UnicodeScript.SINHALA to "si",
        Character.UnicodeScript.ETHIOPIC to "am",
        Character.UnicodeScript.TIBETAN to "bo",
        Character.UnicodeScript.MONGOLIAN to "mn",
    )
}

/**
 * A Latin spelling of text in another script ("Иван" → "Ivan", "王伟" → "wang wei"). Android's ICU transliterators
 * implement it in core/data; null when there is nothing to spell or no transliterator.
 */
fun interface Latinizer {
    fun latin(text: String): String?
}
