package app.parley.data

import app.parley.common.calls.ExpectedWindow
import app.parley.data.security.RecordSealing
import app.parley.data.security.SealedMetaDao
import app.parley.data.security.RecordCrypto
import app.parley.data.db.MetaDao
import android.content.Context
import android.util.Log
import app.parley.data.backup.BackupExtras
import app.parley.data.backup.BackupPrefs
import app.parley.data.backup.BackupRepository
import app.parley.data.backup.CallTimeBackup
import app.parley.data.backup.ContactNotesBackup
import app.parley.data.backup.HistorySettingsBackup
import app.parley.data.backup.SpamListsBackup
import app.parley.data.backup.SyncWatch
import app.parley.data.backup.TimeMachine
import app.parley.data.calls.CallExtrasRepository
import app.parley.data.calls.CallQualityStore
import app.parley.data.calls.ReputationStore
import app.parley.data.calls.RingFactsStore
import app.parley.data.calls.ToCallStore
import app.parley.data.calls.MenuMemoryStore
import app.parley.data.calls.FamilySafetyStore
import app.parley.data.calls.VoicemailRepository
import app.parley.data.calltime.CallUsageLedger
import app.parley.data.calltime.CallingRepository
import app.parley.data.circle.CircleRepository
import app.parley.data.circle.InteractionStore
import app.parley.data.db.AppDatabase
import app.parley.data.extras.ExtrasStore
import app.parley.data.extras.MarkdownExport
import app.parley.data.history.CallHistory
import app.parley.data.messaging.BulkAddStore
import app.parley.data.messaging.MessagingStore
import app.parley.data.memory.NumberMemoryStore
import app.parley.data.people.ContactKeys
import app.parley.data.people.PeopleContainer
import app.parley.data.people.PeoplePrefs
import app.parley.data.people.TemporaryContactStore
import app.parley.data.records.ContactRecordStore
import app.parley.data.sync.FolderSync
import app.parley.data.vault.VaultMoves
import app.parley.data.vault.VaultCrypto
import app.parley.data.vault.VaultRepository
import app.parley.data.vault.PrivateLabelStore
import app.parley.data.vault.PrivateTrash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Manual dependency container: one instance per process. */
class DataContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // The vault's key generation checks for a secure lock screen and StrongBox.
        VaultCrypto.appContext = appContext
    }

    /**
     * Opens when the UI starts or a call has settled. Until then the process runs lean: what call screening and the
     * call screen need (settings, rules, lists, lookups) and nothing that scans the address book or the call log.
     */
    val fullStart = StartGate()

    /**
     * Warm-up and upkeep that isn't urgent run here: at most two at a time, so they never take every IO thread from
     * call screening or the UI.
     */
    val warmDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settings = SettingsRepository(appContext, scope)
    val contacts = ContactsRepository(appContext, scope, fullStart.sharing)
    val callLog = CallLogRepository(appContext, scope, fullStart.sharing)
    val sims = SimRepository(appContext)
    val blocks by lazy { BlockRepository(appContext, db, scope) }
    val prefs by lazy { PrefsRepository(db) { PhoneEnv.countryIso(appContext) } }
    val screener by lazy {
        CallScreener(
            appContext, contacts, blocks, sims, settings, vault, scope, lists, labelRingtones = { peoplePrefs.current().labelRingtones }, callLog = callLog,
            reputation = reputation,
        )
            .also { s -> s.onScreened = { e -> onScreened?.invoke(e) } }
            // I7: windows from notes, the To call list and delivery QR codes count as "Expecting a call".
            .also { s ->
                s.expectedWindows = {
                    // Memory only on the call path (L7): not read yet means none this time, and a read in the background.
                    val windows = familySafety.windowsNow()
                        ?: emptyList<ExpectedWindow>().also { scope.launch(warmDispatcher) { runCatching { familySafety.load() } } }
                    // A private contact's name stays out of "expecting a call (note on …)" in discreet mode.
                    val discreet = settings.current().hideVault
                    windows.map { w -> if (w.label != null && w.shownLabel(discreet) == null) w.copy(label = null) else w }
                }
            }
    }

    /**
     * Per-verdict notifications for screened calls, set at app start. Kept here so setting it doesn't build the
     * screener (and its disk-backed parts) on the main thread.
     */
    @Volatile
    var onScreened: ((ScreenedCall) -> Unit)? = null
    /** Contacts preferences (label ringtones among them), shared by [people] and the call path. */
    val peoplePrefs by lazy { PeoplePrefs(appContext, scope) }
    /** Spam-list packs (device-protected storage). */
    val lists by lazy { SpamListStore(appContext) }
    val dialGuard by lazy {
        DialGuard(appContext, blocks, lists, { history.calls.value }, contacts) { n -> callLog.pastCalls(n, System.currentTimeMillis(), limit = 10) }
    }
    // A label's SIM for people without a remembered SIM of their own.
    val placer by lazy { CallPlacer(appContext, sims, prefs).also { p -> p.fallbackSim = { n -> extras.labelSimFor(n) } } }
    val records by lazy { ContactRecordStore(appContext) }
    val calling by lazy { CallingRepository(appContext) }
    /** Connected calls as the call path saw them, for allowances (a ledger nobody else can clear). */
    val callUsage by lazy { CallUsageLedger(db) }

    /** Call switches (proximity, pocket guard, missed-call re-alert), ring facts and voicemail. */
    val callExtras by lazy { CallExtrasRepository(appContext) }
    val ringFacts: RingFactsStore by lazy { RingFactsStore(appContext) { history } }

    /** Quality facts per call (SIM, Wi-Fi calling, HD voice, why it ended, the caller's subject). */
    val callQuality: CallQualityStore by lazy { CallQualityStore(appContext) { history } }

    /** I2 personal reputation: what your own calls say about numbers and ranges (learned daily, sealed). */
    val reputation: ReputationStore by lazy { ReputationStore(appContext) { history } }
    val voicemail by lazy { VoicemailRepository(appContext, scope) }

    /** The "To call" list: reminders to call back and follow-ups (by number, sealed at rest). */
    val toCall by lazy { ToCallStore(appContext) { n -> vault.lookup(n) != null } }

    /** I6 menu memory: the keys sent per number and menu shortcuts (by number, sealed at rest; never emergency calls). */
    val menus by lazy { MenuMemoryStore(appContext) { n -> vault.lookup(n) != null } }

    /** Family safety: safe words per label, helpers, expected-call windows (sealed, this phone only). */
    val familySafety by lazy { FamilySafetyStore(appContext) }
    val vcards by lazy { VCardIO(appContext, contacts, records) { vault.allNumbers() } }

    /** Lossless moves into and out of the private vault. */
    val vaultMoves by lazy { VaultMoves(vault, contacts, records) { circle.interactions } }
    val vault: VaultRepository by lazy {
        VaultRepository(appContext, db, scope).also { v ->
            // A private contact's labels are the address book's groups; only who is in them is kept in the vault.
            v.labelGroups = { contacts.groups().map { app.parley.common.people.PrivateLabels.Group(it.id, it.title) } }
            // Deleted private contacts kept sealed for 30 days still need their detail key.
            v.keptGenerations = { privateTrash.generations() }
        }
    }

    /** Which private contacts are in which label (sealed in their vault entries, never in the address book). */
    val privateLabels: PrivateLabelStore by lazy { PrivateLabelStore(vault, contacts, scope) }

    /** "Recently deleted" private contacts: sealed copies kept 30 days (History & undo never holds them). */
    val privateTrash: PrivateTrash by lazy { PrivateTrash(appContext, vault) { contactKeys } }

    /** Pinned notes, call notes and journal payloads are sealed at rest behind this DAO. */
    val meta: MetaDao by lazy { SealedMetaDao(db.metaDao(), RecordCrypto.get(appContext)) }
    val journal by lazy { JournalRepository(meta, records) }
    val folderSync by lazy { FolderSync(appContext, contacts, records) }
    val messaging by lazy { MessagingStore(appContext, scope) { n -> vault.lookup(n) != null } }
    /** "Add several numbers…" batches (one undo per batch). */
    val bulkAdd by lazy { BulkAddStore(this) }
    val timeMachine by lazy { TimeMachine(appContext, records) }

    /** Notices large unexplained losses in the daily snapshots and account sync (the sync watchdog). */
    val syncWatch by lazy { SyncWatch(this) }

    /** "Who is this?" for numbers that aren't contacts, from what Parley keeps (keyed-hash index, sealed hints). */
    val numberMemory by lazy { NumberMemoryStore(this) }

    /** Clearing History & undo's stores (contact changes, deleted calls, snapshots). */
    val undoStorage by lazy { UndoStorage(db, meta, history, timeMachine) }

    /** Tips seen, "What's new" and the backup reminder. */
    val ux by lazy { UxPrefs(appContext) }
    val people by lazy { PeopleContainer(this) }
    val backup by lazy {
        BackupRepository(appContext, contacts, records, blocks, prefs, db, settings, vault, BackupPrefs(appContext), callLog)
            .apply { callHistory = history }
            .also { it.extras = { backupParts } }
            .also { it.privateExtras = contactKeys }
    }
    /**
     * Every feature part of the backup. Each names the [app.parley.common.storage.PersistentStores] sections it writes;
     * the backup reports a backed-up store no part covers.
     */
    private val backupParts: List<BackupExtras> by lazy {
        listOf(
            people.backupExtras, circle.backupExtras, extras.backupExtras, extras.callerChoicesBackup,
            ContactNotesBackup(db, { contacts.loadNow() }, metaDao = meta) { id -> contacts.rawIds(id) },
            CallTimeBackup(calling, callExtras) { contacts.loadNow() },
            HistorySettingsBackup { history.prefs },
            SpamListsBackup { lists },
            toCall.backupExtras,
            menus.backupExtras,
        )
    }

    val history: CallHistory by lazy {
        CallHistory(appContext, callLog, contacts, vault, scope, fullStart).also { h ->
            h.onForget = { n, dates ->
                ringFacts.forget(n, dates)
                callQuality.forget(n, dates)
            }
        }
    }

    /** "Delete all Parley data" (every store in [app.parley.common.storage.PersistentStores]). */
    val wipe by lazy { DataWipe(appContext, this) }

    /** Moves rows stored under the old last-digits number key to the line key, once (see [PhoneKeyMigrator]). */
    /** Seals small records older versions stored plain (runs once in the background). */
    val recordSealing by lazy {
        RecordSealing(appContext, db, { timeMachine }) { listOf(toCall, people.cardIdentity, people.shareLedger, people.cardLinks, menus) }
    }
    val phoneKeys by lazy { PhoneKeyMigrator(appContext, db, contacts, { history }) { messaging } }

    /** Temporary contacts: the one API to create, mark, keep and expire them. */
    val temporaries by lazy { TemporaryContactStore(this) }

    /** Keeps notes, backgrounds, relation links and temporary flags attached when lookup keys change. */
    val contactKeys by lazy {
        ContactKeys(
            contacts, meta, { people.backgrounds }, { circle.interactions }, { extras }, db, originals = { people.originals }, calling = { calling },
            waiting = { appContext.getSharedPreferences("contact_key_moves", Context.MODE_PRIVATE) },
            cardLinks = { people.cardLinks }, shareLedger = { people.shareLedger },
        )
    }

    /** The Circle (keep-in-touch rhythms, interactions, "Log this?", reminder bookkeeping). */
    val circle by lazy {
        CircleRepository(
            appContext, meta, InteractionStore(db.interactionDao()),
            index = { history.index }, contactsFlow = { contacts.contacts }, freshContacts = { contacts.loadNow() }, db = db,
        )
    }

    /** Contacts as the screens show them and the number → contact index, shared by the view models. */
    val directory by lazy { ContactDirectory(contacts, settings, PhoneEnv.countryIso(appContext), scope) }

    /** Extras: trip mode city, label policies, simple mode. */
    val extras by lazy { ExtrasStore(this) }

    /** One-way Markdown export of notes and timelines to a folder. */
    val markdown by lazy { MarkdownExport(appContext, this) }

    @OptIn(FlowPreview::class)
    private fun followKeyChanges() {
        // Parley's own links, unlinks and moves: temporary flags first (they need the old keys), then metadata.
        contacts.afterRelink = { before, kind ->
            if (kind == "MERGE") temporaries.onJoined(before)
            val movedTo = kind.removePrefix("MOVE:").takeIf { kind.startsWith("MOVE:") }?.toLongOrNull()
            if (movedTo != null) before.forEach { (_, key) -> contactKeys.moveTo(key, movedTo) } else contactKeys.carry(before)
        }
        // Changes made by other apps and sync adapters: re-resolve stored keys once contacts settle. Not in a process
        // started for a call or a worker (the daily maintenance run sweeps too).
        scope.launch(warmDispatcher) {
            fullStart.await()
            contacts.contacts.filterNotNull().debounce(15_000).collect {
                try {
                    contactKeys.sweep()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("DataContainer", "Metadata key sweep failed", e)
                }
            }
        }
    }

    /**
     * Builds the preference-backed stores on the calling thread (IO, at start-up). Their constructors read
     * SharedPreferences, which blocks until the file is parsed; built here, the call screen's appearance and the first
     * screens never pay for that on the main thread. Construction only: nothing here scans contacts or calls.
     */
    fun warmStores() {
        runCatching { extras }
        runCatching { callExtras }
        runCatching { calling }
        runCatching { ux }
        runCatching { circle }
        runCatching { messaging }
        runCatching { vault }
        runCatching { history }
        runCatching { people }
        runCatching { directory }
    }

    /** The UI started, or a call settled: load and follow everything. Idempotent. */
    fun startFull() = fullStart.open()

    init {
        // Every delete/edit/merge made through Parley is journaled first (30-day undo).
        contacts.beforeChange = { ids, action -> journal.snapshot(ids, action) }
        followKeyChanges()
        // Number memory follows deletes and restores made in Parley, once the full app starts (never on the call path).
        scope.launch(warmDispatcher) {
            fullStart.await()
            numberMemory.follow()
        }
    }
}
