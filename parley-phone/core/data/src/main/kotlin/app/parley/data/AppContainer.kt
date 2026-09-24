package app.parley.data

import android.content.Context
import app.parley.data.db.AppDatabase
import app.parley.data.records.ContactRecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

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
    val vcards by lazy { VCardIO(appContext, contacts, records) }
    val vault by lazy { app.parley.data.vault.VaultRepository(appContext, db, scope) }
    val meta by lazy { db.metaDao() }
    val journal by lazy { JournalRepository(meta, records) }
    val folderSync by lazy { app.parley.data.sync.FolderSync(appContext, contacts, records) }
    val messaging by lazy { app.parley.data.messaging.MessagingStore(appContext, scope) { n -> vault.lookup(n) != null } }
    val timeMachine by lazy { app.parley.data.backup.TimeMachine(appContext, records) }
    val people by lazy { app.parley.data.people.PeopleContainer(this) }
    val backup by lazy {
        app.parley.data.backup.BackupRepository(appContext, contacts, records, blocks, prefs, db, settings, vault, app.parley.data.backup.BackupPrefs(appContext))
            .apply { callHistory = history }
            .also { it.extras = { listOf(people.backupExtras) } }
    }
    val history by lazy { app.parley.data.history.CallHistory(appContext, callLog, contacts, vault, scope) }

    init {
        // Every delete/edit/merge made through Parley is journaled first (30-day undo).
        contacts.beforeChange = { ids, action -> journal.snapshot(ids, action) }
    }
}
