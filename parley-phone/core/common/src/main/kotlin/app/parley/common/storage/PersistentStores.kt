package app.parley.common.storage

/** Where a store lives. */
enum class StoreKind {
    /** A table of a Room database ([PersistentStore.location] is the database file name). */
    ROOM_TABLE,

    /** A Preferences DataStore (`files/datastore/<name>.preferences_pb`). */
    DATASTORE,

    /** A SharedPreferences file (`shared_prefs/<name>.xml`). */
    PREFS,

    /** A file or folder under `files/` ([PersistentStore.location] says which files folder). */
    FILES,

    /** Keys in the Android Keystore. */
    KEYSTORE,
}

/** What happens to a store in a backup. */
sealed interface StorePolicy {
    /** In the encrypted backup, and restored. */
    data object BackedUp : StorePolicy

    /**
     * In the encrypted backup only inside the private-contacts section, which is written only while the vault is
     * unlocked (like the private contacts themselves).
     */
    data object BackedUpWithVault : StorePolicy

    /** Stays on this phone, for [reason]. */
    data class DeviceLocal(val reason: String) : StorePolicy

    /** Keys and secrets: never exported, whatever the format. */
    data class Secret(val reason: String) : StorePolicy
}

/**
 * One place Parley keeps data. [name] is the table, file or preferences name exactly as the code uses it; [section] is
 * the backup section that carries it (for [StorePolicy.BackedUp] and [StorePolicy.BackedUpWithVault]).
 */
data class PersistentStore(
    val name: String,
    val kind: StoreKind,
    val policy: StorePolicy,
    val section: String? = null,
    /** Room: the database file; FILES: [FILES], [NO_BACKUP_FILES] or [DEVICE_PROTECTED_FILES]. */
    val location: String? = null,
) {
    val id: String get() = listOfNotNull(kind.name.lowercase(), location, name).joinToString(":")
    val backedUp: Boolean get() = policy == StorePolicy.BackedUp || policy == StorePolicy.BackedUpWithVault

    companion object {
        const val FILES = "files"
        const val NO_BACKUP_FILES = "no_backup"
        const val DEVICE_PROTECTED_FILES = "device_protected"
    }
}

/**
 * Every persistent store Parley has, with its backup policy. The backup is driven by it (every backed-up store must have
 * a section that writes it, or the backup reports that section as missing), "Delete all Parley data" wipes all of it,
 * and a unit test fails when code uses a preferences file, DataStore, database, table or files entry that isn't listed
 * here. Add a store here in the same change that adds it to the code.
 */
object PersistentStores {
    const val MAIN_DB = "parley.db"
    const val HISTORY_DB = "parley-history.db"

    /** Backup sections (the archive's own sections, and the feature sections inside the settings section). */
    object Sections {
        const val CONTACTS = "contacts"
        const val CALL_LOG = "call_log"
        const val CALL_HISTORY = "call_history"
        const val BLOCKING = "blocking"
        const val SPEED_DIAL = "speed_dial"
        const val SETTINGS = "settings"
        const val VAULT = "vault"
        const val PEOPLE = "people"
        const val CIRCLE = "circle"
        const val EXTRAS = "extras"
        const val CONTACT_NOTES = "contact_notes"
        const val CALL_TIME = "call_time"
        const val HISTORY_SETTINGS = "history_settings"
        const val SPAM_LISTS = "spam_lists"
        const val TO_CALL = "to_call"
        const val MENUS = "menus"
    }

    private fun table(name: String, policy: StorePolicy, section: String? = null, db: String = MAIN_DB) =
        PersistentStore(name, StoreKind.ROOM_TABLE, policy, section, db)

    private fun local(reason: String) = StorePolicy.DeviceLocal(reason)
    private val backedUp = StorePolicy.BackedUp

