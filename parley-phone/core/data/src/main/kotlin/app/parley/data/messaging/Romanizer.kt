package app.parley.data.messaging

import android.icu.text.Transliterator
import app.parley.common.people.Latinizer
import app.parley.common.people.Scripts
import java.util.concurrent.ConcurrentHashMap

/**
 * Latin readings of Chinese, Japanese and Korean names for keypad search, with the ICU transliterators built into
 * Android (API 29+), so Parley ships no pinyin or kana tables.
 *
 * Each character is romanised on its own ("张三" → ["zhang", "san"]) and cached, so building the keypad index for
 * thousands of contacts transliterates each distinct character once. Characters with several readings get ICU's
 * most common one; the contact's phonetic name covers the rest.
 *
 * It also spells whole names of any script in Latin letters ([latin]: "Иван Петров" → "Ivan Petrov"), for the search
 * index, the keypad and the editor's "Add an English spelling".
 */
object Romanizer : Latinizer {
    private val han by lazy { create("Han-Latin; Latin-ASCII") }
    private val hangul by lazy { create("Hangul-Latin; Latin-ASCII") }
    private val hiragana by lazy { create("Hiragana-Latin; Latin-ASCII") }
    private val katakana by lazy { create("Katakana-Latin; Latin-ASCII") }

    // One Any-Latin per thread: the search index is built off the main thread while the keypad and the editor spell too.
    private val any = ThreadLocal.withInitial { create("Any-Latin; Latin-ASCII") }
    private val cache = ConcurrentHashMap<Char, String>()

    /**
     * Names spelled lately, for the screens that spell a few at a time. Bounded and least recently used first, so it
     * never empties all at once; a whole index build keeps its own spellings ([forIndex]).
     */
    private val spelled = object : LinkedHashMap<String, String>(SPELLED_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > SPELLED_MAX
    }

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
        return any.get()?.let { runCatching { it.transliterate(n) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * [text] in Latin letters (ICU's Any-Latin, then plain ASCII: "Иван" → "Ivan", "Γιώργος" → "Giorgos", "王伟" →
     * "wang wei"), or null when it has nothing outside the Latin script. The names spelled lately are kept; a whole
     * search index spells through [forIndex] instead.
     */
    override fun latin(text: String): String? {
        val t = text.trim()
        if (t.isEmpty() || !Scripts.hasNonLatin(t)) return null
        synchronized(spelled) { spelled[t] }?.let { return it.ifEmpty { null } }
        val out = spell(t)
        synchronized(spelled) { spelled[t] = out }
        return out.ifEmpty { null }
    }

    /**
     * A [Latinizer] for building one search index: every name it spells is kept for that build only (as many as the
     * index has, never cut short partway), then dropped with it.
     */
    fun forIndex(): Latinizer {
        val kept = HashMap<String, String>()
        return Latinizer { text ->
            val t = text.trim()
            if (t.isEmpty() || !Scripts.hasNonLatin(t)) {
                null
            } else {
                kept.getOrPut(t) { spell(t) }.ifEmpty { null }
            }
        }
    }

    /** [t] in Latin letters, single-spaced; "" when it can't be spelled. */
    private fun spell(t: String): String =
        any.get()?.let { runCatching { it.transliterate(t) }.getOrNull() }?.replace(SPACES, " ")?.trim().orEmpty()

    /**
     * A name's Latin spelling as a person would write it: each word capitalised ("Иван петров" → "Ivan Petrov"), for the
     * editor's suggestion; null when there is nothing to spell.
     */
    fun spelling(text: String): String? = latin(text)?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }

    private const val SPELLED_MAX = 2_000
    private val SPACES = Regex("\\s+")

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
