package app.parley.common.qr

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import app.parley.common.vcard.VCardStream

/**
 * Q4: a contact card from a QR code comes from a stranger, so the parts of it that change how Parley and Android
 * treat the caller (favourite, which rings through Do Not Disturb; straight to voicemail; a ringtone; labels, which
 * call rules, off hours and ringtones go by) are dropped unless the user ticks them on the result sheet.
 */
object ScannedCard {
    enum class Flag { STARRED, VOICEMAIL, RINGTONE, LABELS }

    private const val STARRED_LABEL = "starred"

    /** What [record] asks for beyond its details. */
    fun flags(record: ContactRecord): Set<Flag> = buildSet {
        if (record.starred || labelRows(record).any { isStarredLabel(it) }) add(Flag.STARRED)
        if (record.sendToVoicemail) add(Flag.VOICEMAIL)
        if (!record.customRingtone.isNullOrEmpty()) add(Flag.RINGTONE)
        if (labels(record).isNotEmpty()) add(Flag.LABELS)
    }

    fun flags(records: List<ContactRecord>): Set<Flag> = records.flatMapTo(LinkedHashSet()) { flags(it) }

    /** The label (group) titles [record] would join, "starred" aside. */
    fun labels(record: ContactRecord): List<String> =
        labelRows(record).filterNot { isStarredLabel(it) }.mapNotNull { it[Col.GROUP_TITLE]?.trim()?.takeIf { t -> t.isNotEmpty() } }.distinct()

    fun labels(records: List<ContactRecord>): List<String> = records.flatMap { labels(it) }.distinct()

    /** [record] with only the [allowed] flags left. */
    fun strip(record: ContactRecord, allowed: Set<Flag>): ContactRecord {
        val raws = record.raws.map { raw ->
            raw.copy(
                rows = raw.rows.filter { row ->
                    when {
                        row.mimeType != Mime.GROUP -> true
                        isStarredLabel(row) -> Flag.STARRED in allowed
                        else -> Flag.LABELS in allowed
                    }
                },
            )
        }
        return record.copy(
            starred = record.starred && Flag.STARRED in allowed,
            sendToVoicemail = record.sendToVoicemail && Flag.VOICEMAIL in allowed,
            customRingtone = record.customRingtone.takeIf { Flag.RINGTONE in allowed },
            raws = raws,
        )
    }

    /** The vCard to import: [original] when nothing needed dropping, else the stripped cards written again. */
    fun vcardToImport(records: List<ContactRecord>, original: String, allowed: Set<Flag>): String {
        if (flags(records).all { it in allowed }) return original
        return VCardStream.writeAll(records.map { strip(it, allowed) })
    }

    private fun labelRows(record: ContactRecord) = record.raws.flatMap { it.rows }.filter { it.mimeType == Mime.GROUP }

    private fun isStarredLabel(row: app.parley.common.record.DataRow) = row[Col.GROUP_TITLE].equals(STARRED_LABEL, ignoreCase = true)
}
