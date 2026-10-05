package app.parley.data

import app.parley.common.people.ThreeWayMerge
import app.parley.common.people.ThreeWayMerge.Side

/**
 * Puts an editor draft on top of the version of a contact that another app or a sync wrote while it was open: the
 * result keeps their row ids (so a save updates rows that exist) and takes, field by field, the side the user or the
 * merge chose. See [ThreeWayMerge].
 */
object ContactEditRebase {
    enum class Field {
        NAME, NICKNAME, COMPANY, NOTE, PHONES, EMAILS, WEBSITES, RELATIONS, ADDRESSES, EVENTS, HANDLES, LABELS, PRONOUNS, LANGUAGE, CUSTOM_FIELDS,
        NATIVE_NAME, CITIZENSHIP,
    }

    /** A field both sides changed: what each side holds, as text to show. */
    data class Conflict(val field: Field, val mine: String, val theirs: String)

    private fun t(s: String) = s.trim()

    private fun items(l: List<DataItem>) = l.filter { it.value.isNotBlank() }.map { Triple(t(it.value), it.type, it.label?.takeIf { _ -> it.type == 0 }) }

    /** A field's content, without ids and blank new rows, for comparing sides. */
    @Suppress("CyclomaticComplexMethod") // One branch per field.
    private fun content(d: ContactDetails, f: Field): Any = when (f) {
        Field.NAME -> listOf(
            d.prefix, d.given, d.middle, d.family, d.suffix, d.phoneticGiven, d.phoneticFamily, d.phoneticMiddle, d.secondSurname, d.generation,
        ).map(::t)
        Field.NICKNAME -> t(d.nickname)
        Field.COMPANY -> listOf(d.company, d.title, d.department).map(::t)
        Field.NOTE -> t(d.note)
        Field.PHONES -> items(d.phones)
        Field.EMAILS -> items(d.emails)
        Field.WEBSITES -> items(d.websites)
        Field.RELATIONS -> items(d.relations)
        Field.ADDRESSES -> d.addresses.filterNot { it.isBlank }.map { it.copy(id = null, label = it.label?.takeIf { _ -> it.type == 0 }) }
        Field.EVENTS -> d.events.filter { it.date.isNotBlank() }.map { it.copy(id = null, label = it.label?.takeIf { _ -> it.type == 0 }) }
        Field.HANDLES -> d.handles.filter { it.value.isNotBlank() }.map { it.copy(id = null, value = t(it.value)) }
        Field.LABELS -> d.groupIds
        Field.PRONOUNS -> t(d.pronouns)
        Field.LANGUAGE -> d.languages.map(::t).filter { it.isNotEmpty() }
        Field.CUSTOM_FIELDS -> d.customFields.filterNot { it.isBlank }.map { t(it.label) to t(it.value) }
        Field.NATIVE_NAME -> d.nativeName.let { listOf(it.shown, it.given, it.family, it.language).map(::t) }
        Field.CITIZENSHIP -> d.citizenships.map(::t).filter { it.isNotEmpty() }
    }

