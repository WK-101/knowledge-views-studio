package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.Duplicates

/** Why a new contact looks like an existing one. */
enum class DuplicateReason { NUMBER, EMAIL, NAME }

data class DuplicateHit(val contact: ContactSummary, val reason: DuplicateReason, val matched: String)

/**
 * Live "Anna Smith already exists" check for the new-contact editor. Uses the same keys as the duplicate
 * finder and the import deduplication ([app.parley.common.DuplicateIndex]): a shared number or e-mail is a
 * strong match; a shared full name (two words or more) is reported too, since the user can still save.
 */
class DuplicateLookup(contacts: List<ContactSummary>) {
    private val byPhone = HashMap<String, ContactSummary>()
    private val byEmail = HashMap<String, ContactSummary>()
    private val byName = HashMap<String, ContactSummary>()

    init {
        for (c in contacts) {
            c.phones.forEach { p -> Duplicates.phoneKey(p.number)?.let { byPhone.putIfAbsent(it, c) } }
            c.emails.forEach { e -> Duplicates.emailKey(e)?.let { byEmail.putIfAbsent(it, c) } }
            Duplicates.nameKey(c.displayName)?.let { byName.putIfAbsent(it, c) }
        }
    }

    fun find(name: String, phones: List<String>, emails: List<String>): DuplicateHit? {
        phones.forEach { p -> Duplicates.phoneKey(p)?.let { k -> byPhone[k]?.let { return DuplicateHit(it, DuplicateReason.NUMBER, p.trim()) } } }
        emails.forEach { e -> Duplicates.emailKey(e)?.let { k -> byEmail[k]?.let { return DuplicateHit(it, DuplicateReason.EMAIL, e.trim()) } } }
        Duplicates.nameKey(name)?.let { k -> byName[k]?.let { return DuplicateHit(it, DuplicateReason.NAME, name.trim()) } }
        return null
    }

    companion object {
        fun describe(hit: DuplicateHit): String = when (hit.reason) {
            DuplicateReason.NUMBER -> "${hit.contact.displayName} already has ${hit.matched}"
            DuplicateReason.EMAIL -> "${hit.contact.displayName} already has ${hit.matched}"
            DuplicateReason.NAME -> "${hit.contact.displayName} already exists"
        }
    }
}
