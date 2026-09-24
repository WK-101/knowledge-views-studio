package app.parley.data.vault

import android.content.Context
import android.provider.CallLog
import app.parley.common.PhoneNumbers
import app.parley.data.CallerInfo
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import app.parley.data.db.AppDatabase
import app.parley.data.db.PrivateCallEntity
import app.parley.data.db.VaultContactEntity
import app.parley.data.db.VaultNumberEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Readable without unlocking: enough to show who is calling and list the vault. */
data class VaultSummary(
    val id: Long,
    val name: String,
    val numbers: List<String>,
    val expiresAt: Long?,
)

data class PrivateCall(val id: Long, val vaultId: Long, val number: String, val name: String, val date: Long, val durationSec: Long, val type: Int)

/**
 * Private contacts stored only inside Parley (encrypted), invisible to every other app.
 * Caller ID uses an HMAC index + the caller-ID key, so it works even while the phone is locked.
 */
class VaultRepository(private val context: Context, db: AppDatabase, scope: CoroutineScope) {
    private val dao = db.vaultDao()

    val contacts: StateFlow<List<VaultSummary>> = dao.contacts()
        .map { list -> list.mapNotNull { summarize(it) }.sortedBy { it.name.lowercase() } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val privateCalls: StateFlow<List<PrivateCall>> = dao.privateCalls()
        .map { list ->
            list.mapNotNull { c ->
                runCatching {
                    val o = JSONObject(String(VaultCrypto.openCallerId(c.blob)))
                    PrivateCall(c.id, c.vaultId, o.optString("n"), o.optString("name"), c.date, c.durationSec, c.type)
                }.getOrNull()
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun summarize(e: VaultContactEntity): VaultSummary? = runCatching {
        val o = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
        val nums = o.optJSONArray("numbers") ?: JSONArray()
        VaultSummary(e.id, o.optString("name"), (0 until nums.length()).map { nums.getString(it) }, e.expiresAt)
    }.getOrNull()

    /** Full details; throws [VaultCrypto.LockedException] if the user must unlock first. */
    suspend fun details(id: Long): ContactDetails? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        ContactDetailsJson.decode(String(VaultCrypto.openDetail(e.detailBlob)))
    }

    suspend fun save(id: Long?, d: ContactDetails, expiresAt: Long? = null): Long = withContext(Dispatchers.IO) {
        val name = d.composedName.ifBlank { d.company.ifBlank { d.phones.firstOrNull()?.value ?: "Private contact" } }
        val numbers = d.phones.map { it.value }.filter { it.isNotBlank() }
        val caller = JSONObject().put("name", name).put("numbers", JSONArray(numbers))
            .put("labels", JSONArray(d.phones.filter { it.value.isNotBlank() }.map { it.type }))
        val existing = id?.let { dao.get(it) }
        val entity = VaultContactEntity(
            id = id ?: 0,
            callerIdBlob = VaultCrypto.sealCallerId(caller.toString().toByteArray()),
            detailBlob = VaultCrypto.sealDetail(ContactDetailsJson.encode(d).toByteArray()),
            expiresAt = expiresAt ?: existing?.expiresAt,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        val newId = dao.upsert(entity).let { if (id != null) id else it }
        dao.clearNumbers(newId)
        dao.addNumbers(numbers.map { VaultNumberEntity(newId, VaultCrypto.hmac(PhoneNumbers.matchKey(it))) }.distinct())
        newId
    }

    suspend fun setExpiry(id: Long, expiresAt: Long?) = withContext(Dispatchers.IO) {
        dao.get(id)?.let { dao.upsert(it.copy(expiresAt = expiresAt)) }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.delete(id)
        dao.clearNumbers(id)
        dao.deletePrivateCalls(id)
    }

    /** Caller ID for incoming calls; works without user authentication. */
    suspend fun lookup(number: String): Pair<Long, CallerInfo>? = withContext(Dispatchers.IO) {
        if (number.isBlank()) return@withContext null
        val id = dao.findByHmac(listOf(VaultCrypto.hmac(PhoneNumbers.matchKey(number)))) ?: return@withContext null
        val s = contacts.value.firstOrNull { it.id == id } ?: dao.get(id)?.let { summarize(it) } ?: return@withContext null
        id to CallerInfo(contactId = -id, lookupKey = null, name = s.name, photoUri = null, numberLabel = "Private", customRingtone = null, sendToVoicemail = false)
    }

    /**
     * Moves call-log rows for vault numbers out of the system log into the encrypted private
     * history (needs WRITE_CALL_LOG, granted by the dialer role). Returns rows moved.
     */
    suspend fun sweepCallLog(sinceMillis: Long): Int = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var moved = 0
        val ids = ArrayList<Long>()
        try {
            cr.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} >= ?", arrayOf(sinceMillis.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1) ?: continue
                    val hit = lookup(number) ?: continue
                    val blob = VaultCrypto.sealCallerId(JSONObject().put("n", number).put("name", hit.second.name).toString().toByteArray())
                    dao.addPrivateCall(PrivateCallEntity(vaultId = hit.first, blob = blob, date = c.getLong(2), durationSec = c.getLong(3), type = c.getInt(4)))
                    ids += c.getLong(0)
                    moved++
                }
            }
            if (ids.isNotEmpty()) cr.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ids.joinToString(",")})", null)
        } catch (_: SecurityException) {
        }
        moved
    }

    suspend fun deletePrivateCall(id: Long) = withContext(Dispatchers.IO) { dao.deletePrivateCall(id) }

    suspend fun expired(now: Long) = withContext(Dispatchers.IO) { dao.expired(now).map { it.id } }
}