    /** A field's content as one line of text for the merge choices. */
    @Suppress("CyclomaticComplexMethod") // One branch per field.
    fun text(d: ContactDetails, f: Field): String = when (f) {
        Field.NAME -> d.composedName
        Field.NICKNAME -> t(d.nickname)
        Field.COMPANY -> listOf(d.company, d.department, d.title).map(::t).filter { it.isNotEmpty() }.joinToString(" · ")
        Field.NOTE -> t(d.note)
        Field.PHONES -> d.phones.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.EMAILS -> d.emails.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.WEBSITES -> d.websites.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.RELATIONS -> d.relations.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.ADDRESSES -> d.addresses.filterNot { it.isBlank }.joinToString("; ") { it.formatted }
        Field.EVENTS -> d.events.map { t(it.date) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.HANDLES -> d.handles.map { t(it.value) }.filter { it.isNotEmpty() }.joinToString(", ")
        Field.LABELS -> d.groupIds.size.toString()
        Field.PRONOUNS -> t(d.pronouns)
        Field.LANGUAGE -> app.parley.common.people.Languages.join(d.languages)
        Field.CUSTOM_FIELDS -> d.customFields.filterNot { it.isBlank }.joinToString(", ") { app.parley.common.people.CustomFields.display(it.label, it.value) }
        Field.NATIVE_NAME -> t(d.nativeName.shown)
        Field.CITIZENSHIP -> d.citizenships.map(::t).filter { it.isNotEmpty() }.joinToString(", ")
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
        // Parley's own fields (relations kept in Parley only too) aren't the address book's: they stay as edited.
        var out = theirs.copy(
            context = mine.context, pinnedNote = mine.pinnedNote, messengerPrefs = mine.messengerPrefs, parleyRelations = mine.parleyRelations,
        )
        fun <T> ids(l: List<T>, their: List<T>, id: (T) -> Long?, withId: (T, Long?) -> T) =
            ThreeWayMerge.rebaseIds(l, their.mapNotNull(id).toSet(), id, withId)
        fun rows(l: List<DataItem>, their: List<DataItem>) = ids(l, their, { it.id }) { r, i -> r.copy(id = i) }
        if (mineFor(Field.NAME)) {
            out = out.copy(
                prefix = mine.prefix, given = mine.given, middle = mine.middle, family = mine.family, suffix = mine.suffix,
                phoneticGiven = mine.phoneticGiven, phoneticFamily = mine.phoneticFamily, phoneticMiddle = mine.phoneticMiddle,
                secondSurname = mine.secondSurname, generation = mine.generation,
            )
        }
        if (mineFor(Field.NICKNAME)) out = out.copy(nickname = mine.nickname)
        if (mineFor(Field.COMPANY)) out = out.copy(company = mine.company, title = mine.title, department = mine.department)
        if (mineFor(Field.NOTE)) out = out.copy(note = mine.note)
        if (mineFor(Field.PHONES)) out = out.copy(phones = rows(mine.phones, theirs.phones))
        if (mineFor(Field.EMAILS)) out = out.copy(emails = rows(mine.emails, theirs.emails))
        if (mineFor(Field.WEBSITES)) out = out.copy(websites = rows(mine.websites, theirs.websites))
        if (mineFor(Field.RELATIONS)) out = out.copy(relations = rows(mine.relations, theirs.relations))
        if (mineFor(Field.ADDRESSES)) out = out.copy(addresses = ids(mine.addresses, theirs.addresses, { it.id }) { r, i -> r.copy(id = i) })
        if (mineFor(Field.EVENTS)) out = out.copy(events = ids(mine.events, theirs.events, { it.id }) { r, i -> r.copy(id = i) })
        if (mineFor(Field.HANDLES)) out = out.copy(handles = ids(mine.handles, theirs.handles, { it.id }) { r, i -> r.copy(id = i) })
        if (mineFor(Field.LABELS)) out = out.copy(groupIds = mine.groupIds)
        if (mineFor(Field.PRONOUNS)) out = out.copy(pronouns = mine.pronouns)
        if (mineFor(Field.LANGUAGE)) out = out.copy(languages = mine.languages)
        if (mineFor(Field.NATIVE_NAME)) out = out.copy(nativeName = mine.nativeName)
        if (mineFor(Field.CITIZENSHIP)) out = out.copy(citizenships = mine.citizenships)
        if (mineFor(Field.CUSTOM_FIELDS)) out = out.copy(customFields = ids(mine.customFields, theirs.customFields, { it.id }) { r, i -> r.copy(id = i) })
        return out
    }

    /**
     * [draft] (an edit of another copy of the contact, or of a contact that has since been linked, unlinked or
     * re-aggregated) with only row ids that belong to [onto]: a row keeps its id when [onto] has it, else takes the id
     * of an unclaimed row of [onto] with the same content, else becomes a new row. The contact, name, note and
     * raw-contact bookkeeping all come from [onto], so a save can only ever write rows of the copy it asserts.
     */
    fun adopt(draft: ContactDetails, onto: ContactDetails): ContactDetails {
        fun <T> ids(mine: List<T>, theirs: List<T>, id: (T) -> Long?, content: (T) -> Any, withId: (T, Long?) -> T): List<T> {
            val free = theirs.mapNotNull { id(it) }.toMutableSet()
            val kept = mine.map { row -> id(row)?.takeIf { free.remove(it) } }
            val byContent = HashMap<Any, ArrayDeque<Long>>()
            theirs.forEach { row -> id(row)?.takeIf { it in free }?.let { byContent.getOrPut(content(row)) { ArrayDeque() }.addLast(it) } }
            return mine.mapIndexed { i, row -> withId(row, kept[i] ?: byContent[content(row)]?.removeFirstOrNull()) }
        }
        fun item(d: DataItem): Any = Triple(t(d.value), d.type, d.label?.takeIf { d.type == 0 })
        fun rows(mine: List<DataItem>, theirs: List<DataItem>) = ids(mine, theirs, { it.id }, ::item) { r, i -> r.copy(id = i) }
        return draft.copy(
            id = onto.id, lookupKey = onto.lookupKey, displayName = onto.displayName, photoUri = onto.photoUri,
            nameId = onto.nameId, nicknameId = onto.nicknameId, orgId = onto.orgId, noteId = onto.noteId, pronounsId = onto.pronounsId,
            namePartsId = onto.namePartsId, languageIds = onto.languageIds, languagePrimaryId = onto.languagePrimaryId,
            nativeNameId = onto.nativeNameId, citizenshipIds = onto.citizenshipIds,
            customFields = ids(draft.customFields, onto.customFields, { it.id }, { t(it.label) to t(it.value) }) { r, i ->
                r.copy(id = i, mime = i?.let { onto.customFields.firstOrNull { f -> f.id == it }?.mime })
            },
            phones = rows(draft.phones, onto.phones), emails = rows(draft.emails, onto.emails),
            websites = rows(draft.websites, onto.websites), relations = rows(draft.relations, onto.relations),
            addresses = ids(draft.addresses, onto.addresses, { it.id }, { it.copy(id = null) }) { r, i -> r.copy(id = i) },
            events = ids(draft.events, onto.events, { it.id }, { it.copy(id = null) }) { r, i -> r.copy(id = i) },
            handles = ids(draft.handles, onto.handles, { it.id }, { it.copy(id = null, value = t(it.value)) }) { r, i -> r.copy(id = i) },
            rawContacts = onto.rawContacts, editRawId = onto.editRawId, editRawVersion = onto.editRawVersion,
            writableRawIds = onto.writableRawIds, readOnlyDataIds = onto.readOnlyDataIds,
        )
    }

    /** Whether any row of [d] points at a data row (ids of the copy it was loaded from). */
    fun hasRowIds(d: ContactDetails): Boolean =
        listOf(d.phones, d.emails, d.websites, d.relations).any { l -> l.any { it.id != null } } ||
            d.addresses.any { it.id != null } || d.events.any { it.id != null } || d.handles.any { it.id != null } ||
            d.customFields.any { it.id != null } || d.languageIds.isNotEmpty() || d.citizenshipIds.isNotEmpty() ||
            listOf(d.nameId, d.nicknameId, d.orgId, d.noteId, d.pronounsId, d.namePartsId, d.nativeNameId, d.editRawId).any { it != null }
}
