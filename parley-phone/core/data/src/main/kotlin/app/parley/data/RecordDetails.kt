package app.parley.data

import app.parley.common.AltCalendar
import app.parley.common.people.AddressParts
import app.parley.common.people.CustomFields
import app.parley.common.people.Handles
import app.parley.common.people.NativeNames
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.RawRecord
import app.parley.common.record.Mime

/**
 * A contact read by the vCard engine (a scanned code, a shared card) as a draft for the editor. It fills every
 * field the editor has, with the same columns [ContactsRepository] reads. Rows the editor can't show (a photo, rows
 * of other apps) are left out: [hasHiddenFields] tells the screen to offer importing the card as it is instead.
 */
object RecordDetails {
    /** Kinds the editor shows. */
    private val EDITABLE = setOf(
        Mime.NAME, Mime.NICKNAME, Mime.PRONOUNS, Mime.ORG, Mime.NOTE, Mime.PHONE, Mime.EMAIL, Mime.IM, Mime.SIP, Mime.WEBSITE, Mime.RELATION,
        Mime.POSTAL, Mime.EVENT, Mime.GROUP, Mime.NAME_PARTS, Mime.LANGUAGE, Mime.CUSTOM_FIELD, Mime.GOOGLE_CUSTOM_FIELD,
        Mime.CITIZENSHIP,
    )

