package app.parley.data.messaging

import android.icu.text.Transliterator
import java.util.concurrent.ConcurrentHashMap

/**
 * Latin readings of Chinese, Japanese and Korean names for keypad search, with the ICU transliterators built into
 * Android (API 29+), so Parley ships no pinyin or kana tables.
 *
 * Each character is romanised on its own ("张三" → ["zhang", "san"]) and cached, so building the keypad index for
 * thousands of contacts transliterates each distinct character once. Characters with several readings get ICU's
 * most common one; the contact's phonetic name covers the rest.
 */
object Romanizer {
    private val han by lazy { create("Han-Latin; Latin-ASCII") }
    private val hangul by lazy { create("Hangul-Latin; Latin-ASCII") }
    private val hiragana by lazy { create("Hiragana-Latin; Latin-ASCII") }
    private val katakana by lazy { create("Katakana-Latin; Latin-ASCII") }
    private val any by lazy { create("Any-Latin; Latin-ASCII") }
    private val cache = ConcurrentHashMap<Char, String>()

    private fun create(id: String): Transliterator? = try {
        Transliterator.getInstance(id)
    } catch (_: Exception) {
        null
    }

    /** Whether [name] has characters worth romanising. */
    fun needsRomanizing(name: String): Boolean = name.any { scriptOf(it) != null }

    /**
     * One entry per character of [name]: its Latin reading in lower-case a–z, or null for characters that the keypad
     * handles by itself. Null when the name has no Chinese, Japanese or Korean characters.
     */
    fun syllables(name: String): List<String?>? {
        if (!needsRomanizing(name)) return null
        return name.map { ch ->
            val t = scriptOf(ch) ?: return@map null
            cache.getOrPut(ch) { clean(transliterate(t, ch.toString())) }.ifEmpty { null }
        }
    }

    /** A phonetic name in Latin letters (furigana and hangul are transliterated), or null when blank. */
    fun phonetic(name: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (n.all { it.code < 0x250 }) return n
        return transliterate(any, n)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun transliterate(t: Transliterator?, s: String): String? = t?.let { synchronized(it) { runCatching { it.transliterate(s) }.getOrNull() } }

    private fun clean(s: String?): String = s.orEmpty().lowercase().filter { it in 'a'..'z' }

    private fun scriptOf(ch: Char): Transliterator? = when (Character.UnicodeScript.of(ch.code)) {
        Character.UnicodeScript.HAN -> han
        Character.UnicodeScript.HANGUL -> hangul
        Character.UnicodeScript.HIRAGANA -> hiragana
        Character.UnicodeScript.KATAKANA -> if (ch == 'ー' || ch == '・') null else katakana
        else -> null
    }
}
