package app.parley.common

/**
 * The "last messaged" record (which numbers you opened a chat with through Parley, and when), as pure logic.
 * Entries are keyed by [PhoneIdentity.key]; records written before F7 were keyed by the last 9 digits and are
 * read through [PhoneNumbers.fallbackLineKey] until [rekeyLegacy] moves them.
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

    /** "Forget messaged numbers after" choices, in days (0 = never). */
    val EXPIRY_CHOICES = listOf(0, 7, 30, 90)

    fun expiryLabel(days: Int): String = if (days <= 0) "Never" else "After $days days"

    /**
     * The oldest time kept, from the record's own expiry ([expiryDays], 0 = never) and the call-history
     * retention ([retentionDays], 0 = keep): whichever is stricter wins. Null when nothing expires.
     */
    fun cutoff(expiryDays: Int, retentionDays: Int, now: Long): Long? {
        val days = listOf(expiryDays, retentionDays).filter { it > 0 }.minOrNull() ?: return null
        return now - days * 86_400_000L
    }

    /** Call-history retention applies here too: entries older than [before] go. */
    fun prune(entries: List<MessagedEntry>, before: Long): List<MessagedEntry> = entries.filter { it.at >= before }

    /**
     * Entries kept from before F7 (no number, keyed by the last digits) moved to the line key [plan] maps those digits
     * to ([PhoneKeyMigration.plan]); where the line already has an entry, the newer of the two stays.
     */
    fun rekeyLegacy(entries: List<MessagedEntry>, plan: Map<String, String>): List<MessagedEntry> {
        if (plan.isEmpty()) return entries
        val moved = entries.map { e ->
            val digits = if (e.number == null && e.key.startsWith(LEGACY_PREFIX)) e.key.removePrefix(LEGACY_PREFIX) else null
            digits?.let { plan[it] }?.let { e.copy(key = it) } ?: e
        }
        return moved.groupBy { it.key }.values.map { same -> same.maxBy { it.at } }.sortedBy { it.at }
    }

    /**
     * The last digits of the entries kept from before F7, in the form [PhoneKeyMigration.plan] resolves, so they are
     * re-keyed even when no other stored row has the same digits.
     */
    fun legacyDigits(entries: List<MessagedEntry>): List<String> =
        entries.filter { it.number == null && it.key.startsWith(LEGACY_PREFIX) }.map { it.key.removePrefix(LEGACY_PREFIX) }.distinct()

    /** How [fromLegacy] keys a number of 7 digits or more: "~k" plus its last digits. */
    private const val LEGACY_PREFIX = "~k"

    /** Converts an entry of the old plain record (key = last 9 digits) to the current keying. */
    fun fromLegacy(oldKey: String, appPackage: String?, label: String, at: Long): MessagedEntry? {
        val key = PhoneNumbers.fallbackLineKey(oldKey).ifEmpty { return null }
        return MessagedEntry(key, null, appPackage, label, at)
    }
}
