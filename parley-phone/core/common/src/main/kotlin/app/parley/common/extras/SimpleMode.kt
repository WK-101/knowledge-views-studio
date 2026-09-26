package app.parley.common.extras

import app.parley.common.ContactSummary
import app.parley.common.PhoneNumbers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** X4: one tile of the simple home. The contact is found again by [lookupKey], then by number, then by name. */
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

    /** Reads [export]'s text; throws [IllegalArgumentException] for anything else. */
    fun import(text: String): SimpleConfig {
        val e = runCatching { json.decodeFromString(Exported.serializer(), text) }.getOrElse { throw IllegalArgumentException("Not a simple-mode setup", it) }
        require(e.format <= FORMAT) { "Setup from a newer version" }
        return e.config.normalised().copy(enabled = false)
    }

    /** At most [SimpleConfig.MAX_PEOPLE] people, no blank numbers, no number twice. */
    fun SimpleConfig.normalised(): SimpleConfig = copy(
        people = people.filter { it.number.isNotBlank() }.distinctBy { PhoneNumbers.matchKey(it.number) }.take(SimpleConfig.MAX_PEOPLE),
    )

    /** A tile resolved against this phone's contacts: [contact] null means "not in contacts" (offer to create it). */
    data class Resolved(val person: SimplePerson, val contact: ContactSummary?)

    /** Finds each person: same lookup key, else a contact with the same number, else the one contact with that name. */
    fun resolve(people: List<SimplePerson>, contacts: List<ContactSummary>): List<Resolved> {
        val byKey = contacts.associateBy { it.lookupKey }
        return people.map { p ->
            val key = PhoneNumbers.matchKey(p.number)
            val c = p.lookupKey?.let { byKey[it] }
                ?: contacts.firstOrNull { c -> key.isNotEmpty() && c.phones.any { PhoneNumbers.matchKey(it.number) == key } }
                ?: contacts.filter { it.displayName.trim().equals(p.name.trim(), ignoreCase = true) && p.name.isNotBlank() }.singleOrNull()
            Resolved(p, c)
        }
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
