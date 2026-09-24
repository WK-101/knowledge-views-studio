package app.parley.common

/**
 * F13: the "last messaged" record (which numbers you opened a chat with through Parley, and when), as pure logic.
 * Entries are keyed by [PhoneNumbers.lineKey]; records written before F7 were keyed by the last 9 digits and are
 * read through [PhoneNumbers.fallbackLineKey].
 */
data class MessagedEntry(
    val key: String,
    /** The number as dialled (null for entries migrated from the old record, which kept only the last digits). */
    val number: String?,
    val appPackage: String?,
    val label: String,
    val at: Long,
)

object MessagedRecord {
    const val MAX_ENTRIES = 500

    /** Adds or refreshes [number], newest last, capped at [MAX_ENTRIES]. */
    fun record(entries: List<MessagedEntry>, number: String, appPackage: String?, label: String, at: Long, countryIso: String?): List<MessagedEntry> {
        val key = PhoneNumbers.lineKey(number, countryIso)
        if (key.isEmpty()) return entries
        val legacy = PhoneNumbers.fallbackLineKey(number)
        val out = entries.filter { it.key != key && !(it.number == null && it.key == legacy) }.toMutableList()
        out += MessagedEntry(key, number, appPackage, label, at)
        return out.sortedBy { it.at }.takeLast(MAX_ENTRIES)
    }

    fun find(entries: List<MessagedEntry>, number: String, countryIso: String?): MessagedEntry? {
        val key = PhoneNumbers.lineKey(number, countryIso)
        if (key.isEmpty()) return null
        entries.lastOrNull { it.key == key }?.let { return it }
        val legacy = PhoneNumbers.fallbackLineKey(number)
        return entries.lastOrNull { it.number == null && it.key == legacy }
    }

    /** Removes [number] (and an old last-digits entry for it). */
    fun forget(entries: List<MessagedEntry>, number: String, countryIso: String?): List<MessagedEntry> {
        val key = PhoneNumbers.lineKey(number, countryIso)
        val legacy = PhoneNumbers.fallbackLineKey(number)
        return entries.filterNot { it.key == key || (it.number == null && it.key == legacy) }
    }

    /** Call-history retention applies here too: entries older than [before] go. */
    fun prune(entries: List<MessagedEntry>, before: Long): List<MessagedEntry> = entries.filter { it.at >= before }

    /** Converts an entry of the old plain record (key = last 9 digits) to the current keying. */
    fun fromLegacy(oldKey: String, appPackage: String?, label: String, at: Long): MessagedEntry? {
        val key = PhoneNumbers.fallbackLineKey(oldKey).ifEmpty { return null }
        return MessagedEntry(key, null, appPackage, label, at)
    }
}
