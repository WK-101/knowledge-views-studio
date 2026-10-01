package app.parley.data.vault

import android.content.Context
import android.util.Base64
import app.parley.common.people.ContactRef
import app.parley.data.people.ContactKeys
import app.parley.data.people.OriginalPhotos
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Recently deleted" for private contacts: History & undo keeps a plain copy of a deleted device contact, which a
 * private contact must never have, so a deleted private contact is kept here instead, for [KEEP_DAYS] days, exactly
 * as the vault stored it: its details still under the detail key, its name, numbers, labels, photo and private calls
 * under the caller-ID key, and what Parley kept about it (Circle, moments, relation links, call-screen picture, the
 * photo as picked, the relation links other contacts had to it) in the same sealed file. Nothing is opened to keep
 * it, so deleting works while the vault is locked; the list is shown only after the vault's own unlock, and restoring
 * puts the entry back as it was.
 *
 * Files live in no-backup storage (`vault_trash`); "Delete all Parley data" removes them, and a backup never holds them.
 */
class PrivateTrash(private val context: Context, private val vault: VaultRepository, private val keys: () -> ContactKeys?) {
    /** One kept contact, for the list (only after the vault is unlocked). */
    data class Kept(val file: String, val name: String, val numbers: List<String>, val deletedAt: Long)

    private val lock = Mutex()

    private fun dir() = File(context.noBackupFilesDir, "vault_trash")

    /** Detail-key generations of the kept copies: the vault keeps those keys until the copies go. */
    fun generations(): Set<Int> = dir().listFiles().orEmpty().mapNotNull { f -> f.name.substringAfterLast("-g", "").substringBefore('.').toIntOrNull() }.toSet()

    /**
     * Keeps a sealed copy of private contact [vaultId] before it is deleted. False when it couldn't be kept (the
     * delete then waits: the user is asked whether to delete without one).
     */
    suspend fun keep(vaultId: Long, now: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        val e = vault.sealedCopy(vaultId) ?: return@withContext false
        val extras = runCatching { keys()?.exportPrivate(ContactRef.privateKey(vaultId)) }.getOrNull()
        // The photo as picked (still sealed) and the relations other contacts link to it: deleting the entry drops both.
        val original = OriginalPhotos.sealedPrivate(context, vaultId)
        val incoming = runCatching { keys()?.incomingLinks(ContactRef.privateKey(vaultId)) }.getOrNull().orEmpty()
        val o = JSONObject()
            .put(K_AT, now)
            .put(K_CALLER, b64(e.callerIdBlob))
            .put(K_DETAIL, b64(e.detailBlob))
            .put(K_CREATED, e.createdAt)
            .apply {
                e.expiresAt?.let { put(K_EXPIRES, it) }
                e.photo?.let { put(K_PHOTO, b64(it)) }
                if (e.calls.isNotEmpty()) {
                    put(K_CALLS, JSONArray(e.calls.map { c -> JSONObject().put("b", b64(c.blob)).put("d", c.date).put("s", c.durationSec).put("t", c.type) }))
                }
                extras?.let { put(K_EXTRAS, it) }
                original?.let { (image, meta) -> put(K_ORIGINAL, b64(image)).put(K_ORIGINAL_META, meta) }
                if (incoming.isNotEmpty()) put(K_INCOMING, JSONArray(incoming.map { (owner, name) -> JSONObject().put("k", owner).put("n", name) }))
            }
        lock.withLock {
            runCatching {
                dir().mkdirs()
                val name = "$now-$vaultId-g${VaultCrypto.generationOf(e.detailBlob)}.bin"
                val tmp = File(dir(), "$name.tmp")
                tmp.writeBytes(VaultCrypto.sealCallerId(o.toString().toByteArray()))
                tmp.renameTo(File(dir(), name))
            }.getOrDefault(false)
        }
    }

