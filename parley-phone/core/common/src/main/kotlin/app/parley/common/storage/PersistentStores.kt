package app.parley.common.storage

/** Where a store lives. */
enum class StoreKind {
    /** A table of a Room database ([PersistentStore.location] is the database file name). */
    ROOM_TABLE,

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
    /**
     * Room: the database file; FILES: [FILES], [NO_BACKUP_FILES] or [DEVICE_PROTECTED_FILES]; PREFS: the shared file its
     * keys are kept in ([PersistentStores.LOCAL_STATE] or [PersistentStores.CALL_PATH_STATE]), or null for a file of its own.
     */
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
 * and a unit test fails when code uses a preferences file, database, table or files entry that isn't listed
 * here. Add a store here in the same change that adds it to the code.
 */
object PersistentStores {
    const val MAIN_DB = "parley.db"
    const val HISTORY_DB = "parley-history.db"

    /** The shared preferences files bookkeeping stores are kept in (a preferences store's location). */
    const val LOCAL_STATE = "local_state"
    const val CALL_PATH_STATE = "call_path_state"

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
        const val FAMILY_SAFETY = "family_safety"
        const val CALL_SWITCHES = "call_switches"
        const val SITUATIONS = "situations"
        const val CASE_FILES = "case_files"
        const val ARCHIVE = "archive"

