package app.parley.data.vault

import android.content.Context
import android.provider.CallLog
import android.util.Base64
import app.parley.common.backup.RecordJson
import app.parley.common.people.CallerCard
import app.parley.common.people.PrivateCallerChoices
import app.parley.common.people.PrivateLabels
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
    /** A favourite: Parley's own star (private contacts aren't in the address book, whose star other apps read). */
    val starred: Boolean = false,
    /** Its labels (the address book's groups; only the membership is kept here, see [PrivateLabels]). */
    val labels: List<PrivateLabels.Membership> = emptyList(),
    /** Its own ringtone, played by Parley's ringer when it calls. */
    val ringtone: String? = null,
    /** Its calls are declined to voicemail by Parley's call screening. */
    val sendToVoicemail: Boolean = false,
    /**
     * The caller-ID copy holds the star, labels, ringtone and "send to voicemail". False for an entry saved before
     * they were kept there and not seeded yet ([VaultRepository.seedCallerChoices]): the fields above are then only
     * defaults, and nothing may be overwritten with them.
     */
    val choicesKnown: Boolean = true,
) {
    /** Anything the call path must apply for this contact (Parley screens its calls then). */
    val hasCallChoices: Boolean get() = ringtone != null || sendToVoicemail || labels.isNotEmpty()
}

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
 * [VaultRepository.hasCallChoices] without building the vault: false until it has been read. A call in a process
 * started for it is screened by the call-screening service anyway, which reads the vault itself.
 */
object VaultCallChoices {
    @Volatile var any: Boolean = false

    /**
     * Whether [any] has been read yet. Until then the call path treats it as "yes": in a process started for a call,
     * other settings may finish loading first, and a private contact's voicemail or ringtone must not be skipped.
     */
    @Volatile var loaded: Boolean = false
}

/** A private contact exactly as the vault stores it, every part still sealed ([VaultRepository.sealedCopy]). */
class SealedEntry(
    val callerIdBlob: ByteArray,
    val detailBlob: ByteArray,
    val expiresAt: Long?,
    val createdAt: Long,
    /** The photo file's bytes, sealed with the caller-ID key. */
    val photo: ByteArray?,
    val calls: List<SealedCall>,
)

/** One private call, its number and name still sealed. */
class SealedCall(val blob: ByteArray, val date: Long, val durationSec: Long, val type: Int)

/**
 * Private contacts stored only inside Parley (encrypted), invisible to every other app.
 * Caller ID uses an HMAC index + the caller-ID key, so it works even while the phone is locked.
 */
class VaultRepository(private val context: Context, private val db: AppDatabase, scope: CoroutineScope) {
    private val dao = db.vaultDao()

    /**
     * The address book's labels (id, title), set by the container: a private contact's label membership is stored by
     * group id and title ([PrivateLabels]) and read back as the labels are now. Empty when contacts can't be read.
     */
    @Volatile var labelGroups: () -> List<PrivateLabels.Group> = { emptyList() }

