package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The first screenful of the Contacts list as it was last shown, so a cold start with a large address book draws rows
 * at once instead of a spinner while the whole book loads; the real list replaces it as soon as it is ready. Only
 * what a row shows is kept (name, photo, star, the first number), and never a private contact.
 */
object ListHead {
    /** Rows kept: more than a tall tablet shows. */
    const val ROWS = 60

    @Serializable
    private data class Row(
        val id: Long,
        val key: String,
        val name: String,
        val sortName: String,
        val photo: String? = null,
        val starred: Boolean = false,
        val number: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The head of [list] to keep: address-book contacts only, at most [ROWS]. */
    fun of(list: List<ContactSummary>): List<ContactSummary> = list.asSequence().filter { it.id > 0 }.take(ROWS).toList()

    fun encode(list: List<ContactSummary>): String = json.encodeToString(
        ListSerializer(Row.serializer()),
        of(list).map { c ->
            val first = c.phones.firstOrNull { it.isPrimary } ?: c.phones.firstOrNull()
            Row(c.id, c.lookupKey, c.displayName, c.sortName, c.photoUri, c.starred, first?.number)
        },
    )

    /** The kept rows, or null when there are none or they can't be read (then the list simply waits). */
    fun decode(text: String): List<ContactSummary>? = runCatching {
        json.decodeFromString(ListSerializer(Row.serializer()), text).filter { it.id > 0 }.map { r ->
            ContactSummary(
                r.id, r.key, r.name, r.photo, r.starred,
                phones = listOfNotNull(r.number?.let { PhoneEntry(it, 0, null, isPrimary = true) }), sortName = r.sortName,
            )
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Whether [list]'s head differs from what [kept] holds (so it is written only when it changed). */
    fun changed(list: List<ContactSummary>, kept: String?): Boolean = encode(list) != kept
}
