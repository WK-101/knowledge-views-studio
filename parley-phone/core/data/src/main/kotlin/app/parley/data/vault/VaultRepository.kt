package app.parley.data.vault

import android.content.Context
import android.provider.CallLog
import android.util.Base64
import app.parley.common.backup.RecordJson
import app.parley.common.people.CallerCard
import app.parley.common.record.ContactRecord
import app.parley.common.PhoneNumbers
import app.parley.common.NotificationPrivacy
import app.parley.common.VaultNumberKeys
import androidx.room.withTransaction
import app.parley.data.CallerInfo
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import app.parley.data.ContactPhotoProcessor
import app.parley.data.DataItem
import app.parley.data.PhoneEnv
import app.parley.data.R
import app.parley.data.db.AppDatabase
import app.parley.data.db.PrivateCallEntity
import app.parley.data.db.VaultContactEntity
import app.parley.data.db.VaultNumberEntity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** A temporary private contact whose call history goes with it when it expires. */
    val purgeHistory: Boolean = false,
)

/**
 * What the call screen shows for a private contact, readable without unlocking (from the caller-ID copy):
 * job/company, the "who is this" line, the note for calls, and whether an encrypted photo exists.
 */
data class VaultCallerCard(
    val name: String,
    val subtitle: String?,
    val context: String?,
    val note: String?,
    val photoUri: String?,
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

    /**
     * Full details; throws [VaultCrypto.LockedException] if the user must unlock first and
     * [VaultCrypto.KeyUnavailableException] when the Keystore can't open them right now (try again later).
     *
     * When the detail key is gone for good (the screen lock was removed or reset), name, numbers, labels and the
     * caller card survive in the caller-ID copy: they are returned, but nothing is written. The sealed record stays as
     * it is until the user chooses [keepWhatIsLeft] (see [detailsLost]).
     */
    suspend fun details(id: Long): ContactDetails? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        try {
            // The photo is kept apart (encrypted, readable for caller ID); anything older in the record is stale.
            ContactDetailsJson.decode(String(VaultCrypto.openDetail(e.detailBlob))).copy(photoUri = photoUri(id))
        } catch (_: VaultCrypto.KeyLostException) {
            rebuiltFromCallerId(id, JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))).copy(photoUri = photoUri(id))
        }
    }

    /** Whether this entry's full details can no longer be opened (only what the caller-ID copy holds is left). */
    suspend fun detailsLost(id: Long): Boolean = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext false
        try {
            VaultCrypto.openDetail(e.detailBlob)
            false
        } catch (_: VaultCrypto.KeyLostException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The user's choice after [detailsLost]: keep the name, numbers and caller card under the current key. The old
     * sealed record is kept in a file beside the database (a later Keystore recovery could still open it).
     */
    suspend fun keepWhatIsLeft(id: Long): Boolean = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext false
        if (!detailsLost(id)) return@withContext false
        save(id, rebuiltFromCallerId(id, JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))))
        true
    }

    /** Moves a detail blob that can't be opened any more out of the way, never deleting it. */
    private fun setAside(id: Long, blob: ByteArray) {
        val dir = File(context.noBackupFilesDir, "vault-unreadable").apply { mkdirs() }
        File(dir, "$id-${System.currentTimeMillis()}.bin").writeBytes(blob)
    }

    /**
     * Moves every private contact's details to a stronger detail key when the current one is weaker than the phone
     * allows ([VaultCrypto.detailKeyNeedsUpgrade]): no authentication (made before a screen lock existed) or no
     * unlocked-device requirement. Runs right after the user authenticated; all entries change in one transaction, or
     * none do.
     */
    suspend fun upgradeDetailKey(): Boolean = withContext(Dispatchers.IO) {
        // Under the key lock throughout: every save seals and writes under it too, so no blob can be sealed with a
        // key this deletes, and no row read before the upgrade is written back after it.
        keysLock.withLock {
            VaultCrypto.reconcileGenerations(generationsInUse())
            if (!VaultCrypto.detailKeyNeedsUpgrade()) return@withLock false
            VaultCrypto.upgradeDetailKey(
                reseal = { convert ->
                    // Entries whose key was already lost stay as they are; everything else must convert.
                    val converted = dao.all().mapNotNull { e ->
                        try {
                            e.id to convert(e.detailBlob)
                        } catch (_: VaultCrypto.KeyLostException) {
                            null
                        }
                    }
                    // Only the sealed details change: expiry and the caller-ID copy stay as they are now.
                    db.withTransaction { converted.forEach { (id, blob) -> dao.setDetailBlob(id, blob) } }
                    true
                },
                inUse = { generationsInUse() },
            )
        }
    }

    private suspend fun generationsInUse(): Set<Int> = dao.all().map { VaultCrypto.generationOf(it.detailBlob) }.toSet()

    /**
     * The details that survive a lost detail key, from the caller-ID copy [o]: name, numbers and labels, and the
     * caller card's job title and company, "who is this" line and note for calls, so re-sealing loses none of them.
     */
    private fun rebuiltFromCallerId(id: Long, o: JSONObject): ContactDetails {
        val nums = o.optJSONArray("numbers") ?: JSONArray()
        val labels = o.optJSONArray("labels") ?: JSONArray()
        val title = o.optString(C_TITLE)
        val company = o.optString(C_COMPANY)
        // Entries saved before title and company were kept apart only have the combined line: keep it as the title.
        val fallbackTitle = if (title.isEmpty() && company.isEmpty()) o.optString("sub") else title
        return ContactDetails(
            id = -id, lookupKey = "", displayName = o.optString("name"), given = o.optString("name"),
            phones = (0 until nums.length()).map { i -> DataItem(0, nums.getString(i), labels.optInt(i, 2), null) },
            title = fallbackTitle, company = company,
            context = o.optString("ctx"), pinnedNote = o.optString("note"),
        )
    }

    /**
     * Saves a private contact. [expiresAt] makes it temporary (null keeps the current expiry); [purgeHistory] (null
     * keeps the current choice) removes its call history when it expires. [record]: the lossless image of the phone
     * contact it came from ("Move to private", F4); it is sealed with the details (photo included) so moving back out
     * restores every field. Editing an entry later keeps the stored record (see [storedRecord]). [interactions]: the
     * contact's logged interactions ([app.parley.common.circle.Interactions.encodeCarried]), sealed with the details
     * so they come back on "Move out" and are never shown while the contact is private; edits keep them too.
     */
    suspend fun save(
        id: Long?,
        d: ContactDetails,
        expiresAt: Long? = null,
        purgeHistory: Boolean? = null,
        record: ContactRecord? = null,
        recordOf: String? = null,
        interactions: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val name = d.composedName.ifBlank { d.company.ifBlank { d.phones.firstOrNull()?.value ?: context.getString(R.string.data_vault_fallback_name) } }
        val region = region()
        val numbers = d.phones.map { it.value }.filter { it.isNotBlank() }
        // Read, sealed and written under the key lock, so a key upgrade can't delete the key this seals with, and an
        // expiry or a re-seal made meanwhile isn't overwritten with what was read before it.
        keysLock.withLock {
            val existing = id?.let { dao.get(it) }
            val purge = purgeHistory ?: existing?.let { summarize(it)?.purgeHistory } ?: false
            val caller = JSONObject().put("name", name).put("numbers", JSONArray(numbers))
                .put("labels", JSONArray(d.phones.filter { it.value.isNotBlank() }.map { it.type }))
                // The caller card's extra lines, readable while the phone is locked like the name.
                .apply {
                    CallerCard.subtitle(d.title, d.company)?.let { put("sub", it) }
                    // Kept apart too, so a lost detail key can restore them (see rebuiltFromCallerId).
                    d.title.trim().ifEmpty { null }?.let { put(C_TITLE, it) }
                    d.company.trim().ifEmpty { null }?.let { put(C_COMPANY, it) }
                    d.context.trim().ifEmpty { null }?.let { put("ctx", it) }
                    d.pinnedNote.trim().ifEmpty { null }?.let { put("note", it) }
                }
                // When it was last saved, so the newest of two entries sharing a number wins.
                .put("u", System.currentTimeMillis())
                .apply { if (purge) put("purge", true) }
                // The region national numbers were read with, so re-fingerprinting later uses the same one.
                .put(C_REGION, region)
            val detailsJson = ContactDetailsJson.encode(d.copy(photoUri = null))
            val detail = JSONObject(detailsJson)
            if (record != null) {
                val blobs = JSONObject()
                detail.put(REC, RecordJson.encode(record) { h, b -> blobs.put(h, Base64.encodeToString(b, Base64.NO_WRAP)) })
                detail.put(REC_BLOBS, blobs)
                // [recordOf] (a restored backup): the hash stored with the record, so "edited since" survives.
                detail.put(REC_OF, recordOf ?: RecordJson.sha256Hex(ContactDetailsJson.encode(ContactDetailsJson.decode(detailsJson)).toByteArray()))
            }
            if (interactions != null) detail.put(INTERACTIONS, interactions)
            if (existing != null && (record == null || interactions == null)) {
                // Keep the original record (the details hash then no longer matches: it was edited) and the carried
                // interactions through edits.
                val keep = (if (record == null) listOf(REC, REC_BLOBS, REC_OF) else emptyList()) +
                    (if (interactions == null) listOf(INTERACTIONS) else emptyList())
                val old = try {
                    JSONObject(String(VaultCrypto.openDetail(existing.detailBlob)))
                } catch (_: VaultCrypto.KeyLostException) {
                    // The user is saving over a record that can't be opened any more: keep the old blob aside first.
                    setAside(existing.id, existing.detailBlob)
                    null
                }
                old?.let { keep.forEach { k -> if (old.has(k)) detail.put(k, old.get(k)) } }
            }
            val entity = VaultContactEntity(
                id = id ?: 0,
                callerIdBlob = VaultCrypto.sealCallerId(caller.toString().toByteArray()),
                detailBlob = VaultCrypto.sealDetail(detail.toString().toByteArray()),
                expiresAt = expiresAt ?: existing?.expiresAt,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            db.withTransaction {
                val newId = dao.upsert(entity).let { if (id != null) id else it }
                dao.clearNumbers(newId)
                dao.addNumbers(numberRows(newId, numbers, region))
                newId
            }
        }
    }

    private fun region(): String = PhoneEnv.countryIso(context)

    /** Serialises writes of sealed details and fingerprints: saves, expiry changes, key upgrades and re-keying never interleave. */
    private val keysLock = Mutex()

    /**
     * E.164 fingerprints, plus the last-digits one as an extra fallback (so a number read with a different
     * region than at save time is still found by non-exact lookups; exact lookups never use it).
     */
    private fun numberRows(id: Long, numbers: List<String>, region: String?): List<VaultNumberEntity> =
        VaultNumberKeys.storedWithFallback(numbers, region).map { VaultNumberEntity(id, VaultCrypto.hmac(it)) }

    /**
     * Migration, once: entries saved before E.164 keys were fingerprinted by their last 9 digits only. The
     * numbers are in the caller-ID copy, which opens without unlocking, so every entry is re-fingerprinted in place
     * (no schema change: same table, new rows). An entry that can't be read keeps its old rows, so it still works
     * as before. Until this finishes, lookups still find the old rows through the last-digits fallback.
     */
    private suspend fun migrateNumberKeys() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(K_KEYS_VERSION, 1) >= KEYS_VERSION) return@withContext
        val fallbackRegion = region()
        var failed = false
        for (id in dao.all().map { it.id }) {
            // Under the same lock as save and re-read inside the transaction, so a save that ran meanwhile is
            // never overwritten with the numbers it replaced.
            val ok = keysLock.withLock {
                db.withTransaction {
                    val e = dao.get(id) ?: return@withTransaction true
                    val o = runCatching { JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob))) }.getOrNull() ?: return@withTransaction false
                    val s = summarize(e) ?: return@withTransaction false
                    val region = o.optString(C_REGION).ifEmpty { fallbackRegion }
                    val rows = runCatching { numberRows(e.id, s.numbers, region) }.getOrNull() ?: return@withTransaction false
                    dao.clearNumbers(e.id)
                    dao.addNumbers(rows)
                    true
                }
            }
            if (!ok) failed = true
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
        // Settles the key generation from the stored blobs before anything audits it (an interrupted upgrade).
        scope.launch { runCatching { keysLock.withLock { VaultCrypto.reconcileGenerations(generationsInUse()) } } }
    }

    /** Only the expiry column: a re-seal or a save running meanwhile is never undone by an older copy of the row. */
    suspend fun setExpiry(id: Long, expiresAt: Long?) = withContext(Dispatchers.IO) {
        keysLock.withLock { dao.setExpiry(id, expiresAt) }
    }

    /** Deletes a private contact with its fingerprints and private calls, all or nothing; then its photo. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        keysLock.withLock {
            db.withTransaction {
                dao.delete(id)
                dao.clearNumbers(id)
                dao.deletePrivateCalls(id)
            }
        }
        photoFile(id).delete()
        app.parley.data.people.OriginalPhotos.forgetPrivate(context, id)
    }

    /**
     * Caller ID for incoming calls; works without user authentication.
     *
     * Matched on the E.164 form, reading a national number with [countryIso] (the country of the SIM that took
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

    /** The caller card of entry [id] (no unlock needed), or null. */
    suspend fun callerCard(id: Long): VaultCallerCard? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        runCatching {
            val o = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
            VaultCallerCard(
                o.optString("name"), o.optString("sub").ifEmpty { null }, o.optString("ctx").ifEmpty { null },
                o.optString("note").ifEmpty { null }, photoUri(id),
            )
        }.getOrNull()
    }

    // ---- Encrypted photo (the caller-ID key, so the call screen can show it while the phone is locked) ----

    private fun photoDir() = File(context.filesDir, "vault_photos").apply { mkdirs() }
    private fun photoFile(id: Long) = File(photoDir(), "$id.bin")

    /**
     * The in-app URI of entry [id]'s photo (served decrypted only inside Parley by the non-exported vault photo
     * provider), or null when it has none. The file time is part of the URI so a new photo isn't served from cache.
     */
    fun photoUri(id: Long): String? {
        val f = photoFile(id)
        return if (f.isFile) "content://${context.packageName}.vaultphotos/$id/${f.lastModified()}" else null
    }

    /** The decrypted photo (JPEG) of entry [id], or null. */
    fun photoBytes(id: Long): ByteArray? = runCatching {
        val f = photoFile(id)
        if (!f.isFile) null else VaultCrypto.openCallerId(f.readBytes())
    }.getOrNull()

    /** Stores [image] (any format Android decodes) as entry [id]'s photo, scaled down and encrypted. */
    suspend fun setPhoto(id: Long, image: ByteArray): Boolean = withContext(Dispatchers.IO) {
        // Bounded decode, upright, centre square (512 px is plenty for a caller photo).
        val jpeg = ContactPhotoProcessor.process(image, PHOTO_PX) ?: return@withContext false
        val tmp = File(photoDir(), "$id.tmp")
        tmp.writeBytes(VaultCrypto.sealCallerId(jpeg))
        tmp.renameTo(photoFile(id))
    }

    fun removePhoto(id: Long) {
        photoFile(id).delete()
    }

    /** Expired entries with what housekeeping needs to clean up after them. */
    suspend fun expiredEntries(now: Long): List<VaultSummary> = withContext(Dispatchers.IO) {
        dao.expired(now).map { e -> summarize(e) ?: VaultSummary(e.id, "", emptyList(), e.expiresAt) }
    }

    /**
     * Moves call-log rows for vault numbers out of the system log into the encrypted private
     * history (needs WRITE_CALL_LOG, granted by the dialer role). Returns rows moved.
     *
     * A provider row is deleted only once its private copy is stored (or was already, from an earlier sweep whose
     * delete failed): the unique dedupe key means a retry never stores a call twice, and a failed insert never loses
     * the call.
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
                    val date = c.getLong(2)
                    val type = c.getInt(4)
                    val stored = runCatching { storePrivateCall(hit.first, number, hit.second.name, date, c.getLong(3), type) }.getOrDefault(false)
                    if (!stored) continue
                    ids += c.getLong(0)
                    moved++
                }
            }
            ids.chunked(500).forEach { chunk -> cr.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${chunk.joinToString(",")})", null) }
        } catch (_: SecurityException) {
        }
        moved
    }

    /**
     * Stores one private call unless it is already there (a sweep retried, a restore run twice). True when the call is
     * now stored, whether by this call or before.
     */
    suspend fun storePrivateCall(vaultId: Long, number: String, name: String, date: Long, durationSec: Long, type: Int): Boolean = withContext(Dispatchers.IO) {
        // Rows from before the dedupe key have none: count those by their columns.
        if (dao.countPrivateCall(vaultId, date, type) > 0) return@withContext true
        val blob = VaultCrypto.sealCallerId(JSONObject().put("n", number).put("name", name).toString().toByteArray())
        dao.addPrivateCall(PrivateCallEntity(vaultId = vaultId, blob = blob, date = date, durationSec = durationSec, type = type, dedupeKey = PrivateCallEntity.dedupeKey(vaultId, date, type)))
        true
    }

    suspend fun deletePrivateCall(id: Long) = withContext(Dispatchers.IO) { dao.deletePrivateCall(id) }

    suspend fun expired(now: Long) = withContext(Dispatchers.IO) { dao.expired(now).map { it.id } }

    /** The lossless phone-contact image stored by "Move to private", and whether the details were edited since. */
    data class StoredRecord(val record: ContactRecord, val editedSince: Boolean, val recordOf: String = "")

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
        StoredRecord(record, RecordJson.sha256Hex(now.toByteArray()) != o.optString(REC_OF), o.optString(REC_OF))
    }

    /**
     * The interactions carried into entry [id] by "Move to private" ([app.parley.common.circle.Interactions.encodeCarried]
     * text), or null. Throws [VaultCrypto.LockedException] when the vault must be unlocked first.
     */
    suspend fun storedInteractions(id: Long): String? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        JSONObject(String(VaultCrypto.openDetail(e.detailBlob))).optString(INTERACTIONS).takeIf { it.isNotEmpty() }
    }

    /** Every private contact, read straight from the database (not the UI flow, which starts empty). */
    suspend fun summariesNow(): List<VaultSummary> = withContext(Dispatchers.IO) { dao.all().mapNotNull { summarize(it) } }

    /** The private calls of entry [vaultId], read straight from the database (backup). */
    suspend fun privateCallsOf(vaultId: Long): List<PrivateCall> = withContext(Dispatchers.IO) {
        dao.allPrivateCalls().filter { it.vaultId == vaultId }.mapNotNull { c ->
            runCatching {
                val o = JSONObject(String(VaultCrypto.openCallerId(c.blob)))
                PrivateCall(c.id, c.vaultId, o.optString("n"), o.optString("name"), c.date, c.durationSec, c.type)
            }.getOrNull()
        }
    }

    /** Every private contact's numbers, read straight from the database (import duplicate checks, F17). */
    suspend fun allNumbers(): List<String> = withContext(Dispatchers.IO) { dao.all().mapNotNull { summarize(it) }.flatMap { it.numbers } }

    private companion object {
        const val PREFS = "vault"
        const val PHOTO_PX = 512
        const val K_KEYS_VERSION = "number_keys_version"
        const val K_KEYS_ATTEMPTS = "number_keys_attempts"
        /**
         * 1: last 9 digits (before F7); 2: E.164 with the last digits only as a fallback; 3: E.164 plus the last
         * digits as an extra fallback for every number, with the region stored at save time.
         */
        const val KEYS_VERSION = 3
        const val REC = "parleyRecord"
        const val REC_BLOBS = "parleyRecordBlobs"
        const val REC_OF = "parleyRecordOf"
        const val INTERACTIONS = "parleyInteractions"
        const val C_TITLE = "t"
        const val C_COMPANY = "co"
        const val C_REGION = "rg"
    }
}