    /** The kept contacts, newest first; older than [KEEP_DAYS] days go first. Show only after the vault's unlock. */
    suspend fun list(now: Long = System.currentTimeMillis()): List<Kept> = withContext(Dispatchers.IO) {
        purge(now)
        files().mapNotNull { f ->
            val o = open(f) ?: return@mapNotNull null
            val caller = runCatching { JSONObject(String(VaultCrypto.openCallerId(Base64.decode(o.getString(K_CALLER), Base64.NO_WRAP)))) }.getOrNull()
                ?: return@mapNotNull null
            val nums = caller.optJSONArray("numbers") ?: JSONArray()
            Kept(f.name, caller.optString("name"), (0 until nums.length()).map { nums.getString(it) }, o.optLong(K_AT))
        }.sortedByDescending { it.deletedAt }
    }

    /** Number memory: changes whenever a copy is kept, restored or removed (nothing is opened). */
    fun memoryStamp(): String = files().map { it.name }.sorted().joinToString(",")

    /** Puts [file]'s contact back; returns its new vault id, or null when it can't be read. */
    suspend fun restore(file: String): Long? = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = File(dir(), file).takeIf { it.isFile && it.parentFile == dir() } ?: return@withLock null
            val o = open(f) ?: return@withLock null
            val calls = o.optJSONArray(K_CALLS)?.let { a ->
                (0 until a.length()).map { i ->
                    a.getJSONObject(i).let { SealedCall(unb64(it.getString("b")), it.optLong("d"), it.optLong("s"), it.optInt("t")) }
                }
            }.orEmpty()
            val entry = SealedEntry(
                unb64(o.getString(K_CALLER)), unb64(o.getString(K_DETAIL)), o.optLong(K_EXPIRES).takeIf { o.has(K_EXPIRES) },
                o.optLong(K_CREATED, System.currentTimeMillis()), o.optString(K_PHOTO).ifEmpty { null }?.let(::unb64), calls,
            )
            val id = vault.restoreSealed(entry)
            o.optJSONObject(K_EXTRAS)?.let { x -> runCatching { keys()?.importPrivate(id, x) } }
            o.optString(K_ORIGINAL).ifEmpty { null }?.let { image ->
                OriginalPhotos.restoreSealedPrivate(context, id, unb64(image), o.optString(K_ORIGINAL_META))
            }
            o.optJSONArray(K_INCOMING)?.let { a ->
                val links = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("k") to it.optString("n") } }
                    .filter { (k, n) -> k.isNotEmpty() && n.isNotEmpty() }
                runCatching { keys()?.restoreIncomingLinks(id, links) }
            }
            f.delete()
            id
        }
    }

    /** Removes [file] for good. */
    suspend fun remove(file: String) = withContext(Dispatchers.IO) {
        lock.withLock { File(dir(), file).takeIf { it.parentFile == dir() }?.delete() }
    }

    /** Everything kept, for good ("Clear history & undo"). Returns how many went. */
    suspend fun clear(): Int = withContext(Dispatchers.IO) { lock.withLock { files().count { it.delete() } } }

    /** How many are kept (no unlock needed: nothing is opened). */
    fun count(): Int = files().size

    /** Drops copies older than [KEEP_DAYS] days (by the time in their name, so nothing is opened). */
    suspend fun purge(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        lock.withLock {
            files().filter { f -> (f.name.substringBefore('-').toLongOrNull() ?: 0L) < now - KEEP_DAYS * DAY_MS }.forEach { it.delete() }
        }
    }

    private fun files(): List<File> = dir().listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") }

    private fun open(f: File): JSONObject? = runCatching { JSONObject(String(VaultCrypto.openCallerId(f.readBytes()))) }.getOrNull()

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val KEEP_DAYS = 30L
        private const val DAY_MS = 86_400_000L
        private const val K_AT = "at"
        private const val K_CALLER = "c"
        private const val K_DETAIL = "d"
        private const val K_CREATED = "cr"
        private const val K_EXPIRES = "x"
        private const val K_PHOTO = "p"
        private const val K_CALLS = "calls"
        private const val K_EXTRAS = "parley"
        private const val K_ORIGINAL = "o"
        private const val K_ORIGINAL_META = "om"
        private const val K_INCOMING = "in"
    }
}
