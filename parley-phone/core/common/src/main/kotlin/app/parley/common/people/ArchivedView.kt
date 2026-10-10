package app.parley.common.people

import app.parley.common.TextSearch
import app.parley.common.record.ContactRecord

/**
 * What Parley shows of an archived contact (docs/CONTACT_MODEL.md, "Archived"): Contacts search's "Also archived" line,
 * and the read-only page an archived contact opens on, with Unarchive.
 */
object ArchivedView {
    /** One archived contact search can point at: [id] is the archive's id (device) or the vault's (private). */
    data class Match(val id: Long, val name: String, val private: Boolean)

    /** Contacts search's line under the results: the archived contacts whose name or number matches [query]. */
    data class AlsoArchived(val matches: List<Match>) {
        /** The one to open when there is only one; with several, the Archived list opens instead. */
        val single: Match? get() = matches.singleOrNull()
    }

    /** Archived contacts matching [query] (by name or number), sorted by name; null when the query is blank or none match. */
    fun alsoArchived(query: String, candidates: List<Pair<Match, List<String>>>): AlsoArchived? {
        if (query.isBlank()) return null
        val found = candidates.filter { (m, numbers) -> TextSearch.matches(query, m.name, numbers) }.map { it.first }
        return found.takeIf { it.isNotEmpty() }?.let { l -> AlsoArchived(l.sortedBy { it.name.lowercase() }) }
    }

    /** One line of an archived contact's read-only page. */
    data class Line(val kind: Kind, val value: String, val extra: String = "")

    enum class Kind { PHONE, EMAIL, ADDRESS, WORK, WEBSITE, DATE, NOTE }

    /**
     * The lines of [record] worth reading while it's archived, in the page's usual order and each value once. Its
     * photo, labels and the rest come back whole with Unarchive.
     */
    fun lines(record: ContactRecord): List<Line> {
        val rows = record.raws.flatMap { it.rows }
        fun of(mime: String) = rows.filter { it.mimeType == mime }
        val out = ArrayList<Line>()
        fun add(kind: Kind, value: String?, extra: String = "") {
            val v = value?.trim().orEmpty()
            if (v.isNotEmpty() && out.none { it.kind == kind && it.value == v }) out += Line(kind, v, extra.trim())
        }
        of(PHONE).forEach { add(Kind.PHONE, it["data1"]) }
        of(EMAIL).forEach { add(Kind.EMAIL, it["data1"]) }
        of(POSTAL).forEach { add(Kind.ADDRESS, it["data1"]) }
        of(ORG).forEach { add(Kind.WORK, it["data1"], it["data4"].orEmpty()) }
        of(WEBSITE).forEach { add(Kind.WEBSITE, it["data1"]) }
        of(EVENT).forEach { add(Kind.DATE, it["data1"]) }
        of(NOTE).forEach { add(Kind.NOTE, it["data1"]) }
        return out
    }

    private const val PHONE = "vnd.android.cursor.item/phone_v2"
    private const val EMAIL = "vnd.android.cursor.item/email_v2"
    private const val POSTAL = "vnd.android.cursor.item/postal-address_v2"
    private const val ORG = "vnd.android.cursor.item/organization"
    private const val WEBSITE = "vnd.android.cursor.item/website"
    private const val EVENT = "vnd.android.cursor.item/contact_event"
    private const val NOTE = "vnd.android.cursor.item/note"
}
