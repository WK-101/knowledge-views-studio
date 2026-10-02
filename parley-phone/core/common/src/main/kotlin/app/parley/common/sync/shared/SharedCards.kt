package app.parley.common.sync.shared

import app.parley.common.Duplicates
import app.parley.common.PhoneIdentity
import app.parley.common.backup.RecordJson
import app.parley.common.people.ThreeWayMerge
import app.parley.common.people.ThreeWayMerge.Side
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.common.record.withoutMessengers
import app.parley.common.vcard.VCardMapper

/** The fields a shared label carries for a contact, each merged on its own. */
enum class CardField { NAME, NICKNAME, ORGANISATION, PHONES, EMAILS, ADDRESSES, WEBSITES, DATES, RELATIONS, NOTE, CHAT, PRONOUNS }

/**
 * A contact as a shared label carries it (docs/SHARED_LABELS.md, "Fields and conflicts"): the shared fields of the
 * contact in canonical form, one raw contact without an account, no photo, no labels, no flags. Field-by-field
 * three-way merge with [ThreeWayMerge], and the way back into a local contact ([overlay]).
 */
object SharedCards {
    private val FIELDS: Map<String, CardField> = mapOf(
        Mime.NAME to CardField.NAME,
        Mime.NICKNAME to CardField.NICKNAME,
        Mime.ORG to CardField.ORGANISATION,
        Mime.PHONE to CardField.PHONES,
        Mime.EMAIL to CardField.EMAILS,
        Mime.POSTAL to CardField.ADDRESSES,
        Mime.WEBSITE to CardField.WEBSITES,
        Mime.EVENT to CardField.DATES,
        Mime.RELATION to CardField.RELATIONS,
        Mime.NOTE to CardField.NOTE,
        Mime.IM to CardField.CHAT,
        Mime.SIP to CardField.CHAT,
        Mime.PRONOUNS to CardField.PRONOUNS,
    )

    fun fieldOf(mimeType: String): CardField? = FIELDS[mimeType]

    /** [record]'s shared part: canonical, only the shared kinds, under one raw contact. */
    fun project(record: ContactRecord): ContactRecord {
        val c = VCardMapper.canonical(record.withoutMessengers())
        val rows = c.raws.flatMap { it.rows }.filter { it.mimeType in FIELDS }
        return ContactRecord(key = "", displayName = c.displayName, raws = listOf(RawRecord(null, null, rows = rows)))
    }

    fun encode(card: ContactRecord): String = RecordJson.encode(card)

    /** A card from a file, or null when it isn't one; whatever it holds is projected again (only shared kinds). */
    fun decode(text: String): ContactRecord? = runCatching { project(RecordJson.decode(text) { null }) }.getOrNull()

    private fun rows(card: ContactRecord, field: CardField): List<DataRow> = card.raws.flatMap { it.rows }.filter { FIELDS[it.mimeType] == field }

    /** A field's content for comparing sides: its rows' identities, sorted (order and primary flags don't count). */
    fun content(card: ContactRecord, field: CardField): List<String> = rows(card, field).map(VCardMapper::rowIdentity).sorted()

    /** Identifies a card's content (for "did this contact change since the last sync"). */
    fun hash(card: ContactRecord): String =
        RecordJson.sha256Hex(CardField.entries.joinToString("\n") { f -> f.name + "=" + content(card, f).joinToString("\u0001") }.toByteArray())

    /** The fields that differ between [before] (null: a new contact, every filled field) and [after]. */
    fun changedFields(before: ContactRecord?, after: ContactRecord): Set<CardField> =
        CardField.entries.filter { f -> (before?.let { content(it, f) } ?: emptyList()) != content(after, f) }.toSet()

    /** A merge's outcome: the merged card (conflicts take [Merge.fallback]'s side) and the fields both sides changed. */
    class Merge(val card: ContactRecord, val conflicts: Set<CardField>, val sides: Map<CardField, Side>)

