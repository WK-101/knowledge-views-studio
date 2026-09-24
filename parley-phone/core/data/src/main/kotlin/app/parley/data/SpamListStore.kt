package app.parley.data

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import app.parley.common.BlockRule
import app.parley.common.ListHit
import app.parley.common.ListMode
import app.parley.common.RuleTools
import app.parley.common.PhoneNumbers
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.spam.BuiltInPacks
import app.parley.common.spam.Ed25519
import app.parley.common.spam.ListPack
import app.parley.common.spam.ListsState
import app.parley.common.spam.PackBuilder
import app.parley.common.spam.PackException
import app.parley.common.spam.PackIndex
import app.parley.common.spam.PackManifest
import app.parley.common.spam.PackOrigin
import app.parley.common.spam.PackState
import app.parley.common.spam.ParsedPack
import app.parley.common.spam.SignatureStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Result of looking a caller up in every enabled pack. */
data class ListLookup(val hits: List<ListHit>, val failed: Boolean)

/** "2 lists · 184,302 numbers · updated 3 days ago". */
data class ListsSummary(val lists: Int, val numbers: Long, val updatedAt: Long?, val stale: Int)

/**
 * Installed spam-list packs (B4/B5/B7). Files live in device-protected storage so the data is readable
 * before the first unlock; numbers are memory-mapped and binary-searched on each incoming call.
 */
class SpamListStore(context: Context) {
    private val app = context.applicationContext
    private val dir: File = File(app.createDeviceProtectedStorageContext().filesDir, "lists").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val lock = Mutex()
    private val indexes = HashMap<String, PackIndex>()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<ListsState> = _state.asStateFlow()

    val summary: ListsSummary get() = summarize(_state.value)

    fun summarize(s: ListsState, now: Long = System.currentTimeMillis()): ListsSummary {
        val on = s.packs.filter { it.enabled }
        return ListsSummary(on.size, on.sumOf { it.entries.toLong() + it.ranges }, on.maxOfOrNull { it.installedAt }, on.count { it.isStale(now) })
    }

    fun hasEnabledPacks(): Boolean = _state.value.packs.any { it.enabled }

    private fun readState(): ListsState = runCatching { ListsState.decode(stateFile.takeIf { it.exists() }?.readText()) }.getOrDefault(ListsState())