    val all: List<PersistentStore> = listOf(
        // ---- parley.db
        table("block_rules", backedUp, Sections.BLOCKING),
        table("blocked_calls", backedUp, Sections.BLOCKING),
        table("call_rings", local("Ring lengths are this phone's evidence for the one-ring guard, kept 60 days")),
        table("speed_dial", backedUp, Sections.SPEED_DIAL),
        table("number_sim", backedUp, Sections.SPEED_DIAL),
        table("journal", local("The 30-day undo of this phone's contact changes")),
        table("temporary_contacts", backedUp, Sections.CONTACT_NOTES),
        table("contact_meta", backedUp, Sections.CONTACT_NOTES),
        table("interactions", backedUp, Sections.CIRCLE),
        table("vault_contacts", StorePolicy.BackedUpWithVault, Sections.VAULT),
        table("vault_numbers", local("Fingerprints of private numbers, rebuilt from the private contacts on restore")),
        table("private_calls", StorePolicy.BackedUpWithVault, Sections.VAULT),
        table("call_notes", backedUp, Sections.CONTACT_NOTES),
        table("call_usage", local("What call-time allowances counted here; a restore must never change what supervision counted")),
        // ---- parley-history.db
        table("archived_calls", backedUp, Sections.CALL_HISTORY, HISTORY_DB),
        table("keep_forever", backedUp, Sections.CALL_HISTORY, HISTORY_DB),
        table("call_trash", local("The 30-day undo of deleted calls"), db = HISTORY_DB),
        // ---- DataStores
        PersistentStore("settings", StoreKind.DATASTORE, backedUp, Sections.SETTINGS),
        PersistentStore("people", StoreKind.DATASTORE, backedUp, Sections.PEOPLE),
        PersistentStore("history", StoreKind.DATASTORE, backedUp, Sections.HISTORY_SETTINGS),
        // ---- SharedPreferences
        PersistentStore("parley_calling", StoreKind.PREFS, backedUp, Sections.CALL_TIME),
        PersistentStore("parley_call_extras", StoreKind.PREFS, backedUp, Sections.CALL_TIME),
        PersistentStore("parley_extras", StoreKind.PREFS, backedUp, Sections.EXTRAS),
        PersistentStore("parley_circle", StoreKind.PREFS, backedUp, Sections.CIRCLE),
        PersistentStore("private_names", StoreKind.PREFS, backedUp, Sections.PEOPLE),
        PersistentStore("me_card", StoreKind.PREFS, backedUp, Sections.PEOPLE),
        // My card's id and signing key (sealed): in the encrypted backup, so your next phone signs your card as you.
        PersistentStore("my_card_identity", StoreKind.PREFS, backedUp, Sections.PEOPLE),
        // "Shared with": who got your card (sealed).
        PersistentStore("card_sharing", StoreKind.PREFS, backedUp, Sections.PEOPLE),
        // Contacts linked to their signed cards, by Parley key (sealed); private contacts' links travel with them.
        PersistentStore("card_links", StoreKind.PREFS, backedUp, Sections.PEOPLE),
        PersistentStore("parley_ring_facts", StoreKind.PREFS, local("Sealed with this phone's call-history key; kept 60 days")),
        PersistentStore("parley_call_quality", StoreKind.PREFS, local("Call quality facts, sealed with this phone's call-history key; kept 60 days")),
        PersistentStore(
            "parley_reputation", StoreKind.PREFS,
            local("What this phone's own calls say about numbers and ranges, keyed and sealed with the call-history key; rebuilt daily"),
        ),
        PersistentStore("messaging", StoreKind.PREFS, local("Which numbers you opened a chat with stays on this phone by design")),
        PersistentStore("temporary_due", StoreKind.PREFS, local("Which due temporary contacts this phone's notification is asking about")),
        PersistentStore("bulk_add", StoreKind.PREFS, local("Undo for recent \"Add several numbers\" batches")),
        PersistentStore("ux", StoreKind.PREFS, local("Tips already seen on this phone")),
        PersistentStore("crash_capture", StoreKind.PREFS, local("Crash reports of this phone, kept only when asked")),
        PersistentStore("diagnostics", StoreKind.PREFS, local("Diagnostics of this phone")),
        PersistentStore("account_diagnostics", StoreKind.PREFS, local("Account checks of this phone")),
        PersistentStore("parley_writes", StoreKind.PREFS, local("Which contact rows Parley wrote on this phone")),
        PersistentStore("contact_key_moves", StoreKind.PREFS, local("Contacts just made visible whose lookup key this phone hasn't listed yet")),
        PersistentStore("relation_mirrors", StoreKind.PREFS, local("Which relations Parley added to the other contact, by this phone's lookup keys")),
        PersistentStore("folder_sync", StoreKind.PREFS, local("Sync state with a folder picked on this phone")),
        PersistentStore("folder_sync_notice", StoreKind.PREFS, local("Which folder-sync pause was already notified")),
        PersistentStore("sync_watch", StoreKind.PREFS, local("What the sync watchdog last saw and already said, by this phone's lookup keys and accounts")),
        PersistentStore("markdown_export", StoreKind.PREFS, local("Export folder picked on this phone")),
        PersistentStore("parley_screening_guard", StoreKind.PREFS, local("Call-path safety state (emergency window)")),
        PersistentStore("parley_ring_boost", StoreKind.PREFS, local("Ring volume to restore after a crash")),
        PersistentStore("parley_missed_realert", StoreKind.PREFS, local("Missed-call reminder in progress")),
        // Kept by number, not by contact: it follows a contact made private or visible without re-keying.
        PersistentStore("to_call", StoreKind.PREFS, backedUp, Sections.TO_CALL),
        // Menu memory: keys sent per number and menu shortcuts (sealed); kept by number like the To call list.
        PersistentStore("menu_memory", StoreKind.PREFS, backedUp, Sections.MENUS),
        // A safe word is a secret: it never leaves this phone, not even in an encrypted backup.
        PersistentStore("family_safety", StoreKind.PREFS, local("Family safe words, helpers and expected-call windows, sealed on this phone")),
        // The car's Bluetooth address belongs to this phone's pairing; a new phone marks the car again.
        PersistentStore("parley_drive_profile", StoreKind.PREFS, local("The drive profile's cars (Bluetooth addresses of this phone's pairings) and switches")),
        PersistentStore("parley_roaming", StoreKind.PREFS, local("Assisted dialling switches and the trip the local-SIM hint was shown for")),
        PersistentStore("lists_updater", StoreKind.PREFS, local("Link with the companion app installed on this phone")),
        PersistentStore("dial_widgets", StoreKind.PREFS, local("Home-screen widgets of this launcher")),
        PersistentStore("parley_migrations", StoreKind.PREFS, local("Which one-time data migrations ran here")),
        PersistentStore("vault", StoreKind.PREFS, local("Private-contact fingerprint migration state")),
        PersistentStore("backup", StoreKind.PREFS, StorePolicy.Secret("The backup keys and the backup folder of this phone")),
        PersistentStore("vault_keys", StoreKind.PREFS, local("Which vault key generations this phone has used")),
        PersistentStore("record_sealing", StoreKind.PREFS, local("Whether older notes were sealed on this phone")),
        PersistentStore("record_crypto", StoreKind.PREFS, local("Which small-records key this phone uses, keys set aside and the reset notice")),
        // ---- files
        PersistentStore("timemachine", StoreKind.FILES, local("Contact history of this phone, kept 180 days"), location = PersistentStore.FILES),
        PersistentStore("vault_photos", StoreKind.FILES, StorePolicy.BackedUpWithVault, Sections.VAULT, PersistentStore.FILES),
        PersistentStore("call_backgrounds", StoreKind.FILES, backedUp, Sections.PEOPLE, PersistentStore.FILES),
        PersistentStore("contact_photos", StoreKind.FILES, backedUp, Sections.PEOPLE, PersistentStore.FILES),
        PersistentStore(
            "vault_photo_originals", StoreKind.FILES,
            local("Full-size originals of private contacts' photos, sealed; the backup carries each private contact's photo"), location = PersistentStore.FILES,
        ),
        PersistentStore("blocking/templates.json", StoreKind.FILES, local("Installed rule templates record this phone's rule ids; the rules are backed up"), location = PersistentStore.FILES),
        PersistentStore("blocking/share.key", StoreKind.FILES, StorePolicy.Secret("Signing key for shared rule lists"), location = PersistentStore.FILES),
        PersistentStore("markdown_export_state.json", StoreKind.FILES, local("Export bookkeeping for a folder picked here"), location = PersistentStore.FILES),
        PersistentStore("folder_sync_state.json", StoreKind.FILES, local("Sync bookkeeping for a folder picked here"), location = PersistentStore.FILES),
        PersistentStore("folder_sync_gone.json", StoreKind.FILES, local("Versions of files a folder sync deleted"), location = PersistentStore.FILES),
        PersistentStore("lists", StoreKind.FILES, backedUp, Sections.SPAM_LISTS, PersistentStore.DEVICE_PROTECTED_FILES),
        PersistentStore("history.keys", StoreKind.FILES, StorePolicy.Secret("Wrapped call-history key"), location = PersistentStore.NO_BACKUP_FILES),
        PersistentStore("records.keys", StoreKind.FILES, StorePolicy.Secret("Wrapped small-records key"), location = PersistentStore.NO_BACKUP_FILES),
        PersistentStore("vault-unreadable", StoreKind.FILES, StorePolicy.Secret("Unreadable private details"), location = PersistentStore.NO_BACKUP_FILES),
        PersistentStore(
            "vault_trash", StoreKind.FILES, local("The 30-day undo of deleted private contacts, sealed like the vault"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        PersistentStore(
            "number_memory", StoreKind.FILES,
            local("Keyed-hash index of what this phone knows about numbers, rebuilt from the stores it indexes"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        // ---- Keystore
        PersistentStore("AndroidKeyStore", StoreKind.KEYSTORE, StorePolicy.Secret("Hardware-backed keys never leave the phone")),
    )

    fun of(kind: StoreKind): List<PersistentStore> = all.filter { it.kind == kind }

    fun find(kind: StoreKind, name: String, location: String? = null): PersistentStore? =
        all.firstOrNull { it.kind == kind && it.name == name && (location == null || it.location == location) }

    /** Sections that must be in a backup: every section of a [StorePolicy.BackedUp] store. */
    val requiredSections: Set<String> get() = all.filter { it.policy == StorePolicy.BackedUp }.mapNotNull { it.section }.toSet()

    /** Tables of [db], for checks against the Room schema. */
    fun tables(db: String): Set<String> = all.filter { it.kind == StoreKind.ROOM_TABLE && it.location == db }.map { it.name }.toSet()
}
