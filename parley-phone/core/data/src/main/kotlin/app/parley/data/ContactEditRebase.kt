package app.parley.data

import app.parley.common.people.ThreeWayMerge
import app.parley.common.people.ThreeWayMerge.Side

/**
 * Puts an editor draft on top of the version of a contact that another app or a sync wrote while it was open: the
 * result keeps their row ids (so a save updates rows that exist) and takes, field by field, the side the user or the
 * merge chose. See [ThreeWayMerge].
 */
object ContactEditRebase {
    enum class Field { NAME, NICKNAME, COMPANY, NOTE, PHONES, EMAILS, WEBSITES, RELATIONS, ADDRESSES, EVENTS, HANDLES, LABELS }

    /** A field both sides changed: what each side holds, as text to show. */
    data class Conflict(val field: Field, val mine: String, val theirs: String)

    private fun t(s: String) = s.trim()

    private fun items(l: List<DataItem>) = l.filter { it.value.isNotBlank() }.map { Triple(t(it.value), it.type, it.label?.takeIf { _ -> it.type == 0 }) }

    /** A field's content, without ids and blank new rows, for comparing sides. */
    private fun content(d: ContactDetails, f: Field): Any = when (f) {
        Field.NAME -> listOf(d.prefix, d.given, d.middle, d.family, d.suffix, d.phoneticGiven, d.phoneticFamily).map(::t)
        Field.NICKNAME -> t(d.nickname)
        Field.COMPANY -> t(d.company) to t(d.title)
        Field.NOTE -> t(d.note)
        Field.PHONES -> items(d.phones)
        Field.EMAILS -> items(d.emails)
        Field.WEBSITES -> items(d.websites)
        Field.RELATIONS -> items(d.relations)
        Field.ADDRESSES -> d.addresses.filterNot { it.isBlank }.map { it.copy(id = null, label = it.label?.takeIf { _ -> it.type == 0 }) }
        Field.EVENTS -> d.events.filter { it.date.isNotBlank() }.map { it.copy(id = null, label = it.label?.takeIf { _ -> it.type == 0 }) }
        Field.HANDLES -> d.handles.filter { it.value.isNotBlank() }.map { it.copy(id = null, value = t(it.value)) }
        Field.LABELS -> d.groupIds
    }

    /** A field's content as one line of text for the merge choices. */
    fun text(d: ContactDetails, f: Field): String = when (f) {
        Field.NAME -> d.composedName
        Field.NICKNAME -> t(d.nickname)
        Field.COMPANY -> listOf(d.company, d.title).map(::t).filter { it.isNotEmpty() }.joinToString(" · ")
        Field.NOTE -> t(d.note)
        Field.PHONES -> d.phones.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.EMAILS -> d.emails.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.WEBSITES -> d.websites.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.RELATIONS -> d.relations.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.ADDRESSES -> d.addresses.filterNot { it.isBlank }.joinToString("; ") { it.formatted }
        Field.EVENTS -> d.events.map { t(it.date) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.HANDLES -> d.handles.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.LABELS -> d.groupIds.size.toString()
    }

    private fun fields(base: ContactDetails?, mine: ContactDetails, theirs: ContactDetails) =
        Field.entries.associateWith { f -> Triple(base?.let { content(it, f) }, content(mine, f), content(theirs, f)) }

    /** The fields both sides changed (with no [base], every field that differs). */
    fun conflicts(base: ContactDetails?, mine: ContactDetails, theirs: ContactDetails): List<Conflict> =
        fields(base, mine, theirs).mapNotNull { (f, v) ->
            if (ThreeWayMerge.merge(v.first, v.second, v.third, base != null) is ThreeWayMerge.Result.Conflict) {
                Conflict(f, text(mine, f), text(theirs, f))
            } else {
                null
            }
        }

    /**
     * The draft to save over [theirs]: each field from the side the merge settles on, and conflicts from [picks]
     * ([fallback] when a conflict has no pick). "Keep mine" is every field from [mine]: pass [Side.MINE] as the
     * fallback with no base.
     */
    fun rebase(base: ContactDetails?, mine: ContactDetails, theirs: ContactDetails, picks: Map<Field, Side>, fallback: Side = Side.MINE): ContactDetails {
        val sides = ThreeWayMerge.sides(fields(base, mine, theirs), picks, fallback, base != null)
        fun mineFor(f: Field) = sides[f] == Side.MINE
        var out = theirs.copy(context = mine.context, pinnedNote = mine.pinnedNote, messengerPrefs = mine.messengerPrefs)
        fun <T> ids(l: List<T>, their: List<T>, id: (T) -> Long?, withId: (T, Long?) -> T) =
            ThreeWayMerge.rebaseIds(l, their.mapNotNull(id).toSet(), id, withId)
        fun rows(l: List<DataItem>, their: List<DataItem>) = ids(l, their, { it.id }) { r, i -> r.copy(id = i) }
        if (mineFor(Field.NAME)) {
            out = out.copy(
                prefix = mine.prefix, given = mine.given, middle = mine.middle, family = mine.family, suffix = mine.suffix,
                phoneticGiven = mine.phoneticGiven, phoneticFamily = mine.phoneticFamily,
            )
        }
        if (mineFor(Field.NICKNAME)) out = out.copy(nickname = mine.nickname)
        if (mineFor(Field.COMPANY)) out = out.copy(company = mine.company, title = mine.title)
        if (mineFor(Field.NOTE)) out = out.copy(note = mine.note)
        if (mineFor(Field.PHONES)) out = out.copy(phones = rows(mine.phones, theirs.phones))
        if (mineFor(Field.EMAILS)) out = out.copy(emails = rows(mine.emails, theirs.emails))
        if (mineFor(Field.WEBSITES)) out = out.copy(websites = rows(mine.websites, theirs.websites))
        if (mineFor(Field.RELATIONS)) out = out.copy(relations = rows(mine.relations, theirs.relations))
        if (mineFor(Field.ADDRESSES)) out = out.copy(addresses = ids(mine.addresses, theirs.addresses, { it.id }) { r, i -> r.copy(id = i) })
        if (mineFor(Field.EVENTS)) out = out.copy(events = ids(mine.events, theirs.events, { it.id }) { r, i -> r.copy(id = i) })
        if (mineFor(Field.HANDLES)) out = out.copy(handles = ids(mine.handles, theirs.handles, { it.id }) { r, i -> r.copy(id = i) })
        if (mineFor(Field.LABELS)) out = out.copy(groupIds = mine.groupIds)
        return out
    }
}