    fun toDetails(record: ContactRecord): ContactDetails {
        val rows = record.raws.flatMap { it.rows }
        fun s(v: String?) = v.orEmpty()
        fun type(v: String?, default: Int) = v?.toIntOrNull() ?: default
        var d = ContactDetails(displayName = record.displayName, starred = record.starred)
        val name = rows.firstOrNull { it.mimeType == Mime.NAME }
        if (name != null) {
            d = d.copy(
                given = s(name[Col.D2]), family = s(name[Col.D3]), prefix = s(name[Col.D4]), middle = s(name[Col.D5]), suffix = s(name[Col.D6]),
                phoneticGiven = s(name[Col.D7]), phoneticFamily = s(name[Col.D9]), phoneticMiddle = s(name[Col.D8]),
            )
        }
        if (d.composedName.isBlank() && record.displayName.isNotBlank() && rows.none { it.mimeType == Mime.ORG }) {
            // Only a formatted name: split it the way the Insert intent does.
            val parts = record.displayName.trim().split(Regex("\\s+"), limit = 2)
            d = d.copy(given = parts[0], family = parts.getOrElse(1) { "" })
        }
        fun native(r: DataRow) = r.mimeType == Mime.NICKNAME && NativeNames.isRow({ r[it] }, record.displayName.ifBlank { d.composedName })
        rows.firstOrNull { it.mimeType == Mime.NICKNAME && !native(it) }?.let { d = d.copy(nickname = s(it[Col.D1])) }
        rows.firstOrNull(::native)?.let { r -> d = d.copy(nativeName = NativeNames.fromRow { r[it] }) }
        rows.firstOrNull { it.mimeType == Mime.PRONOUNS }?.let { d = d.copy(pronouns = s(it[Col.D1])) }
        rows.firstOrNull { it.mimeType == Mime.NAME_PARTS }?.let { d = d.copy(secondSurname = s(it[Col.D1]), generation = s(it[Col.D2])) }
        // The languages in order, the one marked primary (a card's PREF=1) first.
        val spoken = rows.filter { it.mimeType == Mime.LANGUAGE }.sortedByDescending { it.isPrimary }
        d = d.copy(
            languages = spoken.map { s(it[Col.D1]).trim() }.filter { it.isNotEmpty() }.distinct(),
            citizenships = rows.filter { it.mimeType == Mime.CITIZENSHIP }.map { s(it[Col.D1]).trim() }.filter { it.isNotEmpty() }.distinct(),
        )
        d = d.copy(
            customFields = rows.filter { CustomFields.isCustomField(it.mimeType) }
                .map { CustomFieldItem(label = s(it[Col.D1]), value = s(it[Col.D2])) }.filterNot { it.isBlank },
        )
        rows.firstOrNull { it.mimeType == Mime.ORG }?.let {
            d = d.copy(
                company = s(it[Col.D1]), title = s(it[Col.D4]), department = s(it[Col.D5]),
                jobDescription = s(it[Col.D6]), officeLocation = s(it[Col.D9]),
            )
        }
        rows.filter { it.mimeType == Mime.NOTE }.map { s(it[Col.D1]) }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
            d = d.copy(note = it.joinToString("\n\n"))
        }
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
                    parts = s(it[AddressParts.COLUMN]),
                )
                if (p.isBlank) p = p.copy(street = s(it[Col.D1]))
                p
            }.filter { !it.isBlank },
            events = rows.filter { it.mimeType == Mime.EVENT && !it[Col.D1].isNullOrBlank() }.map {
                val calendar = it[AltCalendar.COLUMN]?.takeIf { c -> c.isNotBlank() }
                EventItem(date = s(it[Col.D1]), type = type(it[Col.D2], 3), label = it[Col.D3], calendar = calendar)
            },
            handles = rows.filter { it.mimeType == Mime.IM || it.mimeType == Mime.SIP }
                .mapNotNull { Handles.fromRow(it.mimeType, it[Col.D1], it[Col.D5], it[Col.D6]) }
                .filter { it.value.isNotBlank() }
                .map { HandleItem(service = it.service, value = it.value, customProtocol = it.customProtocol) },
        )
        return d
    }

    /**
     * The other way: a contact Parley holds as [ContactDetails] (a private contact) as a record the vCard engine writes,
     * with the same columns [toDetails] reads, its labels by title ([labels]) and its [photo]. [key] identifies it in
     * the file only. Ringtones stay out: a ringtone is a file of this phone.
     */
    fun toRecord(d: ContactDetails, key: String, labels: List<String> = emptyList(), photo: ByteArray? = null): ContactRecord {
        val rows = ArrayList<DataRow>()
        fun add(mime: String, vararg values: Pair<String, String?>, primary: Boolean = false) {
            val m = values.filter { !it.second.isNullOrEmpty() }.toMap()
            if (m.isNotEmpty()) rows += DataRow(mime, m, isPrimary = primary, isSuperPrimary = primary)
        }
        add(
            Mime.NAME, Col.D1 to d.displayName.ifBlank { d.composedName }, Col.D2 to d.given, Col.D3 to d.family, Col.D4 to d.prefix, Col.D5 to d.middle,
            Col.D6 to d.suffix, Col.D7 to d.phoneticGiven, Col.D8 to d.phoneticMiddle, Col.D9 to d.phoneticFamily,
        )
        add(Mime.NICKNAME, Col.D1 to d.nickname)
        add(Mime.PRONOUNS, Col.D1 to d.pronouns)
        add(Mime.NAME_PARTS, Col.D1 to d.secondSurname, Col.D2 to d.generation)
        rows += namesAndLanguages(d)
        add(Mime.ORG, Col.D1 to d.company, Col.D4 to d.title, Col.D5 to d.department, Col.D6 to d.jobDescription, Col.D9 to d.officeLocation)
        add(Mime.NOTE, Col.D1 to d.note)
        fun items(mime: String, list: List<DataItem>) = list.filter { it.value.isNotBlank() }.forEach {
            add(mime, Col.D1 to it.value, Col.D2 to it.type.toString(), Col.D3 to it.label, primary = it.isPrimary)
        }
        items(Mime.PHONE, d.phones)
        items(Mime.EMAIL, d.emails)
        items(Mime.WEBSITE, d.websites)
        items(Mime.RELATION, d.relations)
        d.addresses.filterNot { it.isBlank }.forEach { a ->
            add(
                Mime.POSTAL, Col.D1 to a.formatted, Col.D2 to a.type.toString(), Col.D3 to a.label, Col.D4 to a.street, Col.D5 to a.poBox,
                Col.D6 to a.neighborhood, Col.D7 to a.city, Col.D8 to a.region, Col.D9 to a.postcode, Col.D10 to a.country, AddressParts.COLUMN to a.parts,
            )
        }
        d.events.filter { it.date.isNotBlank() }.forEach { e ->
            add(Mime.EVENT, Col.D1 to e.date, Col.D2 to e.type.toString(), Col.D3 to e.label, AltCalendar.COLUMN to e.calendar)
        }
        d.handles.filter { it.value.isNotBlank() }.forEach { h ->
            val (mime, cols) = Handles.toColumns(h.handle)
            cols.filterValues { !it.isNullOrEmpty() }.takeIf { it.isNotEmpty() }?.let { rows += DataRow(mime, it) }
        }
        d.customFields.filterNot { it.isBlank }.forEach { add(Mime.CUSTOM_FIELD, Col.D1 to it.label, Col.D2 to it.value) }
        labels.filter { it.isNotBlank() }.forEach { add(Mime.GROUP, Col.GROUP_TITLE to it) }
        if (photo != null && photo.isNotEmpty()) rows += DataRow(Mime.PHOTO, emptyMap(), blob = photo)
        return ContactRecord(
            key = key, displayName = d.displayName.ifBlank { d.composedName.ifBlank { d.company } }, starred = d.starred,
            sendToVoicemail = d.sendToVoicemail, raws = listOf(RawRecord(null, null, rows = rows)),
        )
    }

    /** The name in their language, the languages (the first primary when several: a card writes PREF=1) and citizenship. */
    private fun namesAndLanguages(d: ContactDetails): List<DataRow> = buildList {
        val spoken = d.languages.map { it.trim() }.filter { it.isNotEmpty() }
        spoken.forEachIndexed { i, l ->
            val primary = i == 0 && spoken.size > 1
            add(DataRow(Mime.LANGUAGE, mapOf(Col.D1 to l), isPrimary = primary, isSuperPrimary = primary))
        }
        d.citizenships.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(DataRow(Mime.CITIZENSHIP, mapOf(Col.D1 to it))) }
        if (!d.nativeName.isBlank) {
            add(DataRow(Mime.NICKNAME, NativeNames.rowValues(d.nativeName).filterValues { !it.isNullOrEmpty() }))
        }
    }

    /** Whether [record] holds something the editor would drop (a photo, custom rows, labels). */
    fun hasHiddenFields(record: ContactRecord): Boolean = record.raws.flatMap { it.rows }.any { it.mimeType !in EDITABLE || it.mimeType == Mime.GROUP }
}
