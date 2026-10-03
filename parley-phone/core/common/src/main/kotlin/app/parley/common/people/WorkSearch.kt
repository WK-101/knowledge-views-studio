package app.parley.common.people

/**
 * A contact from the work profile, found by a search. Read-only and held in memory only: it is never stored, backed
 * up or shown in a widget, and a tap hands it to the work profile's own contacts app (or calls [number]).
 *
 * [id] is the enterprise contact id (at or past [WorkSearch.ENTERPRISE_ID_BASE]); [lookupKey] is the enterprise
 * lookup key the provider gave with it.
 */
data class WorkContact(
    val id: Long,
    val lookupKey: String?,
    val name: String,
    val photoUri: String? = null,
    val number: String? = null,
    val numberLabel: String? = null,
)

/** How a search's work-profile results are cleaned up and merged: pure, so it's tested without a work profile. */
object WorkSearch {
    /** `ContactsContract.Contacts.ENTERPRISE_CONTACT_ID_BASE`: ids from the work profile start here. */
    const val ENTERPRISE_ID_BASE = 1_000_000_000L

    /** The most work results a search shows (they sit under the personal ones). */
    const val LIMIT = 20

    /** Longer queries are cut to this before reaching the provider. */
    const val MAX_QUERY = 100

    fun isWorkId(id: Long): Boolean = id >= ENTERPRISE_ID_BASE

    /** What is sent to the provider for [query], or null when there is nothing worth searching. */
    fun queryOf(query: String): String? = query.trim().take(MAX_QUERY).trim().takeIf { it.isNotEmpty() }

    /**
     * One row per work contact: the name matches first, in the provider's order, then the contacts found only by a
     * number. A name match takes its number from a number match of the same contact. Rows that aren't from the work
     * profile (or have no name) are left out, since the personal results already show those. At most [limit].
     */
    fun merge(byName: List<WorkContact>, byNumber: List<WorkContact>, limit: Int = LIMIT): List<WorkContact> {
        val numbers = LinkedHashMap<Long, WorkContact>()
        byNumber.forEach { if (it.number != null) numbers.putIfAbsent(it.id, it) }
        val out = LinkedHashMap<Long, WorkContact>()
        (byName + byNumber).asSequence()
            .filter { isWorkId(it.id) && it.name.isNotBlank() }
            .forEach { c ->
                if (out.size < limit && c.id !in out) {
                    val phone = numbers[c.id]
                    out[c.id] = if (c.number == null && phone != null) c.copy(number = phone.number, numberLabel = phone.numberLabel) else c
                }
            }
        return out.values.toList()
    }
}
