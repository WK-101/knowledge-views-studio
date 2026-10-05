package app.parley.data.archive

import android.util.Base64
import android.util.Log
import app.parley.common.PhoneIdentity
import app.parley.common.catching
import app.parley.common.backup.RecordJson
import app.parley.common.people.Archive
import app.parley.common.people.ArchivedAccount
import app.parley.common.people.ArchivedCard
import app.parley.common.people.ContactRef
import app.parley.common.record.AccountKinds
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import app.parley.common.record.Messengers
import app.parley.common.record.withoutMessengers
import app.parley.common.storage.PersistentStores
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.backup.BackupExtras
import app.parley.data.backup.RestorePart
import app.parley.data.security.RecordCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Archived contacts: out of Android's address book (so out of every list, search, picker and widget, and out of other
 * apps), kept whole by Parley, and still named when they call. The archive variant of a contact, beside private ones
 * (docs/CONTACT_MODEL.md): like Make private it moves the lossless [ContactRecord] out of the address book and re-keys
 * what Parley keeps about the person; unlike it, nothing is locked, so caller ID, Recall and the Archived view read it
 * at any time.
 *
 * Each contact is two files in `files/archive`, sealed with the small-records key (no unlock needed, so the call path
 * reads them while the phone is locked): `<id>.card`, the [ArchivedCard] lists and caller ID use, and `<id>.rec`, the
 * whole record with its photos (one larger than 512 KB kept as its thumbnail, as for Make private), opened only by
 * Unarchive, backups and exports.
 */
class ArchiveStore(private val c: DataContainer) {
    private val dir = File(c.appContext.filesDir, "archive")
    private val crypto by lazy { RecordCrypto.get(c.appContext) }
    private val mutex = Mutex()

    private val _cards = MutableStateFlow<List<ArchivedCard>>(emptyList())

    /** Every archived contact, by name. Loaded off the main thread when the store is first used. */
    val cards: StateFlow<List<ArchivedCard>> = _cards.asStateFlow()

    @Volatile private var loaded = false

    @Volatile private var index: Pair<List<ArchivedCard>, PhoneIdentity.LineMap<ArchivedCard>>? = null

    init {
        c.scope.launch(Dispatchers.IO) { load() }
    }

