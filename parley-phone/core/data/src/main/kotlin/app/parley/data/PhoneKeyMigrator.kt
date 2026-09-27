package app.parley.data

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import app.parley.common.PhoneIdentity
import app.parley.common.PhoneKeyMigration
import app.parley.data.db.AppDatabase
import app.parley.data.db.NumberSimEntity
import app.parley.data.history.CallHistory
import app.parley.data.messaging.MessagingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-time move of rows stored under the old last-9-digits number key to [PhoneIdentity.key]: call notes, ring
 * records, remembered SIMs (Room) and the "last messaged" record (preferences).
 *
 * The old key alone can't be turned into the new one, so it runs once the phone's contacts and call history are
 * loaded and resolves each old key through them ([PhoneKeyMigration]). Rows it can't resolve keep their old key, and
 * every lookup still finds them ([PhoneIdentity.lookupKeys]), during and after the migration. The Room part is one
 * transaction: either every table moved or none did.
 */
class PhoneKeyMigrator(
    private val context: Context,
    private val db: AppDatabase,
    private val contacts: ContactsRepository,
    private val history: () -> CallHistory,
    private val messaging: () -> MessagingStore,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val done: Boolean get() = prefs.getInt(K_VERSION, 0) >= VERSION

    /** Runs the migration unless it already ran; returns how many old keys were moved. */
    suspend fun runIfNeeded(): Int = withContext(Dispatchers.IO) {
        if (done) return@withContext 0
        // Without the contacts or the calls, too little is known to resolve anything: try again at the next start.
        val people = withTimeoutOrNull(WAIT_MS) { contacts.contacts.filterNotNull().first() } ?: return@withContext 0
        val calls = withTimeoutOrNull(WAIT_MS) { history().calls.filterNotNull().first() } ?: return@withContext 0
        val region = PhoneEnv.countryIso(context)
        val known = people.flatMap { c -> c.phones.map { it.number } } + calls.map { it.number } +
            runCatching { history().archive.value.orEmpty().mapNotNull { it.record.number } }.getOrDefault(emptyList())
        val meta = db.metaDao()
        val blocks = db.blockDao()
        val sims = db.prefsDao()
        val stored = meta.callNoteKeys() + blocks.ringKeys() + sims.allSimsNow().map { it.matchKey }
        val plan = PhoneKeyMigration.plan(stored, known, region)
        try {
            if (plan.isNotEmpty()) {
                db.withTransaction {
                    val simRows = sims.allSimsNow().associateBy { it.matchKey }
                    for ((old, new) in plan) {
                        meta.rekeyCallNotes(old, new)
                        blocks.rekeyRings(old, new)
                        val sim = simRows[old] ?: continue
                        // A choice already made under the new key is newer: keep it.
                        if (new !in simRows) sims.setSim(NumberSimEntity(new, sim.phoneAccountId))
                        sims.clearSim(old)
                    }
                }
            }
            runCatching { messaging().rekeyLegacy(plan) }.onFailure { Log.w(TAG, "Messaged-numbers record not re-keyed", it) }
            prefs.edit().putInt(K_VERSION, VERSION).apply()
            plan.size
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Phone-key migration failed; old keys stay readable", e)
            0
        }
    }

    companion object {
        /** Registered in [PersistentStores]. */
        const val PREFS = "parley_migrations"
        private const val K_VERSION = "phone_keys_version"
        private const val VERSION = 1
        private const val WAIT_MS = 60_000L
        private const val TAG = "PhoneKeyMigrator"
    }
}
