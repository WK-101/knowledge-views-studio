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
import app.parley.common.PhoneIdentity
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.security.Bounded
import app.parley.common.security.LimitExceededException
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
import app.parley.common.storage.DurableFiles
import app.parley.common.templates.RuleTemplate
import app.parley.common.templates.RuleTemplates
import app.parley.data.security.RecordCrypto
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
 * Installed spam-list packs. Files live in device-protected storage so the data is readable
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
        // Throws when it can't be stored, so the lists in memory never differ from what the next start reads.
        DurableFiles.writeOrThrow(stateFile, s.encode().toByteArray(Charsets.UTF_8))
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

    /**
     * Where a pack's files live: `pack_<sha256 of id>`, so no id ("..", "/") can point outside [dir]. Packs
     * installed by older versions used the id itself; that folder is still read until the pack is updated.
     */
    private fun packDir(id: String): File {
        val hashed = File(dir, ListPack.storageName(id))
        if (hashed.exists()) return hashed
        return legacyDir(id) ?: hashed
    }

    private fun legacyDir(id: String): File? =
        if (ListPack.isValidId(id) && !id.startsWith("pack_")) File(dir, id).takeIf { it.isDirectory } else null

    @Synchronized
    private fun index(id: String): PackIndex? {
        indexes[id]?.let { return it }
        val pd = packDir(id)
        val numbers = File(pd, ListPack.NUMBERS)
        val ranges = File(pd, ListPack.RANGES)
        if (!numbers.exists() && !ranges.exists()) return null
        val buffer = if (numbers.exists() && numbers.length() > 0) {
            RandomAccessFile(numbers, "r").use { f -> f.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()).order(ByteOrder.BIG_ENDIAN) }
        } else {
            ByteBuffer.allocate(0)
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
        return try {
            input.use { Bounded.readBytes(it, Bounded.Caps.PACK_FILE, "list") }
        } catch (_: LimitExceededException) {
            throw PackException("The file is too large")
        }
    }

    suspend fun install(uri: Uri, origin: PackOrigin = PackOrigin.FILE): InstallResult = try {
        install(parse(uri), origin)
    } catch (e: PackException) {
        InstallResult.Failed(e.message ?: "Not a valid list")
    } catch (e: Exception) {
        InstallResult.Failed("Couldn't read the file")
    }

    suspend fun install(p: ParsedPack, origin: PackOrigin, force: Boolean = false): InstallResult = try {
        lock.withLock { withContext(Dispatchers.IO) { installLocked(p, origin, force) } }
    } catch (e: PackException) {
        InstallResult.Failed(e.message ?: "Not a valid list")
    } catch (e: Exception) {
        InstallResult.Failed("Couldn't install the list")
    }

    /** The full publisher key of an installed signed pack (read from its stored manifest for packs installed before it was kept). */
    private fun installedKey(existing: PackState): String? = existing.publicKey
        ?: runCatching { ListPack.readManifest(File(packDir(existing.id), ListPack.MANIFEST).readBytes())?.publicKey }.getOrNull()

    private fun installLocked(p: ParsedPack, origin: PackOrigin, force: Boolean): InstallResult {
        val m = p.manifest
        // Parsed packs are checked already; this also covers packs built in memory. "." or ".." would name the lists folder itself.
        if (!ListPack.isValidId(m.id)) throw PackException("The list has an invalid id")
        val existing = _state.value.packs.firstOrNull { it.id == m.id }
        if (existing != null && existing.version > m.version && !force) return InstallResult.Older(existing.version, m.version)
        // Keys are pinned: a signed pack is replaced only under the same full key, built-ins only by built-ins, and the
        // companion's packs only under the companion key pinned at its first pack.
        if (!force || origin != PackOrigin.BUILTIN) {
            val installed = existing?.let { installedKey(it) }
            ListPack.refusal(existing, installed, p, origin, _state.value.updaterKey)?.let { return InstallResult.Failed(it) }
        }
        val name = ListPack.storageName(m.id)
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp").apply { deleteRecursively(); mkdirs() }
        DurableFiles.writeOrThrow(File(tmp, ListPack.NUMBERS), p.numbers)
        DurableFiles.writeOrThrow(File(tmp, ListPack.RANGES), p.rangesText.toByteArray())
        DurableFiles.writeOrThrow(File(tmp, ListPack.MANIFEST), p.manifestBytes)
        dropIndex(m.id)
        target.deleteRecursively()
        if (!DurableFiles.move(tmp, target)) {
            tmp.copyRecursively(target, overwrite = true)
            tmp.deleteRecursively()
        }
        // The folder an older version used for this pack.
        legacyDir(m.id)?.deleteRecursively()
        val state = PackState(
            id = m.id, name = m.name, publisher = m.publisher, source = m.source, licence = m.licence, version = m.version,
            created = m.created, ttlDays = m.ttlDays, regions = m.regions, categories = m.categories, entries = p.numbers.size / ListPack.RECORD,
            ranges = p.ranges.size, signed = p.signature == SignatureStatus.SIGNED, fingerprint = p.fingerprint,
            publicKey = if (p.signature == SignatureStatus.SIGNED) m.publicKey else null,
            installedAt = System.currentTimeMillis(), origin = origin,
        ).let { fresh ->
            // Keep the user's choices across updates.
            existing?.let { fresh.copy(enabled = it.enabled, mode = it.mode, threshold = it.threshold, action = it.action, useRanges = it.useRanges, notify = it.notify, suppressed = it.suppressed) } ?: fresh
        }
        val s = _state.value
        val pinned = if (origin == PackOrigin.UPDATER && s.updaterKey == null) m.publicKey else s.updaterKey
        writeState(s.copy(packs = s.packs.filter { it.id != m.id } + state, updaterKey = pinned))
        return InstallResult.Installed(state, existing != null)
    }

    suspend fun installBuiltIn(b: BuiltInPacks.BuiltIn): InstallResult = install(ListPack.parse(BuiltInPacks.toPack(b)), PackOrigin.BUILTIN, force = true)

    suspend fun setPack(id: String, f: (PackState) -> PackState) = update { s -> s.copy(packs = s.packs.map { if (it.id == id) f(it) else it }) }

    suspend fun remove(id: String) {
        // With no companion list left, its key is no longer pinned (a reinstalled companion has a new key).
        update { s ->
            val rest = s.packs.filter { it.id != id }
            s.copy(packs = rest, updaterKey = s.updaterKey.takeIf { rest.any { it.origin == PackOrigin.UPDATER } })
        }
        dropIndex(id)
        withContext(Dispatchers.IO) {
            File(dir, ListPack.storageName(id)).deleteRecursively()
            legacyDir(id)?.deleteRecursively()
        }
    }

    // ---------- Backup ----------

    /**
     * Packs the user added from a file (a folder, the companion app and the built-ins can provide theirs again), for
     * the backup: each pack's state (JSON) and its files as a pack, while they fit in [maxBytes] together. A stored
     * pack keeps no signature file, so it comes back as an unsigned pack with the same entries.
     */
    suspend fun userPacks(maxBytes: Int): List<Pair<String, ByteArray>> = withContext(Dispatchers.IO) {
        var total = 0
        _state.value.packs.filter { it.origin == PackOrigin.FILE }.mapNotNull { p ->
            val pd = packDir(p.id)
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { z ->
                for (name in listOf(ListPack.MANIFEST, ListPack.NUMBERS, ListPack.RANGES)) {
                    val f = File(pd, name).takeIf { it.isFile } ?: continue
                    z.putNextEntry(ZipEntry(name).apply { time = 0 })
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            val zip = out.toByteArray()
            if (total + zip.size > maxBytes) return@mapNotNull null
            total += zip.size
            ListsState(packs = listOf(p)).encode() to zip
        }
    }

    /** Reinstalls a pack from [userPacks] with the user's choices, unless this phone has it already. */
    suspend fun restoreUserPack(state: String, zip: ByteArray): Boolean {
        val saved = ListsState.decode(state).packs.firstOrNull() ?: return false
        if (_state.value.packs.any { it.id == saved.id }) return false
        val parsed = runCatching { ListPack.parse(zip) }.getOrNull() ?: return false
        if (install(parsed, PackOrigin.FILE) !is InstallResult.Installed) return false
        setPack(saved.id) {
            it.copy(
                enabled = saved.enabled,
                mode = saved.mode,
                threshold = saved.threshold,
                action = saved.action,
                useRanges = saved.useRanges,
                notify = saved.notify,
                suppressed = saved.suppressed,
            )
        }
        return true
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
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
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

    // ---------- Sharing your rules ----------

    private val keyFile = File(app.filesDir, "blocking/share.key")

    @Volatile private var unsavedShareKey: ByteArray? = null

    /**
     * Your personal signing key, created on first use. Its fingerprint lets family check that a pack is yours. Sealed
     * with the small-records key, never stored plain: a key from an older version (32 plain bytes) is sealed at its next
     * use, and while the Keystore can't seal, a new key stays in memory and is stored at a later use.
     */
    @Synchronized
    private fun shareKey(): ByteArray {
        val crypto = RecordCrypto.get(app)
        unsavedShareKey?.let { k ->
            if (storeShareKey(crypto, k)) unsavedShareKey = null
            return k
        }
        if (keyFile.isFile) {
            val stored = keyFile.readBytes()
            if (!crypto.isSealed(stored) && stored.size == ED25519_SECRET) {
                storeShareKey(crypto, stored)
                return stored
            }
            return crypto.openBytes(stored)
        }
        val k = Ed25519.newSecret()
        if (!storeShareKey(crypto, k)) unsavedShareKey = k
        return k
    }

    private fun storeShareKey(crypto: RecordCrypto, k: ByteArray): Boolean =
        runCatching { crypto.sealBytesOrThrow(k) }.getOrNull()?.let { DurableFiles.write(keyFile, it) } ?: false

    fun shareFingerprint(): String = Ed25519.fingerprint(Ed25519.publicKey(shareKey()))

    /** Signs a rule-pack template with the same personal key, so family sees one fingerprint for everything you share. */
    fun signTemplate(t: RuleTemplate): String = RuleTemplates.sign(t, shareKey())

    data class Export(val bytes: ByteArray, val numbers: Int, val ranges: Int, val skipped: Int)

    /** Exact rules become numbers, international prefixes become ranges; other rule types can't travel in a pack. */
    suspend fun exportRules(rules: List<BlockRule>, name: String, countryIso: String): Export = withContext(Dispatchers.Default) {
        val id = "user." + shareFingerprint().replace(" ", "").lowercase()
        val b = PackBuilder(
            PackManifest(
                id = id,
                name = name,
                publisher = "Shared from Parley",
                version = System.currentTimeMillis() / 1000,
                ttlDays = 0,
                categories = mapOf("1" to "Blocked by a friend"),
            ),
        )
        var numbers = 0
        var ranges = 0
        var skipped = 0
        rules.filter { it.kind == RuleKind.BLOCK && it.enabled && it.expiresAt == null }.forEach { r ->
            val ok = when (r.type) {
                RuleType.EXACT -> b.addNumber(r.pattern, 1, 90, countryIso).also { if (it) numbers++ }
                RuleType.PREFIX -> {
                    val p = RuleTools.canonicalPrefix(r.pattern, countryIso)
                    val intl = if (p.startsWith("+")) p else PhoneIdentity.e164(p + "0000000", countryIso)?.dropLast(7)
                    (intl != null && b.addRange(intl, 1, 90)).also { if (it) ranges++ }
                }
                else -> false
            }
            if (!ok) skipped++
        }
        Export(b.build(shareKey()), numbers, ranges, skipped)
    }
}

/** An Ed25519 secret, as older versions stored it plain. */
private const val ED25519_SECRET = 32
