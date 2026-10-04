package app.parley.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import app.parley.common.people.AddressParts
import app.parley.common.people.Handles
import app.parley.common.people.RowEdits
import app.parley.common.record.Mime
import app.parley.common.record.WorkRow

/**
 * The data rows one [ContactsRepository.save] writes, one kind at a time, as operations added to [ops]. Only rows that
 * really changed are written, so a sync adapter uploads (and other apps see) just the edit; read-only rows ([locked])
 * are shown locked in the editor and never written. [changed] collects the fields written, for the write log.
 */
internal class ContactRowWriter(
    private val ops: MutableList<ContentProviderOperation>,
    /** Points a new row at the raw contact: the one edited, or the one this batch creates. */
    private val insertTarget: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder,
    private val locked: Set<Long>,
    /** Rows moved in the editor, written again so each kind reads back in the order chosen ([ContactRowOrder]). */
    private val rewrite: Set<Long>,
    /** The other columns of the rows in [rewrite], kept when they are written again. */
    private val kept: Map<Long, ContentValues>,
    private val fieldName: (String) -> String,
) {
    val changed = LinkedHashSet<String>()

    fun insert(mime: String, values: ContentValues) {
        ops += insertTarget(ContentProviderOperation.newInsert(Data.CONTENT_URI)).withValue(Data.MIMETYPE, mime).withValues(values).build()
        changed += fieldName(mime)
    }

    fun update(id: Long, mime: String, values: ContentValues) {
        if (id in locked) return
        ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(Data.CONTENT_URI, id)).withValues(values).build()
        changed += fieldName(mime)
    }

    fun delete(id: Long, mime: String) {
        if (id in locked) return
        ops += ContentProviderOperation.newDelete(ContentUris.withAppendedId(Data.CONTENT_URI, id)).build()
        changed += fieldName(mime)
    }

    /** Writes a moved row again in place: deleted, then inserted with [values] over its other columns. */
    fun replace(id: Long, mime: String, values: ContentValues) {
        delete(id, mime)
        insert(mime, ContentValues(kept[id] ?: ContentValues()).apply { putAll(values) })
    }

    /** The same operations for [ExtraRows]. */
    val extraWriter: ExtraRows.Writer get() = ExtraRows.Writer(::insert, ::update, ::delete, rewrite, ::replace)

    /** A kind with at most one row: removed when [blank], left alone when [same], else updated or added. */
    private fun single(id: Long?, mime: String, blank: Boolean, values: ContentValues, same: Boolean = false) {
        when {
            id != null && blank -> delete(id, mime)
            id != null && same -> Unit
            id != null -> update(id, mime, values)
            !blank -> insert(mime, values)
        }
    }

    /** The name, nickname and pronouns (Parley's own row: Android has no kind for them, written like the nickname). */
    fun names(original: ContactDetails?, edited: ContactDetails) {
        val nameValues = ContentValues().apply {
            put(StructuredName.DISPLAY_NAME, edited.composedName.ifBlank { null })
            put(StructuredName.PREFIX, edited.prefix.trim().ifEmpty { null })
            put(StructuredName.GIVEN_NAME, edited.given.trim().ifEmpty { null })
            put(StructuredName.MIDDLE_NAME, edited.middle.trim().ifEmpty { null })
            put(StructuredName.FAMILY_NAME, edited.family.trim().ifEmpty { null })
            put(StructuredName.SUFFIX, edited.suffix.trim().ifEmpty { null })
            put(StructuredName.PHONETIC_GIVEN_NAME, edited.phoneticGiven.trim().ifEmpty { null })
            put(StructuredName.PHONETIC_FAMILY_NAME, edited.phoneticFamily.trim().ifEmpty { null })
            put(StructuredName.PHONETIC_MIDDLE_NAME, edited.phoneticMiddle.trim().ifEmpty { null })
        }
        fun nameOf(d: ContactDetails) = listOf(d.prefix, d.given, d.middle, d.family, d.suffix, d.phoneticGiven, d.phoneticFamily, d.phoneticMiddle).map(::t)
        val o = original
        val sameName = o != null && nameOf(o) == nameOf(edited)
        val noName = edited.composedName.isBlank() && edited.phoneticGiven.isBlank() && edited.phoneticFamily.isBlank() && edited.phoneticMiddle.isBlank()
        single(o?.nameId, StructuredName.CONTENT_ITEM_TYPE, noName, nameValues, sameName)
        single(
            o?.nicknameId, Nickname.CONTENT_ITEM_TYPE, edited.nickname.isBlank(), ContentValues().apply { put(Nickname.NAME, edited.nickname.trim()) },
            o != null && t(o.nickname) == t(edited.nickname),
        )
        single(
            o?.pronounsId, Mime.PRONOUNS, edited.pronouns.isBlank(), ContentValues().apply { put(Data.DATA1, edited.pronouns.trim()) },
            o != null && t(o.pronouns) == t(edited.pronouns),
        )
    }

    /**
     * The work row: only company, title and department are written; a row that still holds an office, a job
     * description or the like ([holdsOthers], asked of the provider) is kept with those cleared rather than deleted
     * (see [WorkRow]).
     */
    @Suppress("CyclomaticComplexMethod") // The work row's four outcomes, each from a few fields.
    fun work(original: ContactDetails?, edited: ContactDetails, holdsOthers: (Long) -> Boolean) {
        val o = original
        val orgId = o?.orgId
        val orgValues = ContentValues().apply {
            put(Organization.COMPANY, edited.company.trim().ifEmpty { null })
            put(Organization.TITLE, edited.title.trim().ifEmpty { null })
            put(Organization.DEPARTMENT, edited.department.trim().ifEmpty { null })
        }
        val action = WorkRow.action(
            exists = orgId != null,
            // A new row also counts the office and job description it carries (a private contact made visible may
            // hold only those); an existing row's are the provider's, kept as they are.
            editedBlank = edited.company.isBlank() && edited.title.isBlank() && edited.department.isBlank() &&
                (orgId != null || (edited.officeLocation.isBlank() && edited.jobDescription.isBlank())),
            same = o != null && t(o.company) == t(edited.company) && t(o.title) == t(edited.title) && t(o.department) == t(edited.department),
            holdsOthers = orgId != null && holdsOthers(orgId),
        )
        when (action) {
            WorkRow.Action.NONE -> Unit
            WorkRow.Action.INSERT -> insert(
                Organization.CONTENT_ITEM_TYPE,
                // A new row (a private contact made visible) carries the parts the editor only shows.
                orgValues.apply {
                    edited.officeLocation.trim().ifEmpty { null }?.let { put(Organization.OFFICE_LOCATION, it) }
                    edited.jobDescription.trim().ifEmpty { null }?.let { put(Organization.JOB_DESCRIPTION, it) }
                },
            )
            WorkRow.Action.UPDATE, WorkRow.Action.CLEAR -> update(orgId!!, Organization.CONTENT_ITEM_TYPE, orgValues)
            WorkRow.Action.DELETE -> delete(orgId!!, Organization.CONTENT_ITEM_TYPE)
        }
    }

    fun note(original: ContactDetails?, edited: ContactDetails) {
        val same = original != null && t(original.note) == t(edited.note)
        single(original?.noteId, Note.CONTENT_ITEM_TYPE, edited.note.isBlank(), ContentValues().apply { put(Note.NOTE, edited.note.trim()) }, same)
    }

    /** Numbers, e-mails, websites and relations: kinds of value, type and custom label. */
    fun lists(original: ContactDetails?, edited: ContactDetails) {
        multi(original?.phones.orEmpty(), edited.phones, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, Phone.TYPE, Phone.LABEL)
        multi(original?.emails.orEmpty(), edited.emails, Email.CONTENT_ITEM_TYPE, Email.ADDRESS, Email.TYPE, Email.LABEL)
        multi(original?.websites.orEmpty(), edited.websites, Website.CONTENT_ITEM_TYPE, Website.URL, Website.TYPE, Website.LABEL)
        multi(original?.relations.orEmpty(), edited.relations, Relation.CONTENT_ITEM_TYPE, Relation.NAME, Relation.TYPE, Relation.LABEL)
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod") // One kind's columns, and a row's five outcomes.
    private fun multi(orig: List<DataItem>, now: List<DataItem>, mime: String, valueCol: String, typeCol: String, labelCol: String) {
        val keep = now.mapNotNull { it.id }.toSet()
        val before = orig.filter { it.id != null }.associateBy { it.id }
        orig.filter { it.id != null && it.id !in keep }.forEach { delete(it.id!!, mime) }
        now.forEach { item ->
            val v = ContentValues().apply {
                put(valueCol, item.value.trim())
                put(typeCol, item.type)
                put(labelCol, item.label?.takeIf { item.type == 0 })
            }
            val prev = item.id?.let { before[it] }
            val same = prev != null && t(prev.value) == t(item.value) && prev.type == item.type &&
                prev.label?.takeIf { prev.type == 0 } == item.label?.takeIf { item.type == 0 }
            when {
                item.id != null && item.value.isBlank() -> delete(item.id, mime)
                item.id != null && item.id in rewrite -> replace(item.id, mime, v)
                item.id != null && same -> Unit
                item.id != null -> update(item.id, mime, v)
                item.value.isNotBlank() -> insert(mime, v)
            }
        }
    }

    /** Messenger handles. Only Im and SIP rows are planned, so no other row can be touched (see [RowEdits]). */
    @Suppress("CyclomaticComplexMethod") // Each planned operation, and a moved row keeping its columns.
    fun handles(original: ContactDetails?, edited: ContactDetails) {
        fun handleRow(h: HandleItem) = Handles.toColumns(h.handle).let { (m, v) -> RowEdits.Row(h.id, m, v) }
        val planned = RowEdits.plan(
            original?.handles.orEmpty().map(::handleRow), edited.handles.map(::handleRow),
            setOf(Im.CONTENT_ITEM_TYPE, SipAddress.CONTENT_ITEM_TYPE), locked, rewrite,
        )
        fun cv(m: Map<String, String?>) = ContentValues().apply { m.forEach { (k, v) -> put(k, v) } }
        planned.forEach { op ->
            when (op) {
                is RowEdits.Op.Delete -> delete(op.id, op.mime)
                is RowEdits.Op.Update -> update(op.id, op.mime, cv(op.values))
                // A moved handle keeps its other columns; a new one is TYPE_OTHER (3) for both kinds, like other contacts apps.
                is RowEdits.Op.Insert -> op.replaces?.let { id -> kept[id] }?.let { k -> insert(op.mime, ContentValues(k).apply { putAll(cv(op.values)) }) }
                    ?: insert(op.mime, cv(op.values).apply { put(Data.DATA2, 3) })
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // An address's parts compared one by one, and a row's five outcomes.
    fun addresses(original: ContactDetails?, edited: ContactDetails) {
        val mime = StructuredPostal.CONTENT_ITEM_TYPE
        val keep = edited.addresses.mapNotNull { it.id }.toSet()
        val before = original?.addresses.orEmpty().filter { it.id != null }.associateBy { it.id }
        original?.addresses.orEmpty().filter { it.id != null && it.id !in keep }.forEach { delete(it.id!!, mime) }
        edited.addresses.forEach { a ->
            val v = ContentValues().apply {
                put(StructuredPostal.STREET, a.street.trim())
                put(StructuredPostal.POBOX, a.poBox.trim())
                put(StructuredPostal.NEIGHBORHOOD, a.neighborhood.trim())
                put(StructuredPostal.CITY, a.city.trim())
                put(StructuredPostal.REGION, a.region.trim())
                put(StructuredPostal.POSTCODE, a.postcode.trim())
                put(StructuredPostal.COUNTRY, a.country.trim())
                put(StructuredPostal.FORMATTED_ADDRESS, a.formatted)
                put(StructuredPostal.TYPE, a.type)
                put(StructuredPostal.LABEL, a.label?.takeIf { a.type == 0 })
                // RFC 9554's parts aren't edited: written with a new row only, an existing row keeps its own.
                if (a.id == null && a.parts.isNotBlank()) put(AddressParts.COLUMN, a.parts)
            }
            val prev = a.id?.let { before[it] }
            val same = prev != null && prev.type == a.type && prev.label?.takeIf { prev.type == 0 } == a.label?.takeIf { a.type == 0 } &&
                listOf(prev.street, prev.poBox, prev.neighborhood, prev.city, prev.region, prev.postcode, prev.country).map(::t) ==
                listOf(a.street, a.poBox, a.neighborhood, a.city, a.region, a.postcode, a.country).map(::t)
            when {
                a.id != null && a.isBlank -> delete(a.id, mime)
                a.id != null && a.id in rewrite -> replace(a.id, mime, v)
                a.id != null && same -> Unit
                a.id != null -> update(a.id, mime, v)
                !a.isBlank -> insert(mime, v)
            }
        }
    }

    /** Group membership (only groups of the target account can be assigned); leaving one needs the raw contact's id. */
    fun groups(original: ContactDetails?, edited: ContactDetails, rawId: Long?) {
        val before = original?.groupIds.orEmpty()
        (edited.groupIds - before).forEach { g -> insert(GroupMembership.CONTENT_ITEM_TYPE, ContentValues().apply { put(GroupMembership.GROUP_ROW_ID, g) }) }
        if (rawId == null) return
        (before - edited.groupIds).forEach { g ->
            ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection(
                    "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?",
                    arrayOf(rawId.toString(), GroupMembership.CONTENT_ITEM_TYPE, g.toString()),
                ).build()
            changed += fieldName(GroupMembership.CONTENT_ITEM_TYPE)
        }
    }

    /** A new or removed photo counts as a change; removing one deletes it from every writable copy. */
    fun photo(original: ContactDetails?, rawId: Long?, photo: Boolean, removePhoto: Boolean) {
        if (photo || removePhoto) changed += "Photo"
        if (!removePhoto) return
        (original?.writableRawIds.orEmpty() + listOfNotNull(rawId)).distinct().forEach { rid ->
            ops += ContentProviderOperation.newDelete(Data.CONTENT_URI)
                .withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(rid.toString(), Photo.CONTENT_ITEM_TYPE))
                .build()
        }
    }

    private fun t(s: String?) = s.orEmpty().trim()
}
