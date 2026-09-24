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
    val screener by lazy { CallScreener(appContext, contacts, blocks, sims, settings, vault) }
    val placer by lazy { CallPlacer(appContext, sims, prefs) }
    val records by lazy { ContactRecordStore(appContext) }
    val vcards by lazy { VCardIO(appContext, contacts, records) }
    val vault by lazy { app.parley.data.vault.VaultRepository(appContext, db, scope) }
    val meta by lazy { db.metaDao() }
    val journal by lazy { JournalRepository(meta, records) }

    init {
        // Every delete/edit/merge made through Parley is journaled first (30-day undo).
        contacts.beforeChange = { ids, action -> journal.snapshot(ids, action) }
    }
}
