package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.ux.ListSections
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The first screenful of the Contacts list as it was last shown, so a cold start draws rows at once instead of a spinner
 * while the address book and the private contacts load; the real list then takes over row for row (same ids, same
 * order, same headers), so nothing moves when it does. Only what a row shows is kept: the name, the name it is sorted
 * by, the photo's in-app URI, the star, the first number and its section header, plus the favourites strip.
 *
 * Private contacts are kept only when they were listed when it was written ([Snapshot.withPrivate]), and shown again
 * only when [shown] says they may be now. Whenever private contacts are listed but may not be shown from here, nothing
 * is shown: the list waits and appears whole, never without its private contacts first and with them a moment later.
 */
object ListHead {
    /** Rows kept: more than a tall tablet shows. */
    const val ROWS = 60

    /** Favourites kept for the strip above the list. */
    const val FAVOURITES = 40

    @Serializable
    private data class Row(
        val id: Long,
        val key: String,
        val name: String,
        val sortName: String,
        val photo: String? = null,
        val starred: Boolean = false,
        val number: String? = null,
        /** The section header the row is under (its letter, a company, "Not called"…). */
        val sec: String? = null,
    )

    @Serializable
    private data class Kept(
        val rows: List<Row>,
        val favs: List<Row> = emptyList(),
        val withPrivate: Boolean = false,
        val sort: String = "",
    )

    /** What was kept, as the list showed it. */
    data class Snapshot(
        val rows: List<ListSections.Row<String, ContactSummary>>,
        val favourites: List<ContactSummary>,
        /** Private contacts were listed when it was kept: they are in it where they were. */
        val withPrivate: Boolean,
        /** The order the list was in ("Sort by"). */
        val sort: String,
    )

    /**
     * What may be shown now. [privateListed]: private contacts will be in the real list (they exist and aren't hidden
     * by discreet mode or a duress unlock). [privateMayShow]: they may be drawn from what was kept (not hidden, no duress
     * unlock, not locked with "Lock private contacts"). [sort]: the list's order now.
     */
    data class Access(val privateListed: Boolean, val privateMayShow: Boolean, val sort: String) {
        companion object {
            /** When the facts can't be read: nothing private, and nothing at all if private contacts may be listed. */
            val CLOSED = Access(privateListed = true, privateMayShow = false, sort = "")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun row(c: ContactSummary, section: String?): Row {
        val first = c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull()
        return Row(c.id, c.lookupKey, c.displayName, c.sortName, c.photoUri, c.starred, first?.number, section)
    }

    private fun summary(r: Row) = ContactSummary(
        r.id, r.key, r.name, r.photo, r.starred,
        phones = listOfNotNull(r.number?.let { PhoneEntry(it, 0, null, isPrimary = true) }), sortName = r.sortName,
    )

    /**
     * The head of [rows] (the list as shown, headers included) to keep. [withPrivate]: private contacts are listed now;
     * when false none is kept even if one slipped in.
     */
    fun encode(
        rows: List<ListSections.Row<String, ContactSummary>>,
        favourites: List<ContactSummary> = emptyList(),
        withPrivate: Boolean = false,
        sort: String = "",
    ): String {
        val kept = ArrayList<Row>(ROWS)
        var section: String? = null
        for (r in rows) {
            if (kept.size >= ROWS) break
            when (r) {
                is ListSections.Row.Header -> section = r.section
                is ListSections.Row.Item -> if (withPrivate || r.item.id > 0) kept += row(r.item, section)
            }
        }
        val favs = favourites.asSequence().filter { withPrivate || it.id > 0 }.take(FAVOURITES).map { row(it, null) }.toList()
        return json.encodeToString(Kept.serializer(), Kept(kept, favs, withPrivate, sort))
    }

    /** The kept rows, or null when there are none or they can't be read (then the list simply waits). */
    fun decode(text: String): Snapshot? = runCatching {
        val trimmed = text.trimStart()
        val kept = if (trimmed.startsWith("[")) {
            // Kept before headers and private rows were: address-book rows in name order.
            val rows = json.decodeFromString(ListSerializer(Row.serializer()), trimmed).filter { it.id > 0 }
            Kept(rows.map { it.copy(sec = ListSections.letterOf(it.sortName)) }, sort = ContactSort.NAME.name)
        } else {
            json.decodeFromString(Kept.serializer(), trimmed)
        }
        val rows = kept.rows.filter { kept.withPrivate || it.id > 0 }
        Snapshot(sectioned(rows), kept.favs.filter { kept.withPrivate || it.id > 0 }.map(::summary), kept.withPrivate, kept.sort)
    }.getOrNull()?.takeIf { it.rows.isNotEmpty() }

    private fun sectioned(rows: List<Row>): List<ListSections.Row<String, ContactSummary>> {
        val sections = rows.associate { it.id to it.sec }
        return ListSections.interleave(rows.map(::summary)) { sections[it.id] ?: ListSections.letterOf(it.sortName) }
    }

    /**
     * The rows to draw now from [s], or null to wait for the real list. Private rows only when they may show; when
     * private contacts will be listed, the head must hold them (else the list would grow under the finger).
     */
    fun shown(s: Snapshot?, a: Access): List<ListSections.Row<String, ContactSummary>>? {
        if (s == null || s.sort != a.sort) return null
        if (a.privateListed) return if (s.withPrivate && a.privateMayShow) s.rows else null
        return withoutPrivate(s.rows).takeIf { it.isNotEmpty() }
    }

    /** The favourites to draw with [shown]'s rows (none when the rows aren't shown). */
    fun shownFavourites(s: Snapshot?, a: Access): List<ContactSummary> {
        if (shown(s, a) == null || s == null) return emptyList()
        return if (a.privateListed) s.favourites else s.favourites.filter { it.id > 0 }
    }

    /** [rows] without private contacts: a list sorted the same, so still the head of the list without them. */
    fun withoutPrivate(rows: List<ListSections.Row<String, ContactSummary>>): List<ListSections.Row<String, ContactSummary>> {
        val kept = ArrayList<ContactSummary>(rows.size)
        val sections = HashMap<Long, String>()
        var section: String? = null
        for (r in rows) {
            when {
                r is ListSections.Row.Header -> section = r.section
                r is ListSections.Row.Item && r.item.id > 0 -> {
                    kept += r.item
                    sections[r.item.id] = section ?: ListSections.letterOf(r.item.sortName)
                }
            }
        }
        return ListSections.interleave(kept) { sections.getValue(it.id) }
    }

    /** [text] with every private row and favourite taken out, as kept when private contacts aren't listed. */
    fun dropPrivate(text: String): String? {
        val s = decode(text) ?: return null
        return encode(withoutPrivate(s.rows), s.favourites.filter { it.id > 0 }, withPrivate = false, sort = s.sort)
    }

    /** Whether what [kept] holds differs from [encoded] (so it is written only when it changed). */
    fun changed(encoded: String, kept: String?): Boolean = encoded != kept
}
