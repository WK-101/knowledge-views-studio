package app.parley.common.people

import app.parley.common.record.DataRow
import app.parley.common.record.Mime

/**
 * I4: data rows Parley doesn't edit (Google's "File as", user-defined fields, identity rows, any app's own kinds),
 * turned into read-only label/value lines for the contact page, so nothing looks lost. Rows Parley shows elsewhere
 * (the kinds it edits, photos, labels, handles) and messenger apps' own action rows are left out.
 */
object OtherFields {
    data class Field(val label: String, val value: String, val mimeType: String)

    const val GOOGLE_FILE_AS = "vnd.com.google.cursor.item/contact_file_as"
    const val GOOGLE_USER_FIELD = "vnd.com.google.cursor.item/contact_user_defined_field"
    const val GOOGLE_EXTERNAL_ID = "vnd.com.google.cursor.item/contact_external_id"
    const val GOOGLE_MISC = "vnd.com.google.cursor.item/contact_misc"
    const val GOOGLE_JOT = "vnd.com.google.cursor.item/contact_jot"
    const val GOOGLE_CALENDAR = "vnd.com.google.cursor.item/contact_calendar_link"
    const val GOOGLE_LANGUAGE = "vnd.com.google.cursor.item/contact_language"
    const val GOOGLE_KEYWORD = "vnd.com.google.cursor.item/contact_keyword"
    const val GOOGLE_HOBBY = "vnd.com.google.cursor.item/contact_hobby"

    /**
     * [rows] of one contact; [fromMessenger] says whether a row belongs to a messenger's account (those rows are
     * the "Message with…" actions shown under Messengers).
     */
    fun describe(rows: List<DataRow>, fromMessenger: (DataRow) -> Boolean = { false }): List<Field> {
        val out = ArrayList<Field>()
        for (r in rows) {
            if (r.mimeType in Mime.CORE || fromMessenger(r)) continue
            describe(r)?.let { out += it }
        }
        return out.distinctBy { Triple(it.label, it.value, it.mimeType) }
    }

    fun describe(r: DataRow): Field? {
        fun v(col: String) = r[col]?.trim().orEmpty()
        return when (r.mimeType) {
            GOOGLE_FILE_AS -> v("data1").ifEmpty { null }?.let { Field("File as", it, r.mimeType) }
            GOOGLE_USER_FIELD -> {
                // data1 = the field's name, data2 = its value.
                val name = v("data1")
                val value = v("data2")
                if (name.isEmpty() && value.isEmpty()) null else Field(name.ifEmpty { "Custom field" }, value.ifEmpty { "—" }, r.mimeType)
            }
            GOOGLE_EXTERNAL_ID -> v("data1").ifEmpty { null }?.let { Field(v("data3").ifEmpty { "External ID" }, it, r.mimeType) }
            GOOGLE_MISC -> listOfNotNull(
                v("data1").ifEmpty { null }?.let { "Billing: $it" },
                v("data2").ifEmpty { null }?.let { "Directory server: $it" },
                v("data3").ifEmpty { null }?.let { "Mileage: $it" },
            ).joinToString(" · ").ifEmpty { null }?.let { Field("More", it, r.mimeType) }
            GOOGLE_JOT -> v("data1").ifEmpty { null }?.let { Field("Jot", it, r.mimeType) }
            GOOGLE_CALENDAR -> v("data1").ifEmpty { null }?.let { Field("Calendar", it, r.mimeType) }
            GOOGLE_LANGUAGE -> v("data1").ifEmpty { null }?.let { Field("Language", it, r.mimeType) }
            GOOGLE_KEYWORD -> v("data1").ifEmpty { null }?.let { Field("Keyword", it, r.mimeType) }
            GOOGLE_HOBBY -> v("data1").ifEmpty { null }?.let { Field("Hobby", it, r.mimeType) }
            Mime.IDENTITY -> v("data1").ifEmpty { null }?.let { Field("Identity" + v("data2").ifEmpty { null }?.let { n -> " ($n)" }.orEmpty(), it, r.mimeType) }
            else -> generic(r)
        }
    }

    /** Any other kind: its first non-empty text column under a label made from the mimetype. */
    private fun generic(r: DataRow): Field? {
        val value = (1..14).firstNotNullOfOrNull { i -> r["data$i"]?.trim()?.takeIf { it.isNotEmpty() } } ?: return null
        return Field(labelOf(r.mimeType), value, r.mimeType)
    }

    /** "vnd.android.cursor.item/vnd.example.pet_name" → "Pet name". */
    fun labelOf(mime: String): String {
        val last = mime.substringAfterLast('/').substringAfterLast('.')
            .removePrefix("contact_").replace('_', ' ').replace('-', ' ').trim()
        return last.replaceFirstChar { it.uppercase() }.ifEmpty { "Other" }
    }
}
