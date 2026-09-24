package app.parley.data.vault

import android.content.Context
import android.provider.CallLog
import android.util.Base64
import app.parley.common.backup.RecordJson
import app.parley.common.record.ContactRecord
import app.parley.common.PhoneNumbers
import app.parley.common.NotificationPrivacy
import app.parley.common.VaultNumberKeys
import androidx.room.withTransaction
import app.parley.data.CallerInfo
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import app.parley.data.DataItem
import app.parley.data.PhoneEnv
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Readable without unlocking: enough to show who is calling and list the vault. */
data class VaultSummary(
    val id: Long,
    val name: String,
    val numbers: List<String>,
    val expiresAt: Long?,
    /** Last saved (from the caller-ID copy; the creation time for entries saved before this was recorded). */
    val updatedAt: Long = 0,
    /** A temporary private contact whose call history goes with it when it expires (F5). */
    val purgeHistory: Boolean = false,
)

data class PrivateCall(val id: Long, val vaultId: Long, val number: String, val name: String, val date: Long, val durationSec: Long, val type: Int)

/**
 * Private contacts stored only inside Parley (encrypted), invisible to every other app.
 * Caller ID uses an HMAC index + the caller-ID key, so it works even while the phone is locked.
 */
class VaultRepository(private val context: Context, private val db: AppDatabase, scope: CoroutineScope) {
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
        VaultSummary(
            e.id, o.optString("name"), (0 until nums.length()).map { nums.getString(it) }, e.expiresAt,
            updatedAt = o.optLong("u", e.createdAt), purgeHistory = o.optBoolean("purge", false),
        )
    }.getOrNull()

    /** Full details; throws [VaultCrypto.LockedException] if the user must unlock first. */
    suspend fun details(id: Long): ContactDetails? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        try {
            ContactDetailsJson.decode(String(VaultCrypto.openDetail(e.detailBlob)))
        } catch (_: VaultCrypto.KeyLostException) {
            // The screen lock was removed or reset, which destroys the unlock-bound key. Name, numbers and labels
            // survive in the caller-ID copy: rebuild from them and re-seal under a new key.
            val o = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
            val nums = o.optJSONArray("numbers") ?: JSONArray()
            val labels = o.optJSONArray("labels") ?: JSONArray()
            val d = ContactDetails(
                id = -id, lookupKey = "", displayName = o.optString("name"), given = o.optString("name"),
                phones = (0 until nums.length()).map { i -> DataItem(0, nums.getString(i), labels.optInt(i, 2), null) },
            )
            runCatching { save(id, d) }
            d
        }
    }

    /**
     * Saves a private contact. [expiresAt] makes it temporary (null keeps the current expiry); [purgeHistory] (null
     * keeps the current choice) removes its call history when it expires. [record]: the lossless image of the phone
     * contact it came from ("Move to private", F4); it is sealed with the details (photo included) so moving back out
     * restores every field. Editing an entry later keeps the stored record (see [storedRecord]).
     */
    suspend fun save(
        id: Long?,
        d: ContactDetails,
        expiresAt: Long? = null,
        purgeHistory: Boolean? = null,
        record: ContactRecord? = null,
    ): Long = withContext(Dispatchers.IO) {
        val name = d.composedName.ifBlank { d.company.ifBlank { d.phones.firstOrNull()?.value ?: "Private contact" } }
        val numbers = d.phones.map { it.value }.filter { it.isNotBlank() }
        val existing = id?.let { dao.get(it) }
        val purge = purgeHistory ?: existing?.let { summarize(it)?.purgeHistory } ?: false
        val caller = JSONObject().put("name", name).put("numbers", JSONArray(numbers))
            .put("labels", JSONArray(d.phones.filter { it.value.isNotBlank() }.map { it.type }))
            // F15: when it was last saved, so the newest of two entries sharing a number wins.
            .put("u", System.currentTimeMillis())
            .apply { if (purge) put("purge", true) }
        val detailsJson = ContactDetailsJson.encode(d)
        val detail = JSONObject(detailsJson)
        if (record != null) {
            val blobs = JSONObject()
            detail.put(REC, RecordJson.encode(record) { h, b -> blobs.put(h, Base64.encodeToString(b, Base64.NO_WRAP)) })
            detail.put(REC_BLOBS, blobs)
            detail.put(REC_OF, RecordJson.sha256Hex(ContactDetailsJson.encode(ContactDetailsJson.decode(detailsJson)).toByteArray()))
        } else if (existing != null) {
            // Keep the original record through edits (the details hash then no longer matches: it was edited).
            runCatching { JSONObject(String(VaultCrypto.openDetail(existing.detailBlob))) }.getOrNull()?.let { old ->
                listOf(REC, REC_BLOBS, REC_OF).forEach { k -> if (old.has(k)) detail.put(k, old.get(k)) }
            }
        }
        val entity = VaultContactEntity(
            id = id ?: 0,
            callerIdBlob = VaultCrypto.sealCallerId(caller.toString().toByteArray()),
            detailBlob = VaultCrypto.sealDetail(detail.toString().toByteArray()),
            expiresAt = expiresAt ?: existing?.expiresAt,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        val region = region()
        db.withTransaction {
            val newId = dao.upsert(entity).let { if (id != null) id else it }
            dao.clearNumbers(newId)
            dao.addNumbers(numberRows(newId, numbers, region))
            newId
        }
    }

    private fun region(): String = PhoneEnv.countryIso(context)

    /** F7: E.164 fingerprints (the last-digits one only for numbers without an E.164 form). */
    private fun numberRows(id: Long, numbers: List<String>, region: String?): List<VaultNumberEntity> =
        VaultNumberKeys.storedAll(numbers, region).map { VaultNumberEntity(id, VaultCrypto.hmac(it)) }

    /**
     * F7 migration, once: entries saved before E.164 keys were fingerprinted by their last 9 digits only. The
     * numbers are in the caller-ID copy, which opens without unlocking, so every entry is re-fingerprinted in place
     * (no schema change: same table, new rows). An entry that can't be read keeps its old rows, so it still works
     * as before. Until this finishes, lookups still find the old rows through the last-digits fallback.
     */
    private suspend fun migrateNumberKeys() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(K_KEYS_VERSION, 1) >= KEYS_VERSION) return@withContext
        val region = region()
        var failed = false
        for (e in dao.all()) {
            val s = summarize(e)
            if (s == null) { failed = true; continue }
            val rows = runCatching { numberRows(e.id, s.numbers, region) }.getOrElse { failed = true; null } ?: continue
            db.withTransaction {
                dao.clearNumbers(e.id)
                dao.addNumbers(rows)
            }
        }
        // An unreadable entry (caller-ID key lost) can't get better by retrying; a Keystore hiccup might.
        if (!failed || prefs.getInt(K_KEYS_ATTEMPTS, 0) >= 2) {
            prefs.edit().putInt(K_KEYS_VERSION, KEYS_VERSION).apply()
        } else {
            prefs.edit().putInt(K_KEYS_ATTEMPTS, prefs.getInt(K_KEYS_ATTEMPTS, 0) + 1).apply()
        }
    }

    init {
        scope.launch { runCatching { migrateNumberKeys() } }
    }

    suspend fun setExpiry(id: Long, expiresAt: Long?) = withContext(Dispatchers.IO) {
        dao.get(id)?.let { dao.upsert(it.copy(expiresAt = expiresAt)) }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.delete(id)
        dao.clearNumbers(id)
        dao.deletePrivateCalls(id)
    }

    /**
     * Caller ID for incoming calls; works without user authentication.
     *
     * F7: matched on the E.164 form, reading a national number with [countryIso] (the country of the SIM that took
     * the call when known, else this phone's region); the last digits are only a fallback for entries stored without
     * an E.164 form, and [exact] (the private-name provider) never uses them. F15: expired entries never match, and
     * of several entries sharing a number the most recently updated wins.
     */
    suspend fun lookup(number: String, countryIso: String? = null, exact: Boolean = false): Pair<Long, CallerInfo>? = withContext(Dispatchers.IO) {
        if (number.isBlank()) return@withContext null
        val now = System.currentTimeMillis()
        for (input in VaultNumberKeys.lookup(number, countryIso ?: region(), exact)) {
            val ids = dao.idsByHmac(listOf(VaultCrypto.hmac(input)))
            if (ids.isEmpty()) continue
            val candidates = ids.mapNotNull { id ->
                val e = dao.get(id) ?: return@mapNotNull null
                val s = summarize(e) ?: return@mapNotNull null
                s to VaultNumberKeys.Candidate(id, s.updatedAt, e.createdAt, e.expiresAt)
            }
            val win = VaultNumberKeys.winner(candidates.map { it.second }, now) ?: continue
            val s = candidates.first { it.second.id == win.id }.first
            return@withContext win.id to CallerInfo(
                contactId = -win.id, lookupKey = null, name = s.name, photoUri = null,
                numberLabel = NotificationPrivacy.VAULT_LABEL, customRingtone = null, sendToVoicemail = false,
            )
        }
        null
    }

    /** Expired entries with what housekeeping needs to clean up after them (F5, F13). */
    suspend fun expiredEntries(now: Long): List<VaultSummary> = withContext(Dispatchers.IO) {
        dao.expired(now).map { e -> summarize(e) ?: VaultSummary(e.id, "", emptyList(), e.expiresAt) }
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

    /** The lossless phone-contact image stored by "Move to private", and whether the details were edited since. */
    data class StoredRecord(val record: ContactRecord, val editedSince: Boolean)

    /**
     * The [StoredRecord] of entry [id], or null for entries made in the vault or before records were kept. Throws
     * [VaultCrypto.LockedException] when the vault must be unlocked first.
     */
    suspend fun storedRecord(id: Long): StoredRecord? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        val o = JSONObject(String(VaultCrypto.openDetail(e.detailBlob)))
        val line = o.optString(REC).takeIf { it.isNotEmpty() } ?: return@withContext null
        val blobs = o.optJSONObject(REC_BLOBS)
        val record = runCatching { RecordJson.decode(line) { h -> blobs?.optString(h)?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) } } }.getOrNull()
            ?: return@withContext null
        val now = ContactDetailsJson.encode(ContactDetailsJson.decode(o.toString()))
        StoredRecord(record, RecordJson.sha256Hex(now.toByteArray()) != o.optString(REC_OF))
    }

    /** Every private contact's numbers, read straight from the database (import duplicate checks, F17). */
    suspend fun allNumbers(): List<String> = withContext(Dispatchers.IO) { dao.all().mapNotNull { summarize(it) }.flatMap { it.numbers } }

    private companion object {
        const val PREFS = "vault"
        const val K_KEYS_VERSION = "number_keys_version"
        const val K_KEYS_ATTEMPTS = "number_keys_attempts"
        /** 1: last 9 digits (before F7); 2: E.164 with the last digits only as a fallback. */
        const val KEYS_VERSION = 2
        const val REC = "parleyRecord"
        const val REC_BLOBS = "parleyRecordBlobs"
        const val REC_OF = "parleyRecordOf"
    }
}
