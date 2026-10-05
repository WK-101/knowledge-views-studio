package app.parley.common.backup

import app.parley.common.ContactSummary
import app.parley.common.PhoneIdentity
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitScope
import app.parley.common.people.ContactRef

/**
 * How backup sections name a contact so it can be found on another phone, where lookup keys differ: the lookup key,
 * the display name and the numbers' [PhoneIdentity.portableKey]s (the shape the Circle section has always used).
 */
data class PersonRef(val key: String, val name: String? = null, val phones: Set<String> = emptySet())

class PersonRefs(
    private val contacts: List<ContactSummary>,
    /**
     * Archived contacts' keys in the backup → their keys here ([app.parley.common.people.Archive.restoredKeys]). An
     * archived key is resolved through this alone: never by name or number in the address book.
     */
    private val archived: Map<String, String> = emptyMap(),
) {
    private val byKey = contacts.associateBy { it.lookupKey }

    /** The reference written for [lookupKey]: key alone when it isn't a current contact. */
    fun ref(lookupKey: String): PersonRef {
        val c = byKey[lookupKey] ?: return PersonRef(lookupKey)
        return PersonRef(lookupKey, c.displayName, c.phones.mapNotNull { PhoneIdentity.portableKey(it.number) }.toSet())
    }

    /** The contact here: same lookup key, else a shared number, else the only contact with that name. */
    fun resolve(ref: PersonRef): ContactSummary? {
        if (ContactRef.isArchivedKey(ref.key)) return archived[ref.key]?.let { archivedHere(it) }
        byKey[ref.key]?.let { return it }
        if (ref.phones.isNotEmpty()) contacts.firstOrNull { c -> c.phones.any { PhoneIdentity.portableKey(it.number) in ref.phones } }?.let { return it }
        val name = ref.name?.takeIf { it.isNotBlank() } ?: return null
        return contacts.filter { it.displayName == name }.singleOrNull()
    }
}

/** An archived contact here, as a backup section writes to it: its Parley key, and no contact id (0 names none). */
fun archivedHere(key: String): ContactSummary = ContactSummary(0, key, "", null, false, emptyList())

/** Call-time settings from a backup, with contact keys moved to this phone's contacts. */
object CallTimeRestore {
    data class Result(val config: CallingConfig, val unmatched: Int)

    /** [resolve] maps a backup lookup key to this phone's, or null when the person isn't here (their entries go). */
    fun remap(config: CallingConfig, resolve: (String) -> String?): Result {
        var unmatched = 0
        fun key(k: String): String? = resolve(k).also { if (it == null) unmatched++ }
        val rules = config.rules.mapNotNull { r -> if (r.scope == LimitScope.CONTACT) key(r.key)?.let { r.copy(key = it) } else r }
        val never = config.neverLimit.mapNotNull { key(it) }.toSet()
        val perContact = config.reminders.perContact.mapNotNull { (k, v) -> key(k)?.let { it to v } }.toMap()
        return Result(config.copy(rules = rules, neverLimit = never, reminders = config.reminders.copy(perContact = perContact)), unmatched)
    }

    /** Every contact key [config] names (for the references written beside it). */
    fun keys(config: CallingConfig): Set<String> =
        config.rules.filter { it.scope == LimitScope.CONTACT }.map { it.key }.toSet() + config.neverLimit + config.reminders.perContact.keys
}