        /** Generated caller ringtones: their audio files, as optional archive files (BackupArchiveWriter.writeFiles). */
        const val TUNES = "tunes"
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
        table("journal_photos", local("Photos of the 30-day undo copies, kept once by hash")),
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
        table(
            "call_facts",
            local("Ring facts, call quality, names the network sent and \"It wasn't them\" marks, keyed and sealed with this phone's call-history key"),
            db = HISTORY_DB,
        ),
        // ---- Settings files (PreferenceFile; older versions kept them in DataStore files, moved over on first open)
        PersistentStore("settings", StoreKind.PREFS, backedUp, Sections.SETTINGS),
        PersistentStore("people", StoreKind.PREFS, backedUp, Sections.PEOPLE),
        PersistentStore("history", StoreKind.PREFS, backedUp, Sections.HISTORY_SETTINGS),
        // ---- SharedPreferences
        // Small bookkeeping flags, two files for all of them (StatePrefs): each store below with this location keeps its
        // keys there, under its own name. The ring path's in the second.
        PersistentStore(LOCAL_STATE, StoreKind.PREFS, local("This phone's bookkeeping flags (the stores kept in it say which)")),
        PersistentStore(CALL_PATH_STATE, StoreKind.PREFS, local("Call-path state of this phone (the stores kept in it say which)")),
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
        // Where earlier versions kept the case files, To call and menu memory, and the per-call facts now in call_facts:
        // read once to copy them, then emptied. What they held is backed up from where it lives now.
        PersistentStore("case_files", StoreKind.PREFS, local("Case files as earlier versions kept them, copied once to docs/case_files")),
        PersistentStore("parley_ring_facts", StoreKind.PREFS, local("Ring facts as earlier versions kept them, copied once to call_facts")),
        PersistentStore("parley_call_quality", StoreKind.PREFS, local("Call quality facts as earlier versions kept them, copied once to call_facts")),
        PersistentStore("parley_disowned_calls", StoreKind.PREFS, local("\"It wasn't them\" marks as earlier versions kept them, copied once to call_facts")),
        PersistentStore("parley_network_names", StoreKind.PREFS, local("Names the network sent, as earlier versions kept them, copied once to call_facts")),
        PersistentStore(
            "parley_number_advice", StoreKind.PREFS,
            local("Answers to \"seems out of service\" and SIM suggestions, keyed by this phone's call-history key"),
        ),
        PersistentStore(
            "parley_reputation", StoreKind.PREFS,
            local("What this phone's own calls say about numbers and ranges, keyed and sealed with the call-history key; rebuilt daily"),
        ),
        PersistentStore("messaging", StoreKind.PREFS, local("Which numbers you opened a chat with stays on this phone by design")),
        PersistentStore(
            "temporary_due", StoreKind.PREFS,
            local("Which due temporary contacts this phone's notification is asking about"),
            location = LOCAL_STATE,
        ),
        PersistentStore("bulk_add", StoreKind.PREFS, local("Undo for recent \"Add several numbers\" batches")),
        PersistentStore("ux", StoreKind.PREFS, local("Tips already seen on this phone"), location = LOCAL_STATE),
        PersistentStore("crash_capture", StoreKind.PREFS, local("Crash reports of this phone, kept only when asked")),
        PersistentStore("diagnostics", StoreKind.PREFS, local("Diagnostics of this phone"), location = LOCAL_STATE),
        PersistentStore("account_diagnostics", StoreKind.PREFS, local("Account checks of this phone"), location = LOCAL_STATE),
        PersistentStore("parley_writes", StoreKind.PREFS, local("Which contact rows Parley wrote on this phone"), location = LOCAL_STATE),
        PersistentStore(
            "contact_key_moves", StoreKind.PREFS,
            local("Contacts just made visible whose lookup key this phone hasn't listed yet"),
            location = LOCAL_STATE,
        ),
        PersistentStore(
            "relation_mirrors", StoreKind.PREFS,
            local("Which relations Parley added to the other contact, by this phone's lookup keys"),
            location = LOCAL_STATE,
        ),
        PersistentStore("folder_sync", StoreKind.PREFS, local("Sync state with a folder picked on this phone")),
        PersistentStore("folder_sync_notice", StoreKind.PREFS, local("Which folder-sync pause was already notified"), location = LOCAL_STATE),
        PersistentStore(
            "folder_sync_runs", StoreKind.PREFS,
            local("When the background folder sync last ran, so runs close together are skipped"),
            location = LOCAL_STATE,
        ),
        PersistentStore(
            "sync_watch", StoreKind.PREFS,
            local("What the sync watchdog last saw and already said, by this phone's lookup keys and accounts"),
            location = LOCAL_STATE,
        ),
        PersistentStore("markdown_export", StoreKind.PREFS, local("The folder of the notes export earlier versions had; removed by the daily maintenance")),
        PersistentStore("parley_screening_guard", StoreKind.PREFS, local("Call-path safety state (emergency window)"), location = CALL_PATH_STATE),
        PersistentStore("parley_ring_boost", StoreKind.PREFS, local("Ring volume to restore after a crash"), location = CALL_PATH_STATE),
        PersistentStore("parley_ring_ramp", StoreKind.PREFS, local("The ring volume to put back after an increasing ring, if a crash cut it short")),
        PersistentStore(
            "rescue_call", StoreKind.PREFS,
            local("A rescue call waiting to ring and the last choices on its screen (who calls sealed): this phone's moment, never backed up"),
            location = CALL_PATH_STATE,
        ),
        PersistentStore("parley_missed_realert", StoreKind.PREFS, local("Missed-call reminder in progress"), location = CALL_PATH_STATE),
        PersistentStore(
            "private_call_sweep", StoreKind.PREFS,
            local("A private contact's call that may still be in the system call log"),
            location = LOCAL_STATE,
        ),
        PersistentStore("to_call", StoreKind.PREFS, local("Whether the To call strip is folded on this phone; the list as earlier versions kept it")),
        PersistentStore("menu_memory", StoreKind.PREFS, local("Menu memory as earlier versions kept it, copied once to docs/menu_memory")),
        // Safe words, helpers and expected-call windows: a safety feature must survive a move to a new phone. Inside the
        // backup's encryption only; a duress unlock's hidden safe words stay out (FamilySafetyBackup).
        PersistentStore("family_safety", StoreKind.PREFS, backedUp, Sections.FAMILY_SAFETY),
        // Their switches travel; the cars (Bluetooth addresses of this phone's pairings) and the trip the local-SIM
        // hint was shown for stay here: a new phone pairs again and has its own trips.
        PersistentStore("parley_drive_profile", StoreKind.PREFS, backedUp, Sections.CALL_SWITCHES),
        PersistentStore("parley_roaming", StoreKind.PREFS, backedUp, Sections.CALL_SWITCHES),
        // Situations ("Driving", "Night"…) and the ones made: what each sets and when it switches on.
        PersistentStore("parley_situations", StoreKind.PREFS, backedUp, Sections.SITUATIONS),
        PersistentStore(
            "parley_situation_state", StoreKind.PREFS,
            local("The Situation on now and what to put back when it goes off: this phone's moment, not a preference"),
        ),
        PersistentStore("situation_triggers", StoreKind.PREFS, local("Whether this phone's job for a Situation's next window is queued")),
        PersistentStore("lists_updater", StoreKind.PREFS, local("Link with the companion app installed on this phone"), location = LOCAL_STATE),
        PersistentStore("dial_widgets", StoreKind.PREFS, local("Home-screen widgets of this launcher"), location = LOCAL_STATE),
        PersistentStore("favorites_widgets", StoreKind.PREFS, local("Home-screen widgets of this launcher"), location = LOCAL_STATE),
        PersistentStore(
            "history_upkeep", StoreKind.PREFS, local("Whether calls were deleted by hand since number memory last read the whole archive"),
            location = LOCAL_STATE,
        ),
        PersistentStore("parley_migrations", StoreKind.PREFS, local("Which one-time data migrations ran here"), location = LOCAL_STATE),
        PersistentStore("vault", StoreKind.PREFS, local("Private-contact fingerprint migration state"), location = LOCAL_STATE),
        PersistentStore("backup", StoreKind.PREFS, StorePolicy.Secret("The backup keys and the backup folder of this phone")),
        PersistentStore("vault_keys", StoreKind.PREFS, local("Which vault key generations this phone has used"), location = LOCAL_STATE),
        PersistentStore("record_sealing", StoreKind.PREFS, local("Whether older notes were sealed on this phone"), location = LOCAL_STATE),
        PersistentStore("record_crypto", StoreKind.PREFS, local("Which small-records key this phone uses, keys set aside and the reset notice")),
        // ---- files
        PersistentStore("timemachine", StoreKind.FILES, local("Contact history of this phone, kept 180 days"), location = PersistentStore.FILES),
        PersistentStore("vault_photos", StoreKind.FILES, StorePolicy.BackedUpWithVault, Sections.VAULT, PersistentStore.FILES),
        PersistentStore("call_backgrounds", StoreKind.FILES, backedUp, Sections.PEOPLE, PersistentStore.FILES),
        // My card's photo: in the encrypted backup with the card itself.
        PersistentStore("me_card", StoreKind.FILES, backedUp, Sections.PEOPLE, PersistentStore.FILES),
        // Archived contacts: out of the address book, kept whole (sealed) and restored with the contacts.
        PersistentStore("archive", StoreKind.FILES, backedUp, Sections.ARCHIVE, PersistentStore.FILES),
        PersistentStore("contact_photos", StoreKind.FILES, backedUp, Sections.PEOPLE, PersistentStore.FILES),
        PersistentStore(
            "vault_photo_originals", StoreKind.FILES,
            local("Full-size originals of private contacts' photos, sealed; the backup carries each private contact's photo"), location = PersistentStore.FILES,
        ),
        PersistentStore(
            "blocking/templates.json",
            StoreKind.FILES,
            local("Installed rule templates record this phone's rule ids; the rules are backed up"),
            location = PersistentStore.FILES,
        ),
        PersistentStore("blocking/share.key", StoreKind.FILES, StorePolicy.Secret("Signing key for shared rule lists"), location = PersistentStore.FILES),
        PersistentStore(
            "markdown_export_state.json", StoreKind.FILES, local("Bookkeeping of the notes export earlier versions had; removed by the daily maintenance"),
            location = PersistentStore.FILES,
        ),
        // Ringtones made from a name: the contacts and labels keep their URIs, the backup the audio files themselves.
        PersistentStore("tunes", StoreKind.FILES, backedUp, Sections.TUNES, PersistentStore.FILES),
        PersistentStore("folder_sync_state.json", StoreKind.FILES, local("Sync bookkeeping for a folder picked here"), location = PersistentStore.FILES),
        PersistentStore("folder_sync_gone.json", StoreKind.FILES, local("Versions of files a folder sync deleted"), location = PersistentStore.FILES),
        PersistentStore("lists", StoreKind.FILES, backedUp, Sections.SPAM_LISTS, PersistentStore.DEVICE_PROTECTED_FILES),
        // Small sealed documents, one folder each (SealedDocument). Kept by number, not by contact: they follow a
        // contact made private or visible without re-keying.
        PersistentStore("docs/to_call", StoreKind.FILES, backedUp, Sections.TO_CALL, PersistentStore.NO_BACKUP_FILES),
        // Menu memory: keys sent per number and menu shortcuts (sealed).
        PersistentStore("docs/menu_memory", StoreKind.FILES, backedUp, Sections.MENUS, PersistentStore.NO_BACKUP_FILES),
        // Case files, one file per case: calls with hold times, menu keys and reference numbers (sealed; references twice).
        PersistentStore("docs/case_files", StoreKind.FILES, backedUp, Sections.CASE_FILES, PersistentStore.NO_BACKUP_FILES),
        // The number passport: when each saved number came onto its contact and from where (sealed). It describes this
        // phone's address book; restored contacts are stamped as restored instead.
        PersistentStore(
            "docs/number_passport", StoreKind.FILES, local("Where each saved number came from on this phone (the fresh-number warning)"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        // The call round under way: the one thing to say, who to tell and each one's mark (sealed). A few days' errand.
        PersistentStore(
            "docs/call_round", StoreKind.FILES, local("The call round under way: what to tell them and who was reached"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        PersistentStore(
            "contact_list_head", StoreKind.FILES, local("The first screenful of the Contacts list, sealed, shown at a cold start"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        PersistentStore("history.keys", StoreKind.FILES, StorePolicy.Secret("Wrapped call-history key"), location = PersistentStore.NO_BACKUP_FILES),
        PersistentStore("records.keys", StoreKind.FILES, StorePolicy.Secret("Wrapped small-records key"), location = PersistentStore.NO_BACKUP_FILES),
        PersistentStore("vault_calls.keys", StoreKind.FILES, StorePolicy.Secret("Wrapped private-calls key"), location = PersistentStore.NO_BACKUP_FILES),
        PersistentStore(
            "vault_summaries", StoreKind.FILES, local("Private contacts' list rows, sealed, so a cold start lists them at once"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        PersistentStore(
            "vault_summaries.keys", StoreKind.FILES, StorePolicy.Secret("Wrapped key of private contacts' list rows"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        PersistentStore("memory.keys", StoreKind.FILES, StorePolicy.Secret("Wrapped number-memory key"), location = PersistentStore.NO_BACKUP_FILES),
        PersistentStore("vault-unreadable", StoreKind.FILES, StorePolicy.Secret("Unreadable private details"), location = PersistentStore.NO_BACKUP_FILES),
        PersistentStore(
            "shared_labels", StoreKind.FILES,
            StorePolicy.Secret("Shared labels' keys, sync bookkeeping and spam shield verdicts, sealed; another phone joins with an invitation"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        PersistentStore(
            "vault_trash", StoreKind.FILES, local("The 30-day undo of deleted private contacts, sealed like the vault"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        // The app lock's PINs (scrypt hashes, sealed) and whether a duress unlock's hiding is on. Never exported:
        // a PIN is set again on a new phone, and the hiding is about this phone in someone else's hands.
        PersistentStore(
            "app_pin", StoreKind.FILES, StorePolicy.Secret("Hashes of the Parley PIN and the duress PIN"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        PersistentStore(
            "app_lock_state", StoreKind.FILES, StorePolicy.Secret("Whether a duress unlock is hiding things"),
            location = PersistentStore.NO_BACKUP_FILES,
        ),
        // "Lock private contacts" holds until the next unlock in Parley, even when Parley is closed meanwhile.
        PersistentStore(
            "vault_locked", StoreKind.FILES, local("Whether private contacts were locked until the next unlock in Parley"),
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
