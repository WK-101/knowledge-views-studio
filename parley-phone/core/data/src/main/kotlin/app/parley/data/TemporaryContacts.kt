package app.parley.data

import android.provider.ContactsContract

/**
 * Temporary contacts: a name and a number that delete themselves (and, by default, their call history) after a
 * few days. Used after messaging an unsaved number ("Chat, then decide", "Save as a temporary contact").
 *
 * F5: they are **private** by default: kept in Parley's encrypted vault with an expiry, so WhatsApp and every other
 * app that can read contacts never sees them. [save] with `private = false` ("Save visible to other apps") keeps
 * the old behaviour, a phone-only system contact.
 *
 * This facade is the one entry
 * point for both kinds so callers don't need to know where each is stored.
 */
object TemporaryContacts {
    const val DEFAULT_DAYS = 7

    /** Where a temporary contact went: [id] is a vault id when [private], else a contacts-provider contact id. */
    data class Saved(val id: Long, val private: Boolean)

    suspend fun save(
        c: DataContainer,
        name: String,
        number: String,
        days: Int = DEFAULT_DAYS,
        private: Boolean = true,
        purgeHistory: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ): Saved? {
        val details = ContactDetails(
            given = name.trim().ifEmpty { number },
            phones = listOf(DataItem(value = number, type = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)),
        )
        val expiresAt = now + days * 86_400_000L
        if (private) {
            val id = c.vault.save(null, details, expiresAt = expiresAt, purgeHistory = purgeHistory)
            // F13: vault numbers are never kept in the "last messaged" record.
            c.messaging.forget(number)
            return Saved(id, private = true)
        }
        // Kept on this phone only (never synced to an account): it's meant to disappear. The store records the raw
        // contact it created, and only that one is ever deleted (F2; see app.parley.data.people.TemporaryContactStore).
        val id = c.temporaries.createPhone(details, expiresAt, purgeHistory) ?: return null
        return Saved(id, private = false)
    }
}