    /**
     * Merges [mine] and [theirs] against [base] (null: no version in common, as when a contact is first matched).
     * A field one side changed takes that side; without a base, a field only one side fills takes that side. A field
     * both changed differently is a conflict: [picks] decide it, else [fallback].
     */
    fun merge(base: ContactRecord?, mine: ContactRecord, theirs: ContactRecord, picks: Map<CardField, Side> = emptyMap(), fallback: Side = Side.MINE): Merge {
        val conflicts = LinkedHashSet<CardField>()
        val sides = CardField.entries.associateWith { f ->
            val m = content(mine, f)
            val t = content(theirs, f)
            val r = when {
                base == null && m.isEmpty() -> ThreeWayMerge.Result.Resolved(t, Side.THEIRS)
                base == null && t.isEmpty() -> ThreeWayMerge.Result.Resolved(m, Side.MINE)
                else -> ThreeWayMerge.merge(base?.let { content(it, f) }, m, t, hasBase = base != null)
            }
            when (r) {
                is ThreeWayMerge.Result.Resolved -> r.side
                is ThreeWayMerge.Result.Conflict -> {
                    conflicts += f
                    picks[f] ?: fallback
                }
            }
        }
        val rows = CardField.entries.flatMap { f -> rows(if (sides[f] == Side.MINE) mine else theirs, f) }
        val name = if (sides[CardField.NAME] == Side.MINE) mine.displayName else theirs.displayName
        return Merge(ContactRecord(key = "", displayName = name, raws = listOf(RawRecord(null, null, rows = rows))), conflicts, sides)
    }

    /**
     * [local] (a whole contact read from the address book) with its shared fields replaced by [shared]'s: everything
     * the label doesn't carry (other labels, other kinds, the star, ringtone and voicemail) stays as it is. The photo
     * row is left out: an in-place write keeps the contact's photo when the record brings none, and [local] may hold
     * only its thumbnail.
     */
    fun overlay(local: ContactRecord, shared: ContactRecord): ContactRecord {
        val kept = local.raws.flatMap { it.rows }.filter { it.mimeType !in FIELDS && it.mimeType != Mime.PHOTO }
        val raw = local.raws.firstOrNull()?.copy(rows = kept + shared.raws.flatMap { it.rows }) ?: RawRecord(null, null, rows = shared.raws.flatMap { it.rows })
        return local.copy(displayName = shared.displayName.ifBlank { local.displayName }, raws = listOf(raw))
    }

    /** [card] as a new contact in the label titled [labelTitle] (the label's membership travels as its title). */
    fun forImport(card: ContactRecord, labelTitle: String): ContactRecord {
        val group = DataRow(Mime.GROUP, mapOf(Col.GROUP_TITLE to labelTitle))
        return card.copy(raws = listOf(RawRecord(null, null, rows = card.raws.flatMap { it.rows } + group)))
    }

    /** A field as one line of text, for the "Changed on two phones" choice. */
    fun text(card: ContactRecord, field: CardField): String = rows(card, field).mapNotNull { r ->
        when (r.mimeType) {
            Mime.NAME -> r[Col.D1] ?: listOfNotNull(r[Col.D4], r[Col.D2], r[Col.D5], r[Col.D3], r[Col.D6]).joinToString(" ")
            Mime.ORG -> listOfNotNull(r[Col.D1], r[Col.D4]).joinToString(" · ")
            Mime.POSTAL -> r[Col.D1] ?: listOfNotNull(r[Col.D4], r[Col.D7], r[Col.D9], r[Col.D10]).joinToString(", ")
            else -> r[Col.D1]
        }?.trim()?.takeIf { it.isNotEmpty() }
    }.joinToString(", ")

    /** Phone numbers and e-mails as match keys: a contact received for the first time joins one that has any of them. */
    fun matchKeys(card: ContactRecord): Set<String> = card.raws.flatMap { it.rows }.mapNotNull { row ->
        when (row.mimeType) {
            Mime.PHONE -> row[Col.D1]?.let { PhoneIdentity.portableKey(it) }?.let { "p:$it" }
            Mime.EMAIL -> row[Col.D1]?.let { Duplicates.emailKey(it) }?.let { "e:$it" }
            else -> null
        }
    }.toSet()
}