    private fun writeState(s: ListsState) {
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(s.encode())
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(s.encode())
            tmp.delete()
        }
        _state.value = s
    }

    private suspend fun update(f: (ListsState) -> ListsState) = lock.withLock { withContext(Dispatchers.IO) { writeState(f(_state.value)) } }

    // ---------- Lookup (call path) ----------

    /** Looks [number] up in every enabled pack. Never throws: a broken pack is reported as [ListLookup.failed]. */
    fun lookup(number: String, countryIso: String): ListLookup {
        val packs = _state.value.packs.filter { it.enabled }
        if (packs.isEmpty()) return ListLookup(emptyList(), false)
        var failed = false
        val now = System.currentTimeMillis()
        val forms = PackIndex.forms(number, countryIso)
        val hits = packs.mapNotNull { p ->
            try {
                if (p.suppressed.isNotEmpty() && forms.any { it in p.suppressed }) return@mapNotNull null
                val m = index(p.id)?.lookup(number, countryIso, p.useRanges) ?: return@mapNotNull null
                ListHit(p.id, p.name, p.categoryName(m.category), m.score, p.mode, p.threshold, p.action, p.notify, m.range, p.isStale(now))
            } catch (_: Exception) {
                failed = true
                null
            }
        }
        return ListLookup(hits, failed)
    }

    /** Dry-run variant: looks up in one parsed pack that isn't installed (or installed but disabled). */
    fun lookupIn(parsed: ParsedPack, number: String, countryIso: String): ListHit? {
        val m = PackIndex.of(parsed).lookup(number, countryIso, true) ?: return null
        return ListHit(parsed.manifest.id, parsed.manifest.name, parsed.manifest.categories[m.category.toString()], m.score, ListMode.BLOCK, 0, range = m.range)
    }

    @Synchronized
    private fun index(id: String): PackIndex? {
        indexes[id]?.let { return it }
        val pd = File(dir, id)
        val numbers = File(pd, ListPack.NUMBERS)
        val ranges = File(pd, ListPack.RANGES)
        if (!numbers.exists() && !ranges.exists()) return null
        val buffer = if (numbers.exists() && numbers.length() > 0) {
            RandomAccessFile(numbers, "r").use { f -> f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()).order(ByteOrder.BIG_ENDIAN) }
        } else {
            java.nio.ByteBuffer.allocate(0)
        }
        val idx = PackIndex(buffer, if (ranges.exists()) ListPack.parseRanges(ranges.readText()) else emptyList())
        indexes[id] = idx
        return idx
    }

    @Synchronized
    private fun dropIndex(id: String) {
        indexes.remove(id)
    }

    // ---------- Installing ----------

    sealed interface InstallResult {
        data class Installed(val pack: PackState, val replaced: Boolean) : InstallResult
        data class Older(val installed: Long, val offered: Long) : InstallResult
        data class Failed(val reason: String) : InstallResult
    }

    /** Parses without installing (for the "before you add it" dry run and details). */
    suspend fun parse(uri: Uri): ParsedPack = withContext(Dispatchers.IO) { ListPack.parse(readBytes(uri)) }

    private fun readBytes(uri: Uri): ByteArray {
        val input = app.contentResolver.openInputStream(uri) ?: throw PackException("Couldn't open the file")
        return input.use { s ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_FILE) throw PackException("The file is too large")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    suspend fun install(uri: Uri, origin: PackOrigin = PackOrigin.FILE): InstallResult = try {
        install(parse(uri), origin)
    } catch (e: PackException) {
        InstallResult.Failed(e.message ?: "Not a valid list")
    } catch (e: Exception) {
        InstallResult.Failed("Couldn't read the file")
    }

    suspend fun install(p: ParsedPack, origin: PackOrigin, force: Boolean = false): InstallResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val m = p.manifest
            val existing = _state.value.packs.firstOrNull { it.id == m.id }
            if (existing != null && existing.version > m.version && !force) return@withContext InstallResult.Older(existing.version, m.version)
            // A signed pack can only be replaced by the same publisher key.
            if (existing?.fingerprint != null && p.fingerprint != existing.fingerprint && !force) {
                return@withContext InstallResult.Failed("This update is signed by a different key (${p.fingerprint ?: "unsigned"}) than the installed list (${existing.fingerprint})")
            }
            val target = File(dir, m.id)
            val tmp = File(dir, m.id + ".tmp").apply { deleteRecursively(); mkdirs() }
            File(tmp, ListPack.NUMBERS).writeBytes(p.numbers)
            File(tmp, ListPack.RANGES).writeText(p.rangesText)
            File(tmp, ListPack.MANIFEST).writeBytes(p.manifestBytes)
            dropIndex(m.id)
            target.deleteRecursively()
            if (!tmp.renameTo(target)) {
                tmp.copyRecursively(target, overwrite = true)
                tmp.deleteRecursively()
            }
            val state = PackState(
                id = m.id, name = m.name, publisher = m.publisher, source = m.source, licence = m.licence, version = m.version,
                created = m.created, ttlDays = m.ttlDays, regions = m.regions, categories = m.categories, entries = p.numbers.size / ListPack.RECORD,
                ranges = p.ranges.size, signed = p.signature == SignatureStatus.SIGNED, fingerprint = p.fingerprint,
                installedAt = System.currentTimeMillis(), origin = origin,
            ).let { fresh ->
                // Keep the user's choices across updates.
                existing?.let { fresh.copy(enabled = it.enabled, mode = it.mode, threshold = it.threshold, action = it.action, useRanges = it.useRanges, notify = it.notify, suppressed = it.suppressed) } ?: fresh
            }
            val s = _state.value
            writeState(s.copy(packs = s.packs.filter { it.id != m.id } + state))
            InstallResult.Installed(state, existing != null)
        }
    }

    suspend fun installBuiltIn(b: BuiltInPacks.BuiltIn): InstallResult = install(ListPack.parse(BuiltInPacks.toPack(b)), PackOrigin.BUILTIN, force = true)

    suspend fun setPack(id: String, f: (PackState) -> PackState) = update { s -> s.copy(packs = s.packs.map { if (it.id == id) f(it) else it }) }

    suspend fun remove(id: String) {
        update { s -> s.copy(packs = s.packs.filter { it.id != id }) }
        dropIndex(id)
        withContext(Dispatchers.IO) { File(dir, id).deleteRecursively() }
    }

    suspend fun dismissSuggestion(id: String) = update { it.copy(dismissedSuggestions = (it.dismissedSuggestions + id).distinct()) }

    /** "Not spam": this number is never reported by [packId] again. */
    suspend fun suppress(packId: String?, number: String, countryIso: String) {
        val forms = PackIndex.forms(number, countryIso)
        update { s -> s.copy(packs = s.packs.map { p -> if (packId == null || p.id == packId) p.copy(suppressed = (p.suppressed + forms).distinct()) else p }) }
    }

    // ---------- Folder subscription ----------

    suspend fun subscribe(tree: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        update { it.copy(folderUri = tree.toString(), folderSeen = emptyMap(), folderError = null) }
        refreshFolder()
    }

    suspend fun unsubscribe() {
        _state.value.folderUri?.let { u ->
            try {
                app.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        update { it.copy(folderUri = null, folderSeen = emptyMap(), folderError = null) }
    }

    /** Imports new or changed `.parleylist` files from the subscribed folder. Returns how many were installed. */
    suspend fun refreshFolder(): Int = withContext(Dispatchers.IO) {
        val tree = _state.value.folderUri?.let { Uri.parse(it) } ?: return@withContext 0
        var installed = 0
        var error: String? = null
        val seen = HashMap(_state.value.folderSeen)
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val files = ArrayList<Triple<String, String, Long>>()
            app.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    if (name.endsWith("." + ListPack.EXTENSION, ignoreCase = true)) files += Triple(c.getString(0), name, c.getLong(2))
                }
            } ?: throw SecurityException("no access")
            for ((docId, name, modified) in files) {
                if (seen[name] == modified) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                when (val r = install(uri, PackOrigin.FOLDER)) {
                    is InstallResult.Installed -> installed++
                    is InstallResult.Failed -> error = "$name: ${r.reason}"
                    is InstallResult.Older -> Unit
                }
                seen[name] = modified
            }
        } catch (_: SecurityException) {
            error = "Parley can no longer read the folder. Choose it again."
        } catch (_: Exception) {
            error = "The folder couldn't be read"
        }
        update { it.copy(folderSeen = seen, folderCheckedAt = System.currentTimeMillis(), folderError = error) }
        installed
    }

    private var observer: ContentObserver? = null

    /** Re-reads the folder when its provider reports a change (while Parley runs). */
    fun watchFolder(onChange: () -> Unit) {
        val tree = _state.value.folderUri?.let { Uri.parse(it) } ?: return
        observer?.let { app.contentResolver.unregisterContentObserver(it) }
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            app.contentResolver.registerContentObserver(children, true, o)
            observer = o
        } catch (_: Exception) {
        }
    }

    // ---------- Sharing your rules (B7) ----------

    private val keyFile = File(app.filesDir, "blocking/share.key")

    /** Your personal signing key, created on first use. Its fingerprint lets family check that a pack is yours. */
    private fun shareKey(): ByteArray {
        if (keyFile.exists() && keyFile.length() == 32L) return keyFile.readBytes()
        keyFile.parentFile?.mkdirs()
        val k = Ed25519.newSecret()
        keyFile.writeBytes(k)
        return k
    }

    fun shareFingerprint(): String = Ed25519.fingerprint(Ed25519.publicKey(shareKey()))

    data class Export(val bytes: ByteArray, val numbers: Int, val ranges: Int, val skipped: Int)

    /** Exact rules become numbers, international prefixes become ranges; other rule types can't travel in a pack. */
    suspend fun exportRules(rules: List<BlockRule>, name: String, countryIso: String): Export = withContext(Dispatchers.Default) {
        val id = "user." + shareFingerprint().replace(" ", "").lowercase()
        val b = PackBuilder(PackManifest(id = id, name = name, publisher = "Shared from Parley", version = System.currentTimeMillis() / 1000, ttlDays = 0, categories = mapOf("1" to "Blocked by a friend")))
        var numbers = 0
        var ranges = 0
        var skipped = 0
        rules.filter { it.kind == RuleKind.BLOCK && it.enabled && it.expiresAt == null }.forEach { r ->
            val ok = when (r.type) {
                RuleType.EXACT -> b.addNumber(r.pattern, 1, 90, countryIso).also { if (it) numbers++ }
                RuleType.PREFIX -> {
                    val p = RuleTools.canonicalPrefix(r.pattern, countryIso)
                    val intl = if (p.startsWith("+")) p else PhoneNumbers.toE164(p + "0000000", countryIso)?.dropLast(7)
                    (intl != null && b.addRange(intl, 1, 90)).also { if (it) ranges++ }
                }
                else -> false
            }
            if (!ok) skipped++
        }
        Export(b.build(shareKey()), numbers, ranges, skipped)
    }

    companion object {
        private const val MAX_FILE = 100L * 1024 * 1024
    }
}
