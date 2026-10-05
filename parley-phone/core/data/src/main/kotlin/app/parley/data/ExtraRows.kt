package app.parley.data

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.Data
import app.parley.common.AltCalendar
import app.parley.common.people.Citizenship
import app.parley.common.people.CustomFields
import app.parley.common.people.Languages
import app.parley.common.people.NativeName
import app.parley.common.people.NativeNames
import app.parley.common.record.Mime

/**
 * The editor's rows beyond Android's usual kinds, for [ContactsRepository.save]: RFC 9554's name parts, the
 * languages and citizenship (Parley's rows), the name in their own language (a labelled nickname row), custom fields
 * (Google's kind in a Google account, Parley's elsewhere) and dates with the calendar they recur by. Like the rest of a save, only rows that really changed are written.
 */
internal object ExtraRows {
    class Writer(
        val insert: (String, ContentValues) -> Unit,
        val update: (Long, String, ContentValues) -> Unit,
        val delete: (Long, String) -> Unit,
        /** Saved rows to write again in place, so the kind reads back in the edited order ([app.parley.common.people.RowOrder.rewrite]). */
        val rewrite: Set<Long> = emptySet(),
        /** Deletes a row of [rewrite] and inserts it again with these values over its other columns. */
        val replace: (Long, String, ContentValues) -> Unit = { id, mime, v -> delete(id, mime); insert(mime, v) },
    )

    private fun t(s: String?) = s.orEmpty().trim()

    fun write(original: ContactDetails?, edited: ContactDetails, accountType: String?, w: Writer) {
        single(
            original?.namePartsId, Mime.NAME_PARTS, edited.secondSurname.isBlank() && edited.generation.isBlank(),
            ContentValues().apply {
                put(Data.DATA1, t(edited.secondSurname).ifEmpty { null })
                put(Data.DATA2, t(edited.generation).ifEmpty { null })
            },
            original != null && t(original.secondSurname) == t(edited.secondSurname) && t(original.generation) == t(edited.generation), w,
        )
        // A language's name as typed ("Spanish") is kept as its tag ("es"), which other apps and cards read.
        languages(original, edited, w)
        citizenships(original, edited, w)
        nativeName(original, edited, w)
        customFields(original?.customFields.orEmpty(), edited.customFields, accountType, w)
    }

    /**
     * The languages they speak, one row each in order: the rows read are written again in place (so the first stays
     * the first row), extra ones added, and left-over ones removed. With two or more, the first is marked primary,
     * which a card writes as `PREF=1`. An untouched value isn't rewritten, even one another app stored as a name.
     */
    private fun languages(original: ContactDetails?, edited: ContactDetails, w: Writer) {
        val typed = edited.languages.map(::t).filter { it.isNotEmpty() }
        val stored = typed.map { Languages.toStored(it) }
        val ids = original?.languageIds.orEmpty()
        val before = original?.languages.orEmpty().map(::t)
        val primaryChanged = (before.size >= 2) != (stored.size >= 2)
        stored.forEachIndexed { i, tag ->
            val v = ContentValues().apply {
                put(Data.DATA1, tag)
                if (stored.size >= 2) put(Data.IS_PRIMARY, if (i == 0) 1 else 0) else put(Data.IS_PRIMARY, 0)
            }
            val id = ids.getOrNull(i)
            val same = before.getOrNull(i).let { it != null && (it == typed[i] || it == tag) }
            when {
                id == null -> w.insert(Mime.LANGUAGE, v)
                !same || primaryChanged -> w.update(id, Mime.LANGUAGE, v)
            }
        }
        ids.drop(stored.size).forEach { w.delete(it, Mime.LANGUAGE) }
    }

    /** The countries they are a citizen of, one row each (ISO codes), written like [languages]. */
    private fun citizenships(original: ContactDetails?, edited: ContactDetails, w: Writer) {
        val codes = edited.citizenships.mapNotNull { Citizenship.toCode(it) ?: t(it).ifEmpty { null } }.distinct()
        val ids = original?.citizenshipIds.orEmpty()
        val before = original?.citizenships.orEmpty().map(::t)
        codes.forEachIndexed { i, code ->
            val id = ids.getOrNull(i)
            val v = ContentValues().apply { put(Data.DATA1, code) }
            when {
                id == null -> w.insert(Mime.CITIZENSHIP, v)
                before.getOrNull(i) != code -> w.update(id, Mime.CITIZENSHIP, v)
            }
        }
        ids.drop(codes.size).forEach { w.delete(it, Mime.CITIZENSHIP) }
    }