    /**
     * Whether any private contact has a ringtone, "send to voicemail" or labels, so the call path screens calls even
     * when no other screening feature is on. Memory only (the call path asks on the main thread, see
     * [VaultCallChoices]): kept from the last listing and remembered across restarts.
     */
    var hasCallChoices: Boolean
        get() = VaultCallChoices.any
        private set(v) {
            VaultCallChoices.any = v
        }

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
            starred = o.optBoolean(C_STAR, false), labels = labelsOf(o),
            ringtone = o.optString(C_TONE).ifEmpty { null }, sendToVoicemail = o.optBoolean(C_VOICEMAIL, false),
            choicesKnown = o.has(C_SEEDED),
        )
    }.getOrNull()

    private fun labelsOf(o: JSONObject): List<PrivateLabels.Membership> {
        val a = o.optJSONArray(C_LABELS) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { PrivateLabels.Membership(it.optLong("i"), it.optString("t")) } }
            .filter { it.title.isNotBlank() }
    }

    private fun putLabels(o: JSONObject, labels: List<PrivateLabels.Membership>) {
        if (labels.isEmpty()) o.remove(C_LABELS) else o.put(C_LABELS, JSONArray(labels.map { JSONObject().put("i", it.groupId).put("t", it.title) }))
    }

    /**
     * What the caller-ID copy [o] keeps for the call path and the lists (the star, labels, ringtone and "send to
     * voicemail") laid over details read from the sealed record: it is the one place they are stored, so they can be
     * changed without unlocking and are applied to calls while the phone is locked.
     */
    private fun withCallerChoices(d: ContactDetails, o: JSONObject): ContactDetails = if (!o.has(C_SEEDED)) d else d.copy(
        starred = o.optBoolean(C_STAR, false),
        groupIds = PrivateLabels.ids(labelsOf(o), runCatching { labelGroups() }.getOrDefault(emptyList())),
        customRingtone = o.optString(C_TONE).ifEmpty { null },
        sendToVoicemail = o.optBoolean(C_VOICEMAIL, false),
    )

    /**
     * Full details; throws [VaultCrypto.LockedException] if the user must unlock first and
     * [VaultCrypto.KeyUnavailableException] when the Keystore can't open them right now (try again later).
     *
     * When the detail key is gone for good (the screen lock was removed or reset), name, numbers, labels and the
     * caller card survive in the caller-ID copy: they are returned, but nothing is written. The sealed record stays as
     * it is until the user chooses [keepWhatIsLeft] (see [detailsLost]).
     */
    suspend fun details(id: Long): ContactDetails? = withContext(Dispatchers.IO) {
        // An entry from before the caller-ID copy kept the star, labels, ringtone and voicemail gets them now (the
        // details are being opened anyway), so the page, the editor and "Make visible" see them.
        if (dao.get(id)?.let { summarize(it)?.choicesKnown } == false) runCatching { seedCallerChoices(id) }
        val e = dao.get(id) ?: return@withContext null
        val caller = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
        val d = try {
            // The photo is kept apart (encrypted, readable for caller ID); anything older in the record is stale.
            ContactDetailsJson.decode(String(VaultCrypto.openDetail(e.detailBlob))).copy(photoUri = photoUri(id))
        } catch (_: VaultCrypto.KeyLostException) {
            rebuiltFromCallerId(id, caller).copy(photoUri = photoUri(id))
        }
        withCallerChoices(d, caller)
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

    /** Detail key generations still needed: the entries', and sealed copies kept by "Recently deleted". */
    private suspend fun generationsInUse(): Set<Int> =
        dao.all().map { VaultCrypto.generationOf(it.detailBlob) }.toSet() + runCatching { keptGenerations() }.getOrDefault(emptySet())

    /** Generations of detail blobs kept outside the table (set by the container: [PrivateTrash]). */
    @Volatile var keptGenerations: () -> Set<Int> = { emptySet() }

    /**
     * Entry [id] exactly as stored, still sealed (its details under the detail key, the rest under the caller-ID key),
     * with its photo file and private calls: what "Recently deleted" keeps. Nothing is opened, so no unlock is needed.
     */
    suspend fun sealedCopy(id: Long): SealedEntry? = withContext(Dispatchers.IO) {
        keysLock.withLock {
            val e = dao.get(id) ?: return@withLock null
            val photo = photoFile(id).takeIf { it.isFile }?.readBytes()
            val calls = dao.allPrivateCalls().filter { it.vaultId == id }.map { SealedCall(it.blob, it.date, it.durationSec, it.type) }
            SealedEntry(e.callerIdBlob, e.detailBlob, e.expiresAt, e.createdAt, photo, calls)
        }
    }

    /** Puts a [sealedCopy] back as a new entry (its fingerprints rebuilt); returns its id. */
    suspend fun restoreSealed(s: SealedEntry): Long = withContext(Dispatchers.IO) {
        val caller = JSONObject(String(VaultCrypto.openCallerId(s.callerIdBlob)))
        val nums = caller.optJSONArray("numbers") ?: JSONArray()
        val numbers = (0 until nums.length()).map { nums.getString(it) }
        val region = caller.optString(C_REGION).ifEmpty { region() }
        // A temporary contact whose date passed meanwhile would be deleted again at once: it comes back permanent.
        val expiresAt = s.expiresAt?.takeIf { it > System.currentTimeMillis() }
        val id = keysLock.withLock {
            db.withTransaction {
                val newId = dao.upsert(
                    VaultContactEntity(callerIdBlob = s.callerIdBlob, detailBlob = s.detailBlob, expiresAt = expiresAt, createdAt = s.createdAt),
                )
                dao.addNumbers(numberRows(newId, numbers, region))
                s.calls.forEach { c ->
                    val key = PrivateCallEntity.dedupeKey(newId, c.date, c.type)
                    dao.addPrivateCall(
                        PrivateCallEntity(vaultId = newId, blob = c.blob, date = c.date, durationSec = c.durationSec, type = c.type, dedupeKey = key),
                    )
                }
                newId
            }
        }
        s.photo?.let { bytes ->
            val tmp = File(photoDir(), "$id.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(photoFile(id))
        }
        if (caller.optBoolean(C_VOICEMAIL) || caller.has(C_TONE) || caller.has(C_LABELS)) noteCallChoices(true)
        id
    }

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
     * [loaded]: the editor's starting point for an edit, so only the star, labels, ringtone and voicemail it changed
     * are written over the entry's current ones.
     */
    @Suppress("CyclomaticComplexMethod") // One sealed write: caller-ID copy, details and fingerprints together.
    suspend fun save(
        id: Long?,
        d: ContactDetails,
        expiresAt: Long? = null,
        purgeHistory: Boolean? = null,
        record: ContactRecord? = null,
        recordOf: String? = null,
        interactions: String? = null,
        loaded: ContactDetails? = null,
    ): Long = withContext(Dispatchers.IO) {
        val name = d.composedName.ifBlank { d.company.ifBlank { d.phones.firstOrNull()?.value ?: context.getString(R.string.data_vault_fallback_name) } }
        val region = region()
        val numbers = d.phones.map { it.value }.filter { it.isNotBlank() }
        // Read, sealed and written under the key lock, so a key upgrade can't delete the key this seals with, and an
        // expiry or a re-seal made meanwhile isn't overwritten with what was read before it.
        keysLock.withLock {
            val existing = id?.let { dao.get(it) }
            val existingSummary = existing?.let { summarize(it) }
            val purge = purgeHistory ?: existingSummary?.purgeHistory ?: false
            val groups = runCatching { labelGroups() }.getOrDefault(emptyList())
            // [loaded]: the editor's starting point. Only what the editor changed is taken from it; the rest stays as the
            // entry has it now (a star or a label changed from the list while the editor was open).
            val base = existingSummary?.takeIf { loaded != null && it.choicesKnown }
            val shown = if (base == null || loaded == null) d else d.copy(
                starred = if (d.starred != loaded.starred) d.starred else base.starred,
                customRingtone = if (d.customRingtone != loaded.customRingtone) d.customRingtone else base.ringtone,
                sendToVoicemail = if (d.sendToVoicemail != loaded.sendToVoicemail) d.sendToVoicemail else base.sendToVoicemail,
            )
            // Labels as the editor chose them, keeping any it couldn't show (a label that isn't there right now).
            val labels = if (base == null || loaded == null) {
                PrivateLabels.fromIds(shown.groupIds, groups, existingSummary?.labels.orEmpty())
            } else {
                PrivateLabels.edited(base.labels, added = shown.groupIds - loaded.groupIds, removed = loaded.groupIds - shown.groupIds, groups)
            }
            val caller = JSONObject().put("name", name).put("numbers", JSONArray(numbers))
                .put("labels", JSONArray(shown.phones.filter { it.value.isNotBlank() }.map { it.type }))
                // The caller card's extra lines, readable while the phone is locked like the name.
                .apply {
                    CallerCard.subtitle(shown.title, shown.company)?.let { put("sub", it) }
                    // Kept apart too, so a lost detail key can restore them (see rebuiltFromCallerId).
                    shown.title.trim().ifEmpty { null }?.let { put(C_TITLE, it) }
                    shown.company.trim().ifEmpty { null }?.let { put(C_COMPANY, it) }
                    shown.context.trim().ifEmpty { null }?.let { put("ctx", it) }
                    shown.pinnedNote.trim().ifEmpty { null }?.let { put("note", it) }
                }
                // When it was last saved, so the newest of two entries sharing a number wins.
                .put("u", System.currentTimeMillis())
                .apply { if (purge) put("purge", true) }
                // In Favourites while the vault is locked, like the name in Contacts.
                .apply { if (shown.starred) put(C_STAR, true) }
                // Labels, ringtone and "send to voicemail": applied to calls while the phone is locked.
                .apply {
                    putLabels(this, labels)
                    shown.customRingtone?.takeIf { it.isNotBlank() }?.let { put(C_TONE, it) }
                    if (shown.sendToVoicemail) put(C_VOICEMAIL, true)
                }
                // The region national numbers were read with, so re-fingerprinting later uses the same one.
                .put(C_REGION, region)
                // The four choices above are this copy's own from now on (see seedCallerChoices).
                .put(C_SEEDED, 1)
            val detailsJson = ContactDetailsJson.encode(shown.copy(photoUri = null))
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
            }.also { noteCallChoices(labels.isNotEmpty() || shown.sendToVoicemail || !shown.customRingtone.isNullOrBlank()) }
        }
    }

    /**
     * Changes only entry [id]'s caller-ID copy ([change] edits its JSON): the star, labels, ringtone and "send to
     * voicemail" live there, so they change without unlocking the vault and the sealed details are never rewritten.
     * False when the entry is gone or its caller-ID copy can't be opened.
     */
    suspend fun updateCallerChoices(id: Long, change: (VaultSummary) -> VaultSummary): Boolean = withContext(Dispatchers.IO) {
        // An entry not seeded yet would have its old star, tone and labels replaced by the defaults: seed it first when
        // its details can be opened (a locked vault keeps the change to what the caller-ID copy has).
        if (dao.get(id)?.let { summarize(it)?.choicesKnown } == false) runCatching { seedCallerChoices(id) }
        keysLock.withLock {
            val e = dao.get(id) ?: return@withLock false
            val o = runCatching { JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob))) }.getOrNull() ?: return@withLock false
            val before = summarize(e) ?: return@withLock false
            val after = change(before)
            if (after == before) return@withLock true
            if (after.starred) o.put(C_STAR, true) else o.remove(C_STAR)
            putLabels(o, after.labels)
            if (after.ringtone.isNullOrBlank()) o.remove(C_TONE) else o.put(C_TONE, after.ringtone)
            if (after.sendToVoicemail) o.put(C_VOICEMAIL, true) else o.remove(C_VOICEMAIL)
            // "u" stays: which of two entries sharing a number wins follows edits of the contact, not a star or a label.
            dao.setCallerIdBlob(id, VaultCrypto.sealCallerId(o.toString().toByteArray()))
            noteCallChoices(after.hasCallChoices)
            true
        }
    }

    /**
     * Fills entry [id]'s caller-ID copy with the star, labels, ringtone and "send to voicemail" it had before they were
     * kept there: from its sealed details and the address-book record it was moved in with
     * ([app.parley.common.people.PrivateCallerChoices]). Only keys the copy doesn't have yet are written, then it is
     * marked, so this runs once per entry. Throws [VaultCrypto.LockedException] when the details can't be opened now
     * (nothing changes); an entry whose detail key is lost is marked as it is. True when the entry is seeded now.
     */
    @Suppress("CyclomaticComplexMethod") // Each of the four choices is seeded only when absent: one check each.
    suspend fun seedCallerChoices(id: Long): Boolean = withContext(Dispatchers.IO) {
        keysLock.withLock {
            val e = dao.get(id) ?: return@withLock false
            val o = runCatching { JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob))) }.getOrNull() ?: return@withLock false
            if (o.has(C_SEEDED)) return@withLock true
            val detail = try {
                JSONObject(String(VaultCrypto.openDetail(e.detailBlob)))
            } catch (_: VaultCrypto.KeyLostException) {
                null
            }
            if (detail != null) {
                // Photos aren't needed for this: their blobs are left out.
                val record = detail.optString(REC).takeIf { it.isNotEmpty() }?.let { line -> runCatching { RecordJson.decode(line) { null } }.getOrNull() }
                val seed = PrivateCallerChoices.seed(
                    detail.optBoolean("starred"), detail.optString("ringtone").ifEmpty { null }, detail.optBoolean("vm"), record,
                )
                if (!o.has(C_STAR) && seed.starred) o.put(C_STAR, true)
                if (!o.has(C_LABELS)) putLabels(o, seed.labels)
                if (!o.has(C_TONE)) seed.ringtone?.let { o.put(C_TONE, it) }
                if (!o.has(C_VOICEMAIL) && seed.sendToVoicemail) o.put(C_VOICEMAIL, true)
            }
            o.put(C_SEEDED, 1)
            dao.setCallerIdBlob(id, VaultCrypto.sealCallerId(o.toString().toByteArray()))
            noteCallChoices(o.optBoolean(C_VOICEMAIL) || o.has(C_TONE) || o.has(C_LABELS))
            true
        }
    }

    /**
     * Migration, once, after the vault's unlock: [seedCallerChoices] for every entry saved before the caller-ID copy
     * kept the star, labels, ringtone and voicemail. Stops while the vault is locked and runs again on the next unlock;
     * marked done when every entry is seeded. Idempotent. Returns how many entries were seeded now.
     */
    suspend fun migrateCallerChoices(): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(K_CHOICES_SEEDED, false)) return@withContext 0
        var seeded = 0
        var pending = false
        for (e in dao.all()) {
            if (summarize(e)?.choicesKnown != false) continue
            try {
                if (seedCallerChoices(e.id)) seeded++ else pending = true
            } catch (_: VaultCrypto.LockedException) {
                return@withContext seeded
            } catch (_: VaultCrypto.KeyUnavailableException) {
                pending = true
            }
        }
        if (!pending) prefs.edit().putBoolean(K_CHOICES_SEEDED, true).apply()
        seeded
    }

    /** Entry [id]'s summary (caller-ID copy), read straight from the database; null when gone or unreadable. */
    suspend fun summary(id: Long): VaultSummary? = withContext(Dispatchers.IO) { dao.get(id)?.let { summarize(it) } }

    /** A save or change gave a private contact something the call path applies. */
    private fun noteCallChoices(any: Boolean) {
        if (any) rememberCallChoices(true)
    }

    /** Set from the full listing; also stored, for a process started for a call before the listing is read. */
    private fun rememberCallChoices(any: Boolean) {
        if (hasCallChoices == any) return
        hasCallChoices = any
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_CALL_CHOICES, any).apply()
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
        scope.launch(Dispatchers.IO) {
            runCatching { if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_CALL_CHOICES, false)) hasCallChoices = true }
            VaultCallChoices.loaded = true
            // Then the listing decides (a stale "yes" costs only a lookup per call; a "no" only once none has any).
            runCatching { dao.contacts().collect { list -> rememberCallChoices(list.any { e -> summarize(e)?.hasCallChoices == true }) } }
        }
        scope.launch { runCatching { migrateNumberKeys() } }
        // Settles the key generation from the stored blobs before anything audits it (an interrupted upgrade).
        scope.launch { runCatching { keysLock.withLock { VaultCrypto.reconcileGenerations(generationsInUse()) } } }
    }

    /**
     * Only the expiry column, and with [purgeHistory] (null: unchanged) the call-history choice, which the caller-ID
     * copy keeps: the sealed details are never re-sealed, so this works while the vault is locked or its detail key is
     * lost, and a re-seal or a save running meanwhile is never undone by an older copy of the row.
     */
    suspend fun setExpiry(id: Long, expiresAt: Long?, purgeHistory: Boolean? = null) = withContext(Dispatchers.IO) {
        keysLock.withLock {
            dao.setExpiry(id, expiresAt)
            if (purgeHistory == null) return@withLock
            val e = dao.get(id) ?: return@withLock
            val o = runCatching { JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob))) }.getOrNull() ?: return@withLock
            if (o.optBoolean("purge", false) == purgeHistory) return@withLock
            if (purgeHistory) o.put("purge", true) else o.remove("purge")
            dao.setCallerIdBlob(id, VaultCrypto.sealCallerId(o.toString().toByteArray()))
        }
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
                numberLabel = NotificationPrivacy.VAULT_LABEL, customRingtone = s.ringtone, sendToVoicemail = s.sendToVoicemail,
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

    /** Deletes entry [id]'s photo; off the main thread, like [setPhoto] (the editor's save calls it from there). */
    suspend fun removePhoto(id: Long) = withContext(Dispatchers.IO) { photoFile(id).delete() }

    /** Expired entries with what housekeeping needs to clean up after them. */
    suspend fun expiredEntries(now: Long): List<VaultSummary> = withContext(Dispatchers.IO) {
        dao.expired(now).map { e -> summarize(e) ?: VaultSummary(e.id, "", emptyList(), e.expiresAt) }
    }

    /**
     * Entries being made visible ([VaultMoves.moveOut]): their calls were just put back into the phone's call history,
     * so a sweep running meanwhile must not take them back into the private history the entry is deleted with.
     */
    val leaving: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
                    // A contact being made visible: its calls are going back to this log, never into its private history.
                    if (hit.first in leaving) continue
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

    /** How many private calls entry [vaultId] holds, whether or not each can be opened now. */
    suspend fun privateCallCount(vaultId: Long): Int = withContext(Dispatchers.IO) { dao.allPrivateCalls().count { it.vaultId == vaultId } }

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
        const val C_STAR = "star"
        const val C_LABELS = "lb"
        const val C_TONE = "rt"
        const val C_VOICEMAIL = "vm"
        const val K_CALL_CHOICES = "call_choices"
        const val K_CHOICES_SEEDED = "caller_choices_seeded"

        /** Marks a caller-ID copy that keeps the star, labels, ringtone and voicemail itself. */
        const val C_SEEDED = "cs"
    }
}
