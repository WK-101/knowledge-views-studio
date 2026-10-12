package app.parley.data.vault

import android.content.Context
import app.parley.common.catching
import app.parley.data.ContactDetailsJson
import app.parley.data.EventItem
import app.parley.data.StatePrefs
import app.parley.data.db.VaultDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Private contacts' dates in their caller-ID copies ([CallerIdCopy.C_DATES]), for entries saved before the copy kept
 * them: [seed] writes one entry's from its opened details, [migrate] does every entry after the vault's unlock.
 * [keysLock] is [VaultRepository]'s, so a seed never races a save or a key upgrade.
 */
internal class VaultDates(private val context: Context, private val dao: VaultDao, private val keysLock: Mutex, private val prefsName: String) {
    /** Writes [events] as entry [id]'s dates when its copy doesn't keep them yet; the rest of the copy, and "u", stay. */
    suspend fun seed(id: Long, events: List<EventItem>) = keysLock.withLock {
        val e = dao.callerRow(id) ?: return@withLock
        val o = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
        if (CallerIdCopy.hasDates(o)) return@withLock
        CallerIdCopy.putDates(o, events)
        dao.setCallerIdBlob(id, VaultCrypto.sealCallerId(o.toString().toByteArray()))
    }

    /**
     * Every entry whose copy doesn't keep its dates yet gets them from its sealed details. Stops while the vault is
     * locked and runs again on the next unlock; marked done once every entry has them. An entry whose detail key is lost
     * is marked with none. Returns how many were seeded now.
     */
    suspend fun migrate(): Int = withContext(Dispatchers.IO) {
        val prefs = StatePrefs.open(context, prefsName)
        if (prefs.getBoolean(K_DATES_SEEDED, false)) return@withContext 0
        var seeded = 0
        var pending = false
        // Only entries whose copy opens and doesn't keep dates yet.
        val waiting = dao.callerRowsNow().filter { row ->
            catching { JSONObject(String(VaultCrypto.openCallerId(row.callerIdBlob))) }.getOrNull()?.let { !CallerIdCopy.hasDates(it) } == true
        }
        for (row in waiting) {
            val e = dao.get(row.id) ?: continue
            try {
                seed(row.id, ContactDetailsJson.decode(String(VaultCrypto.openDetailMain(e.detailBlob))).events)
                seeded++
            } catch (_: VaultCrypto.LockedException) {
                return@withContext seeded
            } catch (_: VaultCrypto.KeyLostException) {
                // Its details can't be read any more: marked with none, so it isn't tried on every unlock.
                catching { seed(row.id, emptyList()) }
            } catch (_: VaultCrypto.KeyUnavailableException) {
                pending = true
            }
        }
        if (!pending) prefs.edit().putBoolean(K_DATES_SEEDED, true).apply()
        seeded
    }

    private companion object {
        const val K_DATES_SEEDED = "dates_seeded"
    }
}
