package app.parley.data

import app.parley.common.people.Handles
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime

/**
 * Q4: a contact read by the vCard engine (a scanned code, a shared card) as a draft for the editor. It fills every
 * field the editor has, with the same columns [ContactsRepository] reads. Rows the editor can't show (a photo, rows
 * of other apps) are left out: [hasHiddenFields] tells the screen to offer importing the card as it is instead.
 */
object RecordDetails {
    /** Kinds the editor shows. */
    private val EDITABLE = setOf(Mime.NAME, Mime.NICKNAME, Mime.ORG, Mime.NOTE, Mime.PHONE, Mime.EMAIL, Mime.IM, Mime.SIP, Mime.WEBSITE, Mime.RELATION, Mime.POSTAL, Mime.EVENT, Mime.GROUP)

    fun toDetails(record: ContactRecord): ContactDetails {
        val rows = record.raws.flatMap { it.rows }
        fun s(v: String?) = v.orEmpty()
        fun type(v: String?, default: Int) = v?.toIntOrNull() ?: default
        var d = ContactDetails(displayName = record.displayName, starred = record.starred)
        val name = rows.firstOrNull { it.mimeType == Mime.NAME }
        if (name != null) {
            d = d.copy(
                given = s(name[Col.D2]), family = s(name[Col.D3]), prefix = s(name[Col.D4]), middle = s(name[Col.D5]), suffix = s(name[Col.D6]),
                phoneticGiven = s(name[Col.D7]), phoneticFamily = s(name[Col.D9]),
            )
        }
        if (d.composedName.isBlank() && record.displayName.isNotBlank() && rows.none { it.mimeType == Mime.ORG }) {
            // Only a formatted name: split it the way the Insert intent does.
            val parts = record.displayName.trim().split(Regex("\\s+"), limit = 2)
            d = d.copy(given = parts[0], family = parts.getOrElse(1) { "" })
        }
        rows.firstOrNull { it.mimeType == Mime.NICKNAME }?.let { d = d.copy(nickname = s(it[Col.D1])) }
        rows.firstOrNull { it.mimeType == Mime.ORG }?.let { d = d.copy(company = s(it[Col.D1]), title = s(it[Col.D4])) }
        rows.filter { it.mimeType == Mime.NOTE }.map { s(it[Col.D1]) }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { d = d.copy(note = it.joinToString("\n\n")) }
        fun items(mime: String, default: Int) = rows.filter { it.mimeType == mime && !it[Col.D1].isNullOrBlank() }
            .map { DataItem(value = s(it[Col.D1]), type = type(it[Col.D2], default), label = it[Col.D3], isPrimary = it.isSuperPrimary) }
        d = d.copy(
            phones = items(Mime.PHONE, 2),
            emails = items(Mime.EMAIL, 1),
            websites = items(Mime.WEBSITE, 7),
            relations = items(Mime.RELATION, 0),
            addresses = rows.filter { it.mimeType == Mime.POSTAL }.map {
                var p = PostalItem(
                    street = s(it[Col.D4]), poBox = s(it[Col.D5]), neighborhood = s(it[Col.D6]), city = s(it[Col.D7]), region = s(it[Col.D8]),
                    postcode = s(it[Col.D9]), country = s(it[Col.D10]), type = type(it[Col.D2], 1), label = it[Col.D3],
                )
                if (p.isBlank) p = p.copy(street = s(it[Col.D1]))
                p
            }.filter { !it.isBlank },
            events = rows.filter { it.mimeType == Mime.EVENT && !it[Col.D1].isNullOrBlank() }.map { EventItem(date = s(it[Col.D1]), type = type(it[Col.D2], 3), label = it[Col.D3]) },
            handles = rows.filter { it.mimeType == Mime.IM || it.mimeType == Mime.SIP }
                .mapNotNull { Handles.fromRow(it.mimeType, it[Col.D1], it[Col.D5], it[Col.D6]) }
                .filter { it.value.isNotBlank() }
                .map { HandleItem(service = it.service, value = it.value, customProtocol = it.customProtocol) },
        )
        return d
    }

    /** Whether [record] holds something the editor would drop (a photo, custom rows, labels). */
    fun hasHiddenFields(record: ContactRecord): Boolean = record.raws.flatMap { it.rows }.any { it.mimeType !in EDITABLE || it.mimeType == Mime.GROUP }
}