    /** Reads the cards once (the call path may ask before the screens do). */
    private suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        _cards.value = readCards()
        loaded = true
    }

    private fun readCards(): List<ArchivedCard> = dir.listFiles().orEmpty().filter { it.name.endsWith(CARD) }.mapNotNull { f ->
        runCatching { Archive.decode(String(crypto.openBytes(f.readBytes()), Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "An archived card couldn't be read now") }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    suspend fun all(): List<ArchivedCard> {
        load()
        return _cards.value
    }

    /** The archived contact one of whose numbers is [number], for naming a call; null when none is. */
    suspend fun lookup(number: String, region: String? = PhoneEnv.countryIso(c.appContext)): ArchivedCard? {
        val list = all()
        if (list.isEmpty() || number.isBlank()) return null
        val cached = index?.takeIf { it.first === list }?.second
        val map = cached ?: Archive.index(list, region).also { index = list to it }
        return map[number]
    }

    /** What [archive] did. [synced]: a copy was synced, so other apps may still see it until the account's next sync. */
    data class Archived(val id: Long, val synced: Boolean, val messengerCopies: Boolean)

    /**
     * Archives device contact [contactId]: its whole record into the archive, then out of the address book, and what
     * Parley keeps about the person (notes, Circle, moments, the call-screen picture) re-keyed to the archived contact.
     * Null, and nothing changed, when the contact couldn't be read whole or the archive couldn't keep it.
     */
    suspend fun archive(contactId: Long, now: Long = System.currentTimeMillis()): Archived? = withContext(Dispatchers.IO) {
        load()
        val record = c.records.readCapped(contactId)?.withoutMessengers() ?: return@withContext null
        val key = record.key.takeIf { it.isNotEmpty() } ?: c.contacts.lookupKeyOf(contactId).orEmpty()
        val id = mutex.withLock {
            val id = nextId()
            val card = cardOf(id, record, key, now)
            // The address book's copy goes only once the archive holds everything it had.
            if (!write(id, card, record)) return@withContext null
            _cards.value = (_cards.value + card).sortedBy { it.name.lowercase() }
            id
        }
        val purged = catching { c.contacts.purgeForVault(contactId) }.getOrElse { e ->
            // The address book refused (a read-only copy, a provider error). While the contact is still there, the
            // archive gives its copy back, so the person is never both archived and in the address book, and a retry
            // doesn't add a second archived copy. Gone after all: the archive holds it whole, so carry on.
            Log.w(TAG, "A contact couldn't be removed from the address book", e)
            if (catching { c.contacts.lookupKeyOf(contactId) }.getOrNull() != null) {
                forgetFiles(id)
                return@withContext null
            }
            ContactsRepository.VaultPurge(synced = true, messengerCopies = false)
        }
        if (key.isNotEmpty()) {
            val archivedKey = ContactRef.archivedKey(id)
            c.contactKeys.rekey(key, archivedKey, null)
            // An archived contact doesn't delete itself: it isn't in the address book for the expiry to remove.
            c.meta.clearTemporary(archivedKey)
        }
        c.contacts.refresh()
        Archived(id, purged.synced, purged.messengerCopies)
    }

    /** Where Unarchive would put archived contact [id] (null when it isn't archived). */
    suspend fun target(id: Long): Archive.Target? {
        val card = all().firstOrNull { it.id == id } ?: return null
        return withContext(Dispatchers.IO) {
            val writable = (c.contacts.accounts() + c.records.availableAccounts().filter { c.records.isWritableAccount(it) })
                .map { ArchivedAccount(it.type, it.name) }.toSet()
            Archive.target(card.accounts, writable) { AccountKinds.isLocalType(it) || Messengers.isMessengerAccount(it) }
        }
    }

    /** What [unarchive] did. */
    sealed interface Unarchived {
        /** Back in the address book as [contactId]; [redirectedTo]: Android 16 put it in this account instead. */
        data class Done(val contactId: Long, val redirectedTo: AccountRef? = null) : Unarchived

        /** Nothing could be written: it stays archived. */
        data object NotWritten : Unarchived
    }

    /**
     * Puts archived contact [id] back into the address book: into the accounts it came from ([into] null), or all of it
     * into [into] (the user's pick when those accounts aren't here). What Parley kept follows it to its new key.
     */
    suspend fun unarchive(id: Long, into: AccountRef? = null): Unarchived = withContext(Dispatchers.IO) {
        val card = all().firstOrNull { it.id == id } ?: return@withContext Unarchived.NotWritten
        val record = readRecord(id) ?: return@withContext Unarchived.NotWritten
        val result = c.records.insertAll(listOf(record), target = into, announceRedirect = false).single()
        val newId = result.contactId
        if (newId == null) {
            if (result.rawIds.isNotEmpty()) catching { c.contacts.discardInserted(result.rawIds) }
            return@withContext Unarchived.NotWritten
        }
        forgetFiles(id)
        val from = card.parleyKey
        val key = keyOf(newId)
        if (key != null) c.contactKeys.rekey(from, key, newId)
        else c.contactKeys.rekeyLater(from, result.rawIds.ifEmpty { c.contacts.rawIds(newId) }, newId)
        c.contacts.refresh()
        Unarchived.Done(newId, result.redirectedTo)
    }

    /** Removes archived contact [id]'s files and card. */
    private suspend fun forgetFiles(id: Long) = mutex.withLock {
        File(dir, "$id$CARD").delete()
        File(dir, "$id$REC").delete()
        _cards.value = _cards.value.filterNot { it.id == id }
    }

    /** The whole record of archived contact [id] (photos included), for Unarchive, backups and exports. */
    suspend fun readRecord(id: Long): ContactRecord? = withContext(Dispatchers.IO) {
        val f = File(dir, "$id$REC")
        if (!f.exists()) return@withContext null
        catching { decodeRecord(String(crypto.openBytes(f.readBytes()), Charsets.UTF_8)) }.getOrNull()
    }

    /** Every archived contact's record, as exports carry it: keyed by its archived Parley key, so the export can flag it. */
    suspend fun recordsForExport(): List<ContactRecord> = all().mapNotNull { card -> readRecord(card.id)?.copy(key = card.parleyKey) }

    private fun nextId(): Long = maxOf(_cards.value.maxOfOrNull { it.id } ?: 0L, existingIds().maxOrNull() ?: 0L) + 1

    private fun existingIds(): List<Long> = dir.listFiles().orEmpty().mapNotNull { it.name.substringBefore('.').toLongOrNull() }

    private fun cardOf(id: Long, r: ContactRecord, key: String, now: Long): ArchivedCard {
        val rows = r.raws.flatMap { it.rows }
        return ArchivedCard(
            id = id,
            name = r.displayName,
            numbers = rows.filter { it.mimeType == Mime.PHONE }.mapNotNull { it[Col.D1]?.takeIf { n -> n.isNotBlank() } }.distinct(),
            archivedAt = now,
            accounts = r.raws.map { ArchivedAccount(it.accountType, it.accountName) }.distinct(),
            originalKey = key,
            company = rows.firstOrNull { it.mimeType == Mime.ORG }?.get(Col.D1).orEmpty(),
        )
    }

    /** Writes both files of [id] (the record first, so a card never points at nothing). False when either failed. */
    private fun write(id: Long, card: ArchivedCard, record: ContactRecord): Boolean = runCatching {
        dir.mkdirs()
        writeSealed(File(dir, "$id$REC"), encodeRecord(record))
        writeSealed(File(dir, "$id$CARD"), Archive.encode(card))
        true
    }.getOrElse {
        Log.w(TAG, "A contact couldn't be archived", it)
        File(dir, "$id$REC").delete()
        File(dir, "$id$CARD").delete()
        false
    }

    private fun writeSealed(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(crypto.sealBytes(text.toByteArray(Charsets.UTF_8)))
        check(tmp.renameTo(f)) { "Couldn't write ${f.name}" }
    }

    private suspend fun keyOf(contactId: Long): String? {
        repeat(KEY_TRIES) { attempt ->
            c.contacts.lookupKeyOf(contactId)?.takeIf { it.isNotEmpty() }?.let { return it }
            if (attempt < KEY_TRIES - 1) delay(KEY_RETRY_MS)
        }
        return null
    }

    // --- Backup: every archived contact travels inside the encrypted backup, restored with the contacts ---

    val backupExtras: BackupExtras = object : BackupExtras {
        override val section = "archived contacts"
        override val sections = setOf(PersistentStores.Sections.ARCHIVE)
        override val restoreWith = RestorePart.CONTACTS

        override suspend fun export(): Map<String, String> {
            val out = JSONArray()
            all().forEach { card ->
                val record = readRecord(card.id) ?: return@forEach
                out.put(JSONObject().put(J_CARD, Archive.encode(card)).put(J_RECORD, encodeRecord(record)))
            }
            return if (out.length() == 0) emptyMap() else mapOf(X_ARCHIVE to out.toString())
        }

        override suspend fun import(values: Map<String, String>) {
            importCounting(values)
        }

        /**
         * Adds the backup's archived contacts this phone doesn't have yet (same lookup key, or same name and numbers).
         * It runs before the other sections, which then find each archived person's new key through [restoredKeys].
         */
        override suspend fun importCounting(values: Map<String, String>): Int {
            val a = values[X_ARCHIVE]?.let { catching { JSONArray(it) }.getOrNull() } ?: return 0
            val entries = (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val card = Archive.decode(o.optString(J_CARD)) ?: return@mapNotNull null
                catching { decodeRecord(o.optString(J_RECORD)) }.getOrNull()?.let { card to it }
            }
            return entries.count { (card, record) -> !restore(card, record) }
        }
    }

    /**
     * For a backup's other sections: the archived keys in [values] (a backup's `x.` values) → the same people's keys
     * here, once this part has restored them ([Archive.restoredKeys]). Empty when the backup has no archived contacts.
     */
    suspend fun restoredKeys(values: Map<String, String>): Map<String, String> {
        val a = values[X_ARCHIVE]?.let { catching { JSONArray(it) }.getOrNull() } ?: return emptyMap()
        val cards = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.optString(J_CARD)?.let(Archive::decode) }
        return Archive.restoredKeys(cards, all())
    }

    /** Adds [card] with [record] unless an archived contact here is the same one. False when it couldn't be kept. */
    internal suspend fun restore(card: ArchivedCard, record: ContactRecord): Boolean = withContext(Dispatchers.IO) {
        load()
        mutex.withLock {
            val here = _cards.value.any { Archive.sameOne(it, card) }
            if (here) return@withLock true
            val id = nextId()
            val kept = card.copy(id = id)
            if (!write(id, kept, record)) return@withLock false
            _cards.value = (_cards.value + kept).sortedBy { it.name.lowercase() }
            true
        }
    }

    companion object {
        private const val TAG = "ArchiveStore"
        private const val CARD = ".card"
        private const val REC = ".rec"
        private const val KEY_TRIES = 5
        private const val KEY_RETRY_MS = 200L
        private const val X_ARCHIVE = "${BackupExtras.PREFIX}archive.contacts"
        private const val J_CARD = "card"
        private const val J_RECORD = "record"
        private const val J_LINE = "line"
        private const val J_BLOBS = "blobs"

        /** A record with its photos inline (Base64), as one JSON text: what the archive and its backup keep. */
        fun encodeRecord(r: ContactRecord): String {
            val blobs = JSONObject()
            val line = RecordJson.encode(r) { hash, bytes -> blobs.put(hash, Base64.encodeToString(bytes, Base64.NO_WRAP)) }
            return JSONObject().put(J_LINE, line).put(J_BLOBS, blobs).toString()
        }

        fun decodeRecord(text: String): ContactRecord {
            val o = JSONObject(text)
            val blobs = o.optJSONObject(J_BLOBS)
            return RecordJson.decode(o.getString(J_LINE)) { h -> blobs?.optString(h)?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) } }
        }
    }
}
