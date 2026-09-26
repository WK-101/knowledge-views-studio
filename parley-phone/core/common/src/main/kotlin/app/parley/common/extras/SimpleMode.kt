package app.parley.common.extras

import app.parley.common.ContactSummary
import app.parley.common.PhoneNumbers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * X4: one tile of the simple home. The tile always dials [number]; a contact of this phone lends its photo only when it
 * has that number ([lookupKey] picks between several that do).
 */
@Serializable
data class SimplePerson(val name: String, val number: String, val lookupKey: String? = null)

/**
 * X4 simple (assisted) mode: a home of big photo tiles for up to [MAX_PEOPLE] people, a larger keypad, and on the
 * incoming screen large buttons, a question before declining and (optionally) the caller's name spoken aloud.
 */
@Serializable
data class SimpleConfig(
    val enabled: Boolean = false,
    val people: List<SimplePerson> = emptyList(),
    val confirmDecline: Boolean = true,
    val speakName: Boolean = false,
    val showKeypad: Boolean = true,
) {
    companion object {
        const val MAX_PEOPLE = 9
    }
}

object SimpleSetup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Format tag inside exported setups, so a later version can tell them apart. */
    const val FORMAT = 1

    @Serializable
    private data class Exported(val format: Int = FORMAT, val config: SimpleConfig)

    fun decode(text: String?): SimpleConfig =
        if (text.isNullOrBlank()) SimpleConfig() else runCatching { json.decodeFromString(SimpleConfig.serializer(), text) }.getOrDefault(SimpleConfig()).normalised()

    fun encode(c: SimpleConfig): String = json.encodeToString(SimpleConfig.serializer(), c)

    /**
     * The setup to hand to another phone: people by name and number only (lookup keys mean nothing there), and
     * never "on": the other phone turns it on after checking.
     */
    fun export(c: SimpleConfig): String =
        json.encodeToString(Exported.serializer(), Exported(FORMAT, c.normalised().copy(enabled = false, people = c.people.map { it.copy(lookupKey = null) })))

    /** An imported setup and how many of its people were left out because their "number" wasn't a plain phone number. */
    data class Imported(val config: SimpleConfig, val skipped: Int)

    /** Reads [export]'s text; throws [IllegalArgumentException] for anything else. */
    fun import(text: String): SimpleConfig = importChecked(text).config

    /**
     * Like [import], also counting the people dropped. A setup comes from someone else (a file, a QR code, a link a web
     * page can open), so only plain dialable numbers get through: no `*`/`#` service or USSD codes (`**21*…#` would
     * forward every call), no pauses or extensions, nothing over-long.
     */
    fun importChecked(text: String): Imported {
        val e = runCatching { json.decodeFromString(Exported.serializer(), text) }.getOrElse { throw IllegalArgumentException("Not a simple-mode setup", it) }
        require(e.format <= FORMAT) { "Setup from a newer version" }
        val raw = e.config.people.take(MAX_IMPORTED)
        val cfg = e.config.copy(people = raw.map { it.copy(lookupKey = null) }).normalised().copy(enabled = false)
        return Imported(cfg, skipped = raw.count { dialable(it.number) == null })
    }

    /** Longest name kept on a tile. */
    const val MAX_NAME = 60

    /** Shortest and longest dialable number (digits only; E.164 has 15, plus an international prefix such as 011). */
    const val MIN_DIGITS = 3
    const val MAX_DIGITS = 18

    /** More people than this in a setup isn't one of ours; read no further. */
    private const val MAX_IMPORTED = 64

    /**
     * The plain dialable form of [raw] (ASCII digits with an optional leading `+`; spaces, dashes, dots, slashes and
     * brackets dropped), or null for anything else: service and USSD codes (`*`, `#`), pauses and waits (`,` `;` `p`
     * `w`), letters, and numbers shorter than [MIN_DIGITS] or longer than [MAX_DIGITS] digits.
     */
    fun dialable(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > MAX_DIGITS * 3) return null
        val out = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            when {
                ch in '0'..'9' -> out.append(ch)
                ch == '+' && out.isEmpty() && s.substring(0, i).all { it in SEPARATORS } -> out.append(ch)
                ch in SEPARATORS -> Unit
                else -> return null
            }
        }
        val digits = out.count { it.isDigit() }
        return out.toString().takeIf { digits in MIN_DIGITS..MAX_DIGITS }
    }

    private const val SEPARATORS = " \u00A0-.()/\u2010\u2011\u2012\u2013"

    /** A name for a tile: no control characters, at most [MAX_NAME] characters. */
    private fun cleanName(n: String): String = n.filterNot { it.isISOControl() }.trim().take(MAX_NAME)

    /** At most [SimpleConfig.MAX_PEOPLE] people, each with a plain dialable number ([dialable]), no number twice. */
    fun SimpleConfig.normalised(): SimpleConfig = copy(
        people = people.mapNotNull { p -> dialable(p.number)?.let { n -> p.copy(name = cleanName(p.name).ifEmpty { n }, number = n) } }
            .distinctBy { PhoneNumbers.matchKey(it.number) }.take(SimpleConfig.MAX_PEOPLE),
    )

    /** A tile resolved against this phone's contacts: [contact] null means "not in contacts" (offer to create it). */
    data class Resolved(val person: SimplePerson, val contact: ContactSummary?)

    /**
     * Finds each person by number only: a contact that has the tile's number (the one with the tile's lookup key when
     * several do). A contact that merely shares the name is never used, so a tile never shows one person's photo while
     * calling someone else's number.
     */
    fun resolve(people: List<SimplePerson>, contacts: List<ContactSummary>): List<Resolved> = people.map { p ->
        val key = PhoneNumbers.matchKey(p.number)
        val having = if (key.isEmpty()) emptyList() else contacts.filter { c -> c.phones.any { PhoneNumbers.matchKey(it.number) == key } }
        Resolved(p, having.firstOrNull { p.lookupKey != null && it.lookupKey == p.lookupKey } ?: having.firstOrNull())
    }

    /** Rows × columns of the tile grid for [n] people (1 → one big tile, 4 → 2×2, 9 → 3×3). */
    fun grid(n: Int): Pair<Int, Int> {
        val cols = when {
            n <= 1 -> 1
            n <= 4 -> 2
            else -> 3
        }
        return ((n + cols - 1) / cols).coerceAtLeast(1) to cols
    }
}
