package app.parley.data

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.Data
import app.parley.common.AltCalendar
import app.parley.common.people.CustomFields
import app.parley.common.people.Languages
import app.parley.common.record.Mime

/**
 * The editor's rows beyond Android's usual kinds, for [ContactsRepository.save]: RFC 9554's name parts and the
 * language (Parley's rows), custom fields (Google's kind in a Google account, Parley's elsewhere) and dates with the
 * calendar they recur by. Like the rest of a save, only rows that really changed are written.
 */
internal object ExtraRows {
    class Writer(
        val insert: (String, ContentValues) -> Unit,
        val update: (Long, String, ContentValues) -> Unit,
        val delete: (Long, String) -> Unit,
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
        val language = Languages.toStored(edited.language)
        single(
            original?.languageId, Mime.LANGUAGE, language.isBlank(), ContentValues().apply { put(Data.DATA1, language) },
            // An untouched value isn't rewritten, even one another app stored as a name.
            original != null && (t(original.language) == t(edited.language) || t(original.language) == language), w,
        )
        customFields(original?.customFields.orEmpty(), edited.customFields, accountType, w)
    }

    private fun single(id: Long?, mime: String, blank: Boolean, values: ContentValues, same: Boolean, w: Writer) {
        when {
            id != null && blank -> w.delete(id, mime)
            id != null && same -> Unit
            id != null -> w.update(id, mime, values)
            !blank -> w.insert(mime, values)
        }
    }

    @Suppress("CyclomaticComplexMethod") // Delete, keep, update or insert, per row.
    private fun customFields(before: List<CustomFieldItem>, now: List<CustomFieldItem>, accountType: String?, w: Writer) {
        val keep = now.mapNotNull { it.id }.toSet()
        val old = before.filter { it.id != null }.associateBy { it.id }
        before.filter { it.id != null && it.id !in keep }.forEach { w.delete(it.id!!, it.mime ?: Mime.CUSTOM_FIELD) }
        now.forEach { f ->
            val mime = f.mime ?: CustomFields.mimeFor(accountType)
            val v = ContentValues().apply {
                put(Data.DATA1, t(f.label).ifEmpty { null })
                put(Data.DATA2, t(f.value).ifEmpty { null })
            }
            val prev = f.id?.let { old[it] }
            when {
                f.id != null && f.isBlank -> w.delete(f.id, mime)
                f.id != null && prev != null && t(prev.label) == t(f.label) && t(prev.value) == t(f.value) -> Unit
                f.id != null -> w.update(f.id, mime, v)
                !f.isBlank -> w.insert(mime, v)
            }
        }
    }

    /** Dates, with the calendar each recurs by in [AltCalendar.COLUMN] (cleared for a Gregorian one). */
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
            val same = prev != null && t(prev.date) == t(e.date) && prev.type == e.type && label(prev) == label(e) &&
                prev.calendar.orEmpty() == e.calendar.orEmpty()
            when {
                e.id != null && e.date.isBlank() -> w.delete(e.id, Event.CONTENT_ITEM_TYPE)
                e.id != null && same -> Unit
                e.id != null -> w.update(e.id, Event.CONTENT_ITEM_TYPE, v)
                e.date.isNotBlank() -> w.insert(Event.CONTENT_ITEM_TYPE, v)
            }
        }
    }
}
