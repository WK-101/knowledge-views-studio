package app.parley.data

import android.content.Context
import app.parley.data.db.AppDatabase
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
    val vcards by lazy { VCardIO(appContext, contacts) }
    val vault by lazy { app.parley.data.vault.VaultRepository(appContext, db, scope) }
    val meta by lazy { db.metaDao() }
}
