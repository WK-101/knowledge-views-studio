package app.parley.common.storage

/**
 * The stores of [PersistentStores] that keep something per contact under its Parley key (the lookup key of a device
 * contact, `parley-private:<id>` for a private one; see [app.parley.common.people.ContactRef]). Converting a contact
 * between variants re-keys every one of them, in `ContactKeys.rekey`, so nothing the user added is left behind under
 * the old key; a unit test keeps this list in step with the registry.
 */
object ContactKeyedStores {
    /** (kind, name): the store and what of it follows the contact. */
    val all: List<Pair<StoreKind, String>> = listOf(
        // Note for calls, usual messenger, the Circle's rhythm, relation links, dates remembered yearly.
        StoreKind.ROOM_TABLE to "contact_meta",
        // Logged moments and promises (their notes sealed).
        StoreKind.ROOM_TABLE to "interactions",
        // A device contact's expiry (a private one's is a column of its vault entry).
        StoreKind.ROOM_TABLE to "temporary_contacts",
        // The call-screen picture.
        StoreKind.FILES to "call_backgrounds",
        // Who Parley starred for a label's Do Not Disturb choice.
        StoreKind.PREFS to "parley_extras",
    )

    /** The registry entries, or null for a name the registry doesn't know (the test fails on it). */
    fun resolved(): List<PersistentStore?> = all.map { (kind, name) -> PersistentStores.find(kind, name) }
}
