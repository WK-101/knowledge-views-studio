package app.parley.common

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime

/**
 * Answers "is this incoming contact already in the address book?" during an import, using the same keys as
 * [Duplicates]: normalised phone numbers, lower-cased e-mails and word-order-insensitive names.
 *
 * It is deliberately stricter than the duplicate finder, because a false match here silently skips a person:
 * a shared phone number or e-mail is a match; a shared name only counts when the incoming contact has no
 * number or e-mail to tell the two apart.
 */
class DuplicateIndex {
    private val phones = HashSet<String>()
    private val emails = HashSet<String>()
    private val names = HashSet<String>()

    fun add(c: ContactSummary) {
        c.phones.forEach { p -> Duplicates.phoneKey(p.number)?.let { phones += it } }
        c.emails.forEach { e -> Duplicates.emailKey(e)?.let { emails += it } }
        Duplicates.nameKey(c.displayName)?.let { names += it }
    }

    fun add(r: ContactRecord) {
        val k = keys(r)
        phones += k.phones
        emails += k.emails
        k.name?.let { names += it }
    }

    fun matches(r: ContactRecord): Boolean {
        val k = keys(r)
        if (k.phones.any { it in phones } || k.emails.any { it in emails }) return true
        return k.phones.isEmpty() && k.emails.isEmpty() && k.name != null && k.name in names
    }

    private class Keys(val phones: Set<String>, val emails: Set<String>, val name: String?)

    private fun keys(r: ContactRecord): Keys {
        val rows = r.raws.flatMap { it.rows }
        return Keys(
            phones = rows.filter { it.mimeType == Mime.PHONE }.mapNotNull { it[Col.D1]?.let(Duplicates::phoneKey) }.toSet(),
            emails = rows.filter { it.mimeType == Mime.EMAIL }.mapNotNull { it[Col.D1]?.let(Duplicates::emailKey) }.toSet(),
            name = Duplicates.nameKey(r.displayName),
        )
    }
}
