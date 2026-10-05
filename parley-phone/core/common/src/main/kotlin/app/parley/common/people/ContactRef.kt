package app.parley.common.people

/**
 * Which contact a page, a list row or a conversion is about, whatever its storage. Parley has one kind of contact;
 * where it is kept is a variant of it (docs/CONTACT_MODEL.md):
 *
 * - [Device]: in Android's address book, so apps with the contacts permission can read it.
 * - [Private]: only inside Parley, encrypted in the vault, invisible to every other app.
 *
 * Being temporary is not a storage: either kind can delete itself on a date.
 *
 * Screens and navigation carry one number for both, [navId]: the address book's contact id for device contacts and
 * the negated vault id for private ones (the convention the editor and "Save to" already used for a saved private
 * contact).
 */
sealed interface ContactRef {
    /** The id navigation and list rows use for this contact: positive for device contacts, negative for private ones. */
    val navId: Long

    /**
     * The key Parley keeps its own data about this person under (notes, the Circle, logged moments, the call-screen
     * picture, relation links): the address book's lookup key for a device contact, [privateKey] for a private one.
     */
    fun parleyKey(lookupKey: String): String

    data class Device(val contactId: Long) : ContactRef {
        override val navId: Long get() = contactId
        override fun parleyKey(lookupKey: String): String = lookupKey
    }

    data class Private(val vaultId: Long) : ContactRef {
        override val navId: Long get() = -vaultId
        override fun parleyKey(lookupKey: String): String = privateKey(vaultId)
    }

    companion object {
        /**
         * Keys of private contacts start with this. Android builds lookup keys from raw contact ids and account source
         * ids, which never start with it; and Parley never resolves such a key through the address book (see
         * [isPrivateKey]), so a private contact's data can't be handed to a device contact by a key sweep.
         */
        const val PRIVATE_KEY_PREFIX = "parley-private:"

        /** The ref for a navigation or list id ([navId]); null for 0, which names no contact. */
        fun ofNavId(id: Long): ContactRef? = when {
            id > 0 -> Device(id)
            id < 0 -> Private(-id)
            else -> null
        }

        /** The Parley key of private contact [vaultId]. */
        fun privateKey(vaultId: Long): String = PRIVATE_KEY_PREFIX + vaultId

        /** Whether [key] is a private contact's Parley key: such keys must never be looked up in the address book. */
        fun isPrivateKey(key: String?): Boolean = key != null && key.startsWith(PRIVATE_KEY_PREFIX)

        /**
         * Keys of archived contacts start with this: what Parley keeps about an archived contact waits under it while
         * the contact is out of the address book, and is never resolved there either ([isParleyOnlyKey]).
         */
        const val ARCHIVED_KEY_PREFIX = "parley-archived:"

        /** The Parley key of archived contact [archiveId]. */
        fun archivedKey(archiveId: Long): String = ARCHIVED_KEY_PREFIX + archiveId

        /** Whether [key] is an archived contact's Parley key. */
        fun isArchivedKey(key: String?): Boolean = key != null && key.startsWith(ARCHIVED_KEY_PREFIX)

        /** A key only Parley knows (a private or an archived contact's): never looked up in the address book. */
        fun isParleyOnlyKey(key: String?): Boolean = isPrivateKey(key) || isArchivedKey(key)

        /** The vault id of a private contact's Parley key, or null for any other key. */
        fun vaultIdOf(key: String?): Long? =
            key?.takeIf { isPrivateKey(it) }?.removePrefix(PRIVATE_KEY_PREFIX)?.toLongOrNull()?.takeIf { it > 0 }
    }
}
