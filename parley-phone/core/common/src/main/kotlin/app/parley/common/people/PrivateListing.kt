package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry

/**
 * Parley's own lists (Contacts, Favourites, the Circle) show private contacts among the others, in name order, with a
 * lock badge; they are never added to the list other apps can query. A private contact's row has the id
 * [ContactRef.navId] (negative) and its Parley key ([ContactRef.privateKey]) as its lookup key, so a tap opens the
 * same contact page and per-contact data (Circle, logged moments) is found under the same key as on that page.
 */
object PrivateListing {
    /** A private contact as a list row. [photoUri] is the in-app photo; [phones] its numbers. */
    fun row(vaultId: Long, name: String, numbers: List<String>, starred: Boolean, photoUri: String?): ContactSummary =
        ContactSummary(
            id = ContactRef.Private(vaultId).navId, lookupKey = ContactRef.privateKey(vaultId), displayName = name, photoUri = photoUri,
            starred = starred, phones = numbers.mapIndexed { i, n -> PhoneEntry(n, MOBILE, null, isPrimary = i == 0 && numbers.size > 1) },
        )

    /** Whether a list row is a private contact. */
    fun isPrivate(row: ContactSummary): Boolean = row.id < 0

    /**
     * [device] (already in display order) with [private] merged in by [compare] on the shown name; ties keep the device
     * contact first, and the device list's own order is never changed.
     */
    fun merge(device: List<ContactSummary>, private: List<ContactSummary>, compare: Comparator<String>): List<ContactSummary> {
        if (private.isEmpty()) return device
        val extra = private.sortedWith { a, b -> compare.compare(a.displayName, b.displayName) }
        val out = ArrayList<ContactSummary>(device.size + extra.size)
        var j = 0
        for (d in device) {
            while (j < extra.size && compare.compare(extra[j].displayName, d.displayName) < 0) out += extra[j++]
            out += d
        }
        while (j < extra.size) out += extra[j++]
        return out
    }

    /** Android's "mobile" phone type, the label private numbers are shown with in lists. */
    private const val MOBILE = 2
}