    /**
     * The name in their own language: a nickname row labelled "Name in Russian" ([NativeNames]), so it syncs and other
     * apps show it, while the main name stays the everyday one.
     */
    private fun nativeName(original: ContactDetails?, edited: ContactDetails, w: Writer) {
        val n = edited.nativeName
        val before = original?.nativeName
        fun key(x: NativeName?) = x?.let { listOf(t(it.shown), t(it.given), t(it.family), Languages.toStored(it.language)) }
        val values = ContentValues().apply { NativeNames.rowValues(n).forEach { (k, v) -> put(k, v) } }
        single(original?.nativeNameId, Mime.NICKNAME, n.isBlank, values, original != null && key(before) == key(n), w)
    }

    private fun single(id: Long?, mime: String, blank: Boolean, values: ContentValues, same: Boolean, w: Writer) {
        when {
            id != null && blank -> w.delete(id, mime)
            id != null && same -> Unit
            id != null -> w.update(id, mime, values)
            !blank -> w.insert(mime, values)
        }
    }

    /**
     * The kind custom field [f] is saved as. Google's field needs both halves: in a Google account a field takes
     * Google's kind with both and Parley's with one, and a row whose kind changes is deleted and inserted again (so
     * [ContactRowOrder] counts it as a new row).
     */
    fun customMime(f: CustomFieldItem, accountType: String?): String {
        val kind = f.mime ?: CustomFields.mimeFor(accountType, f.label, f.value)
        return when {
            accountType == CustomFields.GOOGLE_ACCOUNT -> CustomFields.mimeFor(accountType, f.label, f.value)
            kind == Mime.GOOGLE_CUSTOM_FIELD && (t(f.label).isEmpty() || t(f.value).isEmpty()) -> Mime.CUSTOM_FIELD
            else -> kind
        }
    }

    @Suppress("CyclomaticComplexMethod") // Delete, keep, update or insert, per row.
    private fun customFields(before: List<CustomFieldItem>, now: List<CustomFieldItem>, accountType: String?, w: Writer) {
        val keep = now.mapNotNull { it.id }.toSet()
        val old = before.filter { it.id != null }.associateBy { it.id }
        before.filter { it.id != null && it.id !in keep }.forEach { w.delete(it.id!!, it.mime ?: Mime.CUSTOM_FIELD) }
        now.forEach { f ->
            val kind = f.mime ?: CustomFields.mimeFor(accountType, f.label, f.value)
            val mime = customMime(f, accountType)
            val v = ContentValues().apply {
                put(Data.DATA1, t(f.label).ifEmpty { null })
                put(Data.DATA2, t(f.value).ifEmpty { null })
            }
            val prev = f.id?.let { old[it] }
            when {
                f.id != null && f.isBlank -> w.delete(f.id, kind)
                f.id != null && f.id !in w.rewrite && prev != null && t(prev.label) == t(f.label) && t(prev.value) == t(f.value) -> Unit
                f.id != null && mime != kind -> {
                    w.delete(f.id, kind)
                    w.insert(mime, v)
                }
                f.id != null && f.id in w.rewrite -> w.replace(f.id, mime, v)
                f.id != null -> w.update(f.id, mime, v)
                !f.isBlank -> w.insert(mime, v)
            }
        }
    }

    /**
     * Dates, with the calendar each recurs by in [AltCalendar.COLUMN] (cleared for a Gregorian one). The column is
     * written only when the calendar changed, so a value another app keeps there survives an edit of the date.
     */
    @Suppress("CyclomaticComplexMethod") // Delete, keep, update or insert, per row.
    fun events(before: List<EventItem>, now: List<EventItem>, w: Writer) {
        val keep = now.mapNotNull { it.id }.toSet()
        val old = before.filter { it.id != null }.associateBy { it.id }
        before.filter { it.id != null && it.id !in keep }.forEach { w.delete(it.id!!, Event.CONTENT_ITEM_TYPE) }
        fun label(e: EventItem) = e.label?.takeIf { e.type == Event.TYPE_CUSTOM }
        now.forEach { e ->
            val v = ContentValues().apply {
                put(Event.START_DATE, t(e.date))
                put(Event.TYPE, e.type)
                put(Event.LABEL, label(e))
                put(AltCalendar.COLUMN, e.calendar?.takeIf { it.isNotBlank() })
            }
            val prev = e.id?.let { old[it] }
            val sameCalendar = prev != null && prev.calendar.orEmpty() == e.calendar.orEmpty()
            if (sameCalendar) v.remove(AltCalendar.COLUMN)
            val same = prev != null && t(prev.date) == t(e.date) && prev.type == e.type && label(prev) == label(e) && sameCalendar
            when {
                e.id != null && e.date.isBlank() -> w.delete(e.id, Event.CONTENT_ITEM_TYPE)
                e.id != null && e.id in w.rewrite -> w.replace(e.id, Event.CONTENT_ITEM_TYPE, v)
                e.id != null && same -> Unit
                e.id != null -> w.update(e.id, Event.CONTENT_ITEM_TYPE, v)
                e.date.isNotBlank() -> w.insert(Event.CONTENT_ITEM_TYPE, v)
            }
        }
    }
}
