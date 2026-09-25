package app.parley.data

import android.content.Context
import app.parley.data.db.AppDatabase
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Manual dependency container: one instance per process. */
class DataContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val settings = SettingsRepository(appContext, scope)
    val contacts = ContactsRepository(appContext, scope)
    val callLog = CallLogRepository(appContext, scope)
    val sims = SimRepository(appContext)
    val blocks by lazy { BlockRepository(appContext, db, scope) }
    val prefs by lazy { PrefsRepository(db) }
    val screener by lazy {
        CallScreener(appContext, contacts, blocks, sims, settings, vault, scope, lists, labelRingtones = { peoplePrefs.current().labelRingtones })
            .also { s -> s.onScreened = { e -> onScreened?.invoke(e) } }
    }

    /**
     * Per-verdict notifications for screened calls, set at app start. Kept here so setting it doesn't build the
     * screener (and its disk-backed parts) on the main thread.
     */
    @Volatile
    var onScreened: ((ScreenedCall) -> Unit)? = null
    /** Contacts preferences (label ringtones among them), shared by [people] and the call path. */
    val peoplePrefs by lazy { app.parley.data.people.PeoplePrefs(appContext, scope) }
    /** Spam-list packs (device-protected storage). */
    val lists by lazy { SpamListStore(appContext) }
    val dialGuard by lazy { DialGuard(appContext, blocks, lists, callLog, contacts) }
    val placer by lazy { CallPlacer(appContext, sims, prefs) }
    val records by lazy { ContactRecordStore(appContext) }
    val calling by lazy { app.parley.data.calltime.CallingRepository(appContext) }

    /** v3.1 call switches (proximity, pocket guard, missed-call re-alert), ring facts (V9) and voicemail (V1). */
    val callExtras by lazy { app.parley.data.calls.CallExtrasRepository(appContext) }
    val ringFacts: app.parley.data.calls.RingFactsStore by lazy { app.parley.data.calls.RingFactsStore(appContext) { history } }
    val voicemail by lazy { app.parley.data.calls.VoicemailRepository(appContext, scope) }
    val vcards by lazy { VCardIO(appContext, contacts, records) { vault.allNumbers() } }

    /** Lossless moves into and out of the private vault (F4). */
    val vaultMoves by lazy { app.parley.data.vault.VaultMoves(vault, contacts, records) }
    val vault by lazy { app.parley.data.vault.VaultRepository(appContext, db, scope) }
    val meta by lazy { db.metaDao() }
    val journal by lazy { JournalRepository(meta, records) }
    val folderSync by lazy { app.parley.data.sync.FolderSync(appContext, contacts, records) }
    val messaging by lazy { app.parley.data.messaging.MessagingStore(appContext, scope) { n -> vault.lookup(n) != null } }
    /** M11: "Add several numbers…" batches (one undo per batch). */
    val bulkAdd by lazy { app.parley.data.messaging.BulkAddStore(this) }
    val timeMachine by lazy { app.parley.data.backup.TimeMachine(appContext, records) }
    /** v3.2: tips seen, "What's new" and the backup reminder (U2, U6, C3). */
    val ux by lazy { UxPrefs(appContext) }
    val people by lazy { app.parley.data.people.PeopleContainer(this) }
    val backup by lazy {
        app.parley.data.backup.BackupRepository(appContext, contacts, records, blocks, prefs, db, settings, vault, app.parley.data.backup.BackupPrefs(appContext))
            .apply { callHistory = history }
            .also { it.extras = { listOf(people.backupExtras, circle.backupExtras) } }
    }
    val history: app.parley.data.history.CallHistory by lazy {
        app.parley.data.history.CallHistory(appContext, callLog, contacts, vault, scope).also { h ->
            h.onForget = { n, dates -> ringFacts.forget(n, dates) }
        }
    }

    /** Temporary contacts: the one API to create, mark, keep and expire them (F2). */
    val temporaries by lazy { app.parley.data.people.TemporaryContactStore(this) }

    /** Keeps notes, backgrounds, relation links and temporary flags attached when lookup keys change (F8). */
    val contactKeys by lazy { app.parley.data.people.ContactKeys(contacts, meta, { people.backgrounds }) { circle.interactions } }

    /** R1–R5: the Circle (keep-in-touch rhythms, interactions, "Log this?", reminder bookkeeping). */
    val circle by lazy {
        app.parley.data.circle.CircleRepository(
            appContext, meta, app.parley.data.circle.InteractionStore(db.interactionDao()),
            index = { history.index }, contactsFlow = { contacts.contacts },
        )
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun followKeyChanges() {
        // Parley's own links, unlinks and moves: temporary flags first (they need the old keys), then metadata.
        contacts.afterRelink = { before, kind ->
            if (kind == "MERGE") temporaries.onJoined(before)
            val movedTo = kind.removePrefix("MOVE:").takeIf { kind.startsWith("MOVE:") }?.toLongOrNull()
            if (movedTo != null) before.forEach { (_, key) -> contactKeys.moveTo(key, movedTo) } else contactKeys.carry(before)
        }
        // Changes made by other apps and sync adapters: re-resolve stored keys once contacts settle.
        scope.launch {
            contacts.contacts.filterNotNull().debounce(15_000).collect {
                try {
                    contactKeys.sweep()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("DataContainer", "Metadata key sweep failed", e)
                }
            }
        }
    }

    init {
        // Every delete/edit/merge made through Parley is journaled first (30-day undo).
        contacts.beforeChange = { ids, action -> journal.snapshot(ids, action) }
        followKeyChanges()
    }
}
