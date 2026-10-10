package app.parley.data.backup

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.LabelRefs
import app.parley.common.NotifyLevel
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.StoredStatus
import app.parley.common.backup.ArchiveMeta
import app.parley.common.backup.ArchiveOrigin
import app.parley.common.backup.ArchiveSignatures
import app.parley.common.backup.ArchiveSigning
import app.parley.common.backup.BackupArchiveReader
import app.parley.common.backup.BackupArchiveWriter
import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.BackupFile
import app.parley.common.backup.BlockRuleRecord
import app.parley.common.backup.BlockedCallRecord
import app.parley.common.backup.BlockingSnapshot
import app.parley.common.backup.KeyBundle
import app.parley.common.backup.MergeAction
import app.parley.common.backup.MergePlan
import app.parley.common.backup.MergePlanner
import app.parley.common.backup.NumberSimRecord
import app.parley.common.backup.PhotoRefs
import app.parley.common.backup.Recipient
import app.parley.common.backup.RecordJson
import app.parley.common.backup.RecoveryKey
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.RetentionDecider
import app.parley.common.backup.SpeedDialRecord
import app.parley.common.backup.Unlock
import app.parley.common.backup.WrongKeyException
import app.parley.common.catching
import app.parley.common.history.RetentionDefaults
import app.parley.common.people.Batches
import app.parley.common.people.PrivateLabels
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Messengers
import app.parley.common.record.Mime
import app.parley.common.storage.PersistentStores
import app.parley.common.suspendRunCatching
import app.parley.data.BlockRepository
import app.parley.data.CallLogRepository
import app.parley.data.ContactDetailsJson
import app.parley.data.ContactsRepository
import app.parley.data.PrefsRepository
import app.parley.data.R
import app.parley.data.SettingsRepository
import app.parley.data.applyInBatches
import app.parley.data.db.AppDatabase
import app.parley.data.db.BlockedCallEntity
import app.parley.data.db.NumberSimEntity
import app.parley.data.records.ContactRecordStore
import app.parley.data.security.Privacy
import app.parley.data.vault.PrivateCall
import app.parley.data.vault.VaultCrypto
import app.parley.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.PrivateKey
import java.time.Instant
import java.time.ZoneId
import javax.crypto.SecretKey

data class BackupOutcome(
    val ok: Boolean,
    val name: String? = null,
    val contacts: Int = 0,
    val calls: Int = 0,
    val unchanged: Boolean = false,
    val verified: Boolean = false,
    val rotationPaused: Boolean = false,
    val vaultIncluded: Boolean = false,
    val message: String,
    /** Feature sections ([BackupExtras.section]) that couldn't be exported and are missing from this backup. */
    val failedSections: List<String> = emptyList(),
)

data class BackupFileInfo(val uri: Uri, val name: String, val time: Long, val size: Long)

/** A decrypted, integrity-checked backup ready for preview/restore. [origin]: which phone signed it, if any. */
class OpenedBackup internal constructor(val uri: Uri, val reader: BackupArchiveReader, val origin: ArchiveOrigin = ArchiveOrigin.UNSIGNED) {
    val createdAt: Long get() = reader.manifest.createdAt
    val counts: Map<String, Long> get() = reader.manifest.counts
}

data class RestoreOptions(
    val mode: RestoreMode = RestoreMode.MERGE,
    val contacts: Boolean = true,
    val applyConflicts: Boolean = false,
    val callLog: Boolean = true,
    val blocking: Boolean = true,
    val speedDial: Boolean = true,
    val settings: Boolean = false,
    val vault: Boolean = true,
)

data class RestoreReport(
    val added: Int = 0,
    val enriched: Int = 0,
    val rowsAdded: Int = 0,
    val deleted: Int = 0,
    val calls: Int = 0,
    val rules: Int = 0,
    val vault: Int = 0,
    val failed: Int = 0,
    /** Set when the restore didn't run at all. */
    val error: String? = null,
    /** Parts that couldn't be restored (e.g. private contacts while the vault is locked). */
    val skipped: List<String> = emptyList(),
    /** Circle entries (members, interactions, yearly flags) whose person wasn't found among the contacts here. */
    val unmatched: Int = 0,
    /** Parts that weren't applied because they would change a safeguard (supervised call-time limits) without asking. */
    val needsConfirmation: Boolean = false,
    /** Blocked-call log entries brought back. */
    val blockedLog: Int = 0,
    /** Photos the backup named but didn't hold: their contacts came back without them. */
    val missingPhotos: Int = 0,
) {
    fun summary(res: Resources) = error ?: buildList {
        add(res.getQuantityString(R.plurals.data_rst_added, added, added))
        if (enriched > 0) add(res.getQuantityString(R.plurals.data_rst_enriched, enriched, enriched, rowsAdded))
        if (deleted > 0) add(res.getQuantityString(R.plurals.data_rst_replaced, deleted, deleted))
        if (calls > 0) add(res.getQuantityString(R.plurals.data_calls_count, calls, calls))
        if (rules > 0) add(res.getQuantityString(R.plurals.data_rst_rules, rules, rules))
        if (vault > 0) add(res.getQuantityString(R.plurals.data_rst_vault, vault, vault))
        if (failed > 0) add(res.getQuantityString(R.plurals.data_rst_failed, failed, failed))
        if (missingPhotos > 0) add(res.getQuantityString(R.plurals.data_rst_photos_missing, missingPhotos, missingPhotos))
        skipped.forEach { add(res.getString(R.string.data_rst_not_restored, it)) }
    }.joinToString(" · ")
}

/**
 * Encrypted "Parley Backup" archives in a folder the user picked (Storage Access Framework).
 * Writes to a `.partial` file, verifies it end to end, then renames it and applies rotation.
 * Scheduled backups only need the public key; restoring needs the passphrase or recovery key.
 */
class BackupRepository(
    private val context: Context,
    private val contacts: ContactsRepository,
    private val records: ContactRecordStore,
    private val blocks: BlockRepository,
    private val prefsRepo: PrefsRepository,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val vault: VaultRepository,
    val prefs: BackupPrefs,
    /** The system call log: the history layer's own reads and writes. */
    private val callLog: CallLogRepository,
) {
    private val cr = context.contentResolver

    /** Feature data stored alongside the settings (see [BackupExtras]); set by the container. */
    var extras: () -> List<BackupExtras> = { emptyList() }

    /**
     * What Parley keeps beside each private contact (Circle, logged moments, call-screen picture), written only in
     * the private-contacts section, like the contacts themselves; set by the container.
     */
    var privateExtras: app.parley.data.people.ContactKeys? = null

    /**
     * Every feature's extras; a section that fails is left out and named in [failed] rather than dropped silently. So is
     * a backed-up store of [PersistentStores] that no section writes (a store added without its backup part).
     */
    private suspend fun extrasMap(failed: MutableList<String>): Map<String, String> {
        val parts = extras()
        val covered = BUILT_IN_SECTIONS + parts.flatMap { it.sections }
        (PersistentStores.requiredSections - covered).forEach { missing ->
            Log.w("BackupRepository", "No backup part writes section $missing")
            failed += missing
        }
        return parts.fold(emptyMap()) { acc, x ->
            acc + suspendRunCatching { x.export() }.getOrElse { e ->
                Log.w("BackupRepository", "Backup section ${x.section} failed", e)
                failed += x.section
                emptyMap()
            }
        }
    }

    /** Security settings from the last restore, waiting for the user to confirm them ([SettingsRepository.SECURITY_KEYS]). */
    @Volatile private var pendingSecurity: Map<String, String> = emptyMap()

    /** Whether the last restore left a part waiting for confirmation (see [ConfirmedRestore]). */
    fun hasPendingRestore(): Boolean = pendingSecurity.isNotEmpty() || extras().any { it is ConfirmedRestore && it.hasPending() }

    /** Applies what the last restore left waiting; call only after the user confirmed with the app lock. */
    suspend fun applyPendingRestore(): Boolean {
        val security = pendingSecurity
        pendingSecurity = emptyMap()
        if (security.isNotEmpty()) settings.importMap(security)
        return extras().filterIsInstance<ConfirmedRestore>().map { it.applyPending() }.any { it } || security.isNotEmpty()
    }

    fun discardPendingRestore() {
        pendingSecurity = emptyMap()
        extras().filterIsInstance<ConfirmedRestore>().forEach { it.discardPending() }
    }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Parley's call-history archive, backed up in its own optional section (set by the container). */
    var callHistory: CallHistoryBackup? = null

    /** Ringtones made from a name, carried as files of their own (set by the container). */
    var tuneFiles: CallerTuneFiles? = null

    // ------------------------------------------------------------------ keys

    /** First-time setup: returns the recovery key to show the user once. */
    suspend fun setupKeys(passphrase: CharArray): RecoveryKey = withContext(Dispatchers.Default) {
        val recovery = RecoveryKey.generate()
        val bundle = BackupCrypto.createKeyBundle(passphrase, recovery)
        prefs.saveKeyBundle(bundle)
        endorse(bundle, BackupCrypto.unlockPrivateKey(bundle, recovery))
        recovery
    }

    suspend fun changePassphrase(old: CharArray, new: CharArray): Boolean = withContext(Dispatchers.Default) {
        val bundle = prefs.keyBundle() ?: return@withContext false
        try {
            val changed = BackupCrypto.changePassphrase(bundle, old, new)
            prefs.saveKeyBundle(changed)
            if (!prefs.state.value.signedAsYours) endorse(changed, BackupCrypto.unlockPrivateKey(changed, new))
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Lets the backup key vouch for this phone's signing key, so a restore on another phone shows this phone's backups
     * as yours. Needs the passphrase once (backups made before signing existed). False on a wrong passphrase.
     */
    suspend fun confirmThisPhone(passphrase: CharArray): Boolean = withContext(Dispatchers.Default) {
        val bundle = prefs.keyBundle() ?: return@withContext false
        val pk = try {
            BackupCrypto.unlockPrivateKey(bundle, passphrase)
        } catch (_: WrongKeyException) {
            return@withContext false
        }
        endorse(bundle, pk)
    }

    /** Stores the bundle's endorsement of this phone's signing key; false when the Keystore has no key to offer. */
    private fun endorse(bundle: KeyBundle, privateKey: PrivateKey): Boolean {
        val signer = DeviceSigner.load(context) ?: return false
        prefs.saveEndorsement(bundle.keyId, ArchiveSignatures.endorse(privateKey, signer.publicKey))
        return true
    }

    /** This phone's signer with its endorsement, when the endorsement still matches the key and the bundle. */
    private fun signer(bundle: KeyBundle): DeviceSigner? {
        val signer = DeviceSigner.load(context) ?: return null
        val e = prefs.endorsement()?.takeIf { ArchiveSignatures.endorsementValid(bundle.publicKey, signer.publicKey, it) }
        return signer.endorsed(e)
    }

    fun setFolder(uri: Uri, name: String?) {
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        prefs.update { it.putString("folder", uri.toString()).putString("folderName", name) }
    }

    // ------------------------------------------------------------------ backup

    /**
     * Writes a backup. [scheduled] runs skip the new file when nothing changed since the last one.
     * With [target], writes that document instead of the backup folder (e.g. "move to a new phone").
     * A [safety] backup (taken before a Replace restore) never triggers rotation, so it can't delete the file
     * being restored from.
     */
    suspend fun backupNow(scheduled: Boolean, target: Uri? = null, safety: Boolean = false): BackupOutcome = withContext(Dispatchers.IO + NonCancellable) {
        val bundle = prefs.keyBundle() ?: return@withContext BackupOutcome(false, message = context.getString(R.string.data_bkp_need_pass))
        val state = prefs.state.value
        val folder = state.folderUri?.let(Uri::parse)
        if (target == null && folder == null) return@withContext BackupOutcome(false, message = context.getString(R.string.data_bkp_need_folder))

        if (target == null) cleanupPartials(folder!!)
        val now = Instant.now()
        val finalName = RetentionDecider.fileName(now, zone)
        val doc: Uri = target ?: try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            DocumentsContract.createDocument(cr, parent, "application/octet-stream", "$finalName.partial")
        } catch (e: Exception) {
            null
        } ?: return@withContext fail(context.getString(R.string.data_bkp_folder_gone), StoredStatus.of(BackupState.FOLDER_GONE))

        var contactCount = 0
        var callCount = 0
        var vaultIncluded = false
        val failedSections = ArrayList<String>()

        // A backup that is fine except for a feature section says so, instead of looking complete.
        fun gaps(o: BackupOutcome) = if (failedSections.isEmpty()) o
        else o.copy(
            failedSections = failedSections.toList(),
            message = context.getString(R.string.data_bkp_sections_missing, o.message, failedSections.joinToString(", ")),
        )
        val dataKey: SecretKey
        val manifest = try {
            cr.openOutputStream(doc, "wt")!!.use { raw ->
                val enc = BackupCrypto.encrypt(raw.buffered(), listOf(Recipient.PublicKey(bundle)))
                dataKey = enc.dataKey
                // Signed with this phone's Keystore key, so a restore can tell this backup from one planted in the folder.
                val signing = signer(bundle)?.let { ArchiveSigning(enc.header.bytes, it) }
                // Photos wait in a sealed temporary file, not in memory, until they are written at the end.
                val writer = BackupArchiveWriter(enc, ArchiveMeta(now.toEpochMilli(), appVersion(), device(), signing), spoolDir())
                writer.writeContacts(records.readAll(fullPhoto = true).onEach { contactCount++ })
                writer.writeCallLog(callLog.exportAll().onEach { callCount++ })
                callHistory?.let { h ->
                    // Streamed: each archived call goes to the archive as it is read.
                    val section = writer.callHistory()
                    h.backupLines(section::add)
                    section.end()
                }
                writer.writeBlocking(blocking())
                writer.writeSpeedDial(prefsRepo.speedDials.first().map { SpeedDialRecord(it.key, it.number, it.label) })
                writer.writeNumberSims(prefsRepo.numberSims.first().map { NumberSimRecord(it.matchKey, it.phoneAccountId) })
                writer.writeSettings(settings.exportMap() + extrasMap(failedSections))
                val v = vaultBlob()
                if (v != null) {
                    writer.writeVault(mapOf("vault.json" to v))
                    vaultIncluded = true
                }
                tuneFiles?.let { t -> writer.writeFiles(CallerTuneFiles.FOLDER, t.forBackup()) }
                val m = writer.finish()
                enc.finish()
                m
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            return@withContext fail(
                context.getString(R.string.data_bkp_failed, e.message.toString()), StoredStatus.of(BackupState.FAILED, e.message.toString()),
            )
        }

        // Verify: decrypt with this archive's key and check every entry's hash.
        val verified = try {
            BackupArchiveReader.open({ BackupCrypto.decrypt(cr.openInputStream(doc)!!, dataKey) }, spoolDir = spoolDir()).use { reader ->
                reader.contactCount == contactCount.toLong()
            }
        } catch (_: Exception) {
            false
        }
        if (!verified) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            return@withContext fail(context.getString(R.string.data_bkp_not_verified), StoredStatus.of(BackupState.NOT_VERIFIED))
        }
        if (target != null) {
            return@withContext gaps(
                BackupOutcome(
                    true,
                    null,
                    contactCount,
                    callCount,
                    verified = true,
                    vaultIncluded = vaultIncluded,
                    message = context.resources.getQuantityString(R.plurals.data_bkp_ready, contactCount, contactCount),
                ),
            )
        }

        val hash = manifest.contentHash()
        // A backup missing a feature section is kept, but it must not become the reference: rotation would otherwise
        // delete the older complete backups one by one, and later runs would compare against the incomplete content.
        val incomplete = failedSections.isNotEmpty()
        if (scheduled && hash == (if (incomplete) state.lastIncompleteHash else state.lastContentHash)) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            val status = if (incomplete) StoredStatus.of(BackupState.INCOMPLETE, failedSections.joinToString(", "), contactCount, callCount)
            else StoredStatus.of(BackupState.UNCHANGED)
            prefs.update { it.putLong("verifiedAt", System.currentTimeMillis()).putString("lastResult", status.encode()) }
            return@withContext gaps(
                BackupOutcome(
                    true,
                    state.lastBackupName,
                    contactCount,
                    callCount,
                    unchanged = true,
                    verified = true,
                    message = context.getString(R.string.data_bkp_nothing_changed),
                ),
            )
        }
        runCatching { DocumentsContract.renameDocument(cr, doc, finalName) }

        // Rotation, paused if many contacts disappeared (protects the last good backups). The reference count is
        // a high-water mark: it only moves while rotation runs, so the pause lasts until the user resumes it.
        val paused = !safety && state.lastContactCount >= 0 && RetentionDecider.mustPauseRotation(state.lastContactCount, contactCount)
        val hiding = Privacy.duressOnly().hiding
        val vaultMissing = !vaultIncluded && !hiding && runCatching { vault.summariesNow().isNotEmpty() }.getOrDefault(true)
        // L4: a backup made after a duress unlock never rotates out older ones, nor becomes the count rotation compares with.
        val rotates = RetentionDecider.rotates(paused, safety, incomplete, hiding)
        if (rotates) rotate(protect = if (vaultIncluded) finalName else state.lastVaultBackupName)
        val res = context.resources
        // Stored as what happened, rendered in the current language when shown (BackupState.resultText).
        val result = if (incomplete) StoredStatus.of(BackupState.INCOMPLETE, failedSections.joinToString(", "), contactCount, callCount).encode()
        else StoredStatus.of(BackupState.RESULT, if (vaultMissing) 1 else 0, contactCount, callCount).encode()
        prefs.update {
            it.putLong("lastAt", System.currentTimeMillis()).putString("lastName", finalName).putLong("verifiedAt", System.currentTimeMillis())
                .putBoolean("paused", paused)
                .putString("lastResult", result)
            if (incomplete) it.putString("gapHash", hash) else it.putString("lastHash", hash).remove("gapHash")
            if (rotates) it.putInt("lastCount", contactCount)
            if (vaultIncluded && !incomplete) it.putString("vaultName", finalName)
        }
        gaps(
            BackupOutcome(
                true,
                finalName,
                contactCount,
                callCount,
                verified = true,
                rotationPaused = paused,
                vaultIncluded = vaultIncluded,
                message = res.getQuantityString(if (paused) R.plurals.data_bkp_backed_up_paused else R.plurals.data_bkp_backed_up, contactCount, contactCount),
            ),
        )
    }

    /** Accepts the current contact count after a rotation pause, so old backups rotate again. */
    fun resumeRotation() = prefs.update { it.putInt("lastCount", -1).putBoolean("paused", false) }

    private fun fail(msg: String, status: StoredStatus): BackupOutcome {
        prefs.update { it.putString("lastResult", status.encode()) }
        return BackupOutcome(false, message = msg)
    }

    private fun appVersion(): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun device(): Map<String, String> = mapOf("model" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT.toString())

    /** Read from the database, never from the UI flows (they start empty in a worker process that just started). */
    private suspend fun blocking(): BlockingSnapshot {
        val rules = blocks.allRules().map {
            BlockRuleRecord(
                it.pattern,
                it.type.name,
                it.action.name,
                it.enabled,
                it.note,
                it.kind.name,
                it.simId,
                it.schedule?.encode(),
                it.notify.name,
                it.ringtone,
                it.expiresAt,
                it.label,
            )
        }
        val system = blocks.loadSystemNow().map { it.number }
        val log = db.blockDao().blockedCallsNow().map { BlockedCallRecord(it.number, it.reason, it.action, it.time) }
        return BlockingSnapshot(rules, system, log)
    }

    /** A private contact's labels, by title: group ids mean nothing on another phone (optional; older versions ignore it). */
    private fun putLabels(o: JSONObject, v: app.parley.data.vault.VaultSummary) {
        if (v.labels.isNotEmpty()) o.put("labels", JSONArray(v.labels.map { it.title }))
        // Its vibration and auto-answer, kept in the caller-ID copy like the labels (optional too).
        v.vibration?.let { o.put("vibration", it) }
        if (v.autoAnswer) o.put("autoAnswer", true)
        // Archived inside the vault: it comes back archived, and private (optional: older versions list it).
        v.archivedAt?.let { o.put("archivedAt", it) }
    }

    private suspend fun putPrivateExtras(o: JSONObject, vaultId: Long) {
        val extras = runCatching { privateExtras?.exportPrivate(app.parley.common.people.ContactRef.privateKey(vaultId)) }.getOrNull() ?: return
        o.put("parley", extras)
    }

    /**
     * Locked or unreadable right now: private contacts are left out (and reported as left out), never half written. A
     * key lost for good still lets what's left (the caller-ID copies) be saved.
     */
    // I21: never after a duress unlock, even when the phone's own unlock left the detail key open.
    private fun vaultReadable(): Boolean = !Privacy.duressOnly().hiding && (!VaultCrypto.detailNeedsUnlock() || VaultCrypto.detailKeyLost())

    /** Private contacts, re-encrypted under the archive key. Needs the vault unlocked (otherwise skipped). */
    private suspend fun vaultBlob(): ByteArray? {
        val list = vault.summariesNow()
        if (list.isEmpty()) return null
        if (!vaultReadable()) return null
        val arr = JSONArray()
        for (v in list) {
            val d = runCatching { vault.details(v.id) }.getOrNull() ?: continue
            val o = JSONObject().put("details", ContactDetailsJson.encode(d.copy(photoUri = null))).put("expiresAt", v.expiresAt ?: 0L)
            if (v.purgeHistory) o.put("purgeHistory", true)
            putLabels(o, v)
            // The lossless phone-contact image of a moved contact, with the hash that tells whether the details
            // were edited since ("recordOf"), so moving out after a restore behaves as before. Optional: older
            // Parley versions ignore it (and only wrote it for unedited entries, which is what a missing hash means).
            runCatching { vault.storedRecord(v.id) }.getOrNull()?.let { s ->
                val blobs = JSONObject()
                o.put("record", RecordJson.encode(s.record) { h, b -> blobs.put(h, Base64.encodeToString(b, Base64.NO_WRAP)) })
                o.put("recordBlobs", blobs)
                if (s.recordOf.isNotEmpty()) o.put("recordOf", s.recordOf)
            }
            // Logged interactions carried in the entry while the contact is private.
            runCatching { vault.storedInteractions(v.id) }.getOrNull()?.let { o.put("interactions", it) }
            // Parley's own data about them, kept under their private key (never in the sections every backup has).
            putPrivateExtras(o, v.id)
            // The private call history: removed from the system log, so this is its only copy.
            val calls = JSONArray()
            vault.privateCallsOf(v.id).forEach { c ->
                calls.put(privateCallJson(c))
            }
            if (calls.length() > 0) o.put("calls", calls)
            // The caller photo (kept encrypted apart from the details); inside the archive it is under the archive key.
            vault.photoBytes(v.id)?.let { o.put("photo", Base64.encodeToString(it, Base64.NO_WRAP)) }
            arr.put(o)
        }
        return JSONObject().put("contacts", arr).toString().toByteArray()
    }

    // ------------------------------------------------------------------ folder

    fun listBackups(): List<BackupFileInfo> {
        val folder = prefs.state.value.folderUri?.let(Uri::parse) ?: return emptyList()
        val out = ArrayList<BackupFileInfo>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            cr.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val t = RetentionDecider.parseName(name, zone) ?: continue
                    out += BackupFileInfo(DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0)), name, t.toEpochMilli(), c.getLong(2))
                }
            }
        } catch (_: Exception) {
        }
        return out.sortedByDescending { it.time }
    }

    /** Removes `.partial` files left by an interrupted backup (older than an hour, so a running one is safe). */
    private fun cleanupPartials(folder: Uri) = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val cutoff = System.currentTimeMillis() - 3_600_000L
        cr.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.endsWith(".partial") && c.getLong(2) in 1 until cutoff) {
                    runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(folder, c.getString(0))) }
                }
            }
        }
    }

    private fun rotate(protect: String?) {
        val files = listBackups().filter { it.name != protect }
        val decision = RetentionDecider.decide(files.map { BackupFile(it.name, it.time) }, prefs.state.value.policy, Instant.now(), zone)
        val toDelete = decision.delete.map { it.name }.toSet()
        files.filter { it.name in toDelete }.forEach { runCatching { DocumentsContract.deleteDocument(cr, it.uri) } }
    }

    /** Where a backup's photos wait while it is written or read (each spool file is unlinked as soon as it is open). */
    private fun spoolDir() = File(context.cacheDir, "backup_spool")

    // ------------------------------------------------------------------ restore

    /**
     * Decrypts and fully verifies a backup, and checks which phone signed it. Throws WrongKeyException /
     * BackupIntegrityException.
     */
    suspend fun open(uri: Uri, unlock: Unlock): OpenedBackup = withContext(Dispatchers.IO) {
        val header = cr.openInputStream(uri)!!.use { BackupCrypto.readHeader(it) }
        val opened = BackupCrypto.open(header, unlock)
        val key = opened.dataKey
        // Photos are spooled, sealed, to a temporary file the reader lets go of when it is no longer used.
        val reader = BackupArchiveReader.open({ BackupCrypto.decrypt(cr.openInputStream(uri)!!, key) }, spoolDir = spoolDir())
        // The user just proved they hold this phone's backup secret: let it vouch for this phone if it didn't yet.
        val mine = prefs.keyBundle()
        if (mine != null && opened.bundle?.keyId == mine.keyId && !prefs.state.value.signedAsYours) {
            opened.privateKey?.let { runCatching { endorse(mine, it) } }
        }
        val origin = ArchiveSignatures.verify(header.bytes, reader.manifest, opened.bundle, DeviceSigner.publicKey())
        OpenedBackup(uri, reader, origin)
    }

    /**
     * Plans from both address books with photos as hashes ([PhotoRefs]): each record is made light as it is read, so
     * neither book's photos are held while planning (20k contacts with photos would not fit). [restore] puts the
     * photos back for the records it writes.
     */
    suspend fun plan(opened: OpenedBackup, mode: RestoreMode): MergePlan = withContext(Dispatchers.IO) {
        val existing = records.readAll(fullPhoto = false).map(PhotoRefs::light).toList()
        // Every photo the backup names is checked here, before anything is deleted or written: one the archive doesn't
        // hold is dropped from its contact (which is restored without it) and counted for the report.
        var missing = 0
        val backup = opened.reader.contactsLight { seq ->
            seq.map { r -> PhotoRefs.resolvable(r, opened.reader::hasPhoto).let { (kept, n) -> missing += n; kept } }.toList()
        }
        MergePlanner.plan(existing, backup, mode).copy(missingPhotos = missing)
    }

    /**
     * Restores the chosen parts. Runs to completion even if the screen goes away; each part is isolated so one
     * failure (e.g. a locked vault) doesn't abandon the rest. A Replace restore first takes a safety backup and
     * doesn't start if that fails.
     */
    suspend fun restore(opened: OpenedBackup, plan: MergePlan, o: RestoreOptions): RestoreReport = withContext(Dispatchers.IO + NonCancellable) {
        var r = RestoreReport(missingPhotos = if (o.contacts) plan.missingPhotos else 0)
        val skipped = ArrayList<String>()
        if (o.contacts && plan.mode == RestoreMode.REPLACE && plan.toDelete.isNotEmpty()) {
            val safety = backupNow(scheduled = false, safety = true)
            if (!safety.ok) return@withContext RestoreReport(error = context.getString(R.string.data_rst_safety_failed, safety.message))
        }
        val insertedRaws = ArrayList<Long>()
        fun remember() = prefs.update { it.putString("restoreRawIds", insertedRaws.joinToString(",")) }
        remember()
        if (o.contacts) try {
            if (plan.mode == RestoreMode.REPLACE && plan.toDelete.isNotEmpty()) {
                val ids = plan.toDelete.mapNotNull { idForKey(it.key) }
                contacts.delete(ids) // journaled first
                r = r.copy(deleted = ids.size)
            }
            val news = plan.actions.filterIsInstance<MergeAction.New>().map { it.backup }
            var added = 0
            // Photos come back a chunk at a time, only for the contacts written.
            news.chunked(200).forEach { light ->
                val chunk = light.map { PhotoRefs.filled(it, opened.reader::photo) }
                records.insertAll(chunk, target = null).forEach { res ->
                    insertedRaws += res.rawIds
                    if (res.contactId != null) added++ else r = r.copy(failed = r.failed + 1)
                }
                remember()
            }
            r = r.copy(added = added)
            for (a in plan.actions) {
                val (existing, rows) = when (a) {
                    is MergeAction.Enrich -> a.existing to a.missingRows
                    is MergeAction.Conflict -> if (o.applyConflicts) a.existing to a.missingRows else continue
                    else -> continue
                }
                // Photos aren't added to an existing contact (single-valued, see addRows): none is read for one.
                val n = addRows(existing, rows.filter { it.mimeType != Mime.PHOTO })
                if (n > 0) r = r.copy(enriched = r.enriched + 1, rowsAdded = r.rowsAdded + n)
            }
        } catch (e: Exception) {
            skipped += context.getString(R.string.data_rst_some_contacts, e.message ?: e.javaClass.simpleName)
        }
        suspend fun part(name: String, block: suspend () -> Unit) = try {
            block()
        } catch (e: Exception) {
            skipped += name
        }
        if (o.callLog) part(context.getString(R.string.data_rst_part_calls)) {
            r = r.copy(calls = restoreCallLog(opened))
            val h = callHistory
            if (h != null) r = r.copy(calls = r.calls + restoreArchive(opened, h))
        }
        // Feature parts ride on the choice they belong to; they match people against the contacts restored above.
        val featureValues = if (o.contacts || o.blocking || o.settings) runCatching { opened.reader.settings() }.getOrNull()?.filterKeys { it.startsWith(BackupExtras.PREFIX) }.orEmpty() else emptyMap()
        suspend fun features(which: RestorePart) {
            if (featureValues.isEmpty()) return
            extras().filter { it.restoreWith == which }.forEach { e ->
                part(e.section) { r = r.copy(unmatched = r.unmatched + e.importCounting(featureValues)) }
            }
        }
        if (o.contacts) features(RestorePart.CONTACTS)
        if (o.blocking) part(context.getString(R.string.data_rst_part_blocking)) {
            val (rules, log) = restoreBlocking(opened)
            r = r.copy(rules = rules, blockedLog = log)
        }
        if (o.blocking) features(RestorePart.BLOCKING)
        if (o.speedDial) part(context.getString(R.string.data_rst_part_speed_dial)) {
            opened.reader.speedDial()?.forEach { prefsRepo.setSpeedDial(it.slot, it.number, it.label) }
            opened.reader.numberSims()?.forEach { db.prefsDao().setSim(NumberSimEntity(it.matchKey, it.phoneAccountId)) }
        }
        if (o.settings) part(context.getString(R.string.data_rst_part_settings)) {
            opened.reader.settings()?.let { all ->
                val plain = all.filterKeys { !it.startsWith(BackupExtras.PREFIX) }
                // App lock, discreet mode and hiding the screen wait for the user's confirmation; everything else applies.
                val (security, rest) = plain.entries.partition { it.key in SettingsRepository.SECURITY_KEYS }
                // A backup without a retention kept calls forever: it doesn't take this phone's new-install default.
                settings.importMap(RetentionDefaults.restored(rest.associate { it.key to it.value }, SettingsRepository.RETENTION_KEY))
                val here = settings.exportMap()
                pendingSecurity = security.associate { it.key to it.value }.filter { (k, v) -> here[k] != v }
                // Off hours' "only this label" names a label of the old phone: keep it only if that title exists here.
                val titles = labelTitlesHere()
                settings.update { s -> s.copy(screening = s.screening.copy(offHours = LabelRefs.restoreOffHours(s.screening.offHours, titles))) }
            }
            features(RestorePart.SETTINGS)
        }
        if (o.contacts || o.settings) part(context.getString(R.string.data_rst_part_tunes)) { restoreTunes(opened) }
        if (o.vault) try {
            r = r.copy(vault = restoreVault(opened))
        } catch (e: VaultCrypto.LockedException) {
            skipped += context.getString(R.string.data_rst_part_vault_locked)
        } catch (e: Exception) {
            skipped += context.getString(R.string.data_rst_part_vault)
        }
        contacts.refresh()
        r.copy(skipped = skipped, needsConfirmation = hasPendingRestore())
    }

    /** Removes the raw contacts the last restore added (existing contacts they joined keep their own entries). */
    suspend fun undoLastRestore(): Int = withContext(Dispatchers.IO + NonCancellable) {
        val ids = prefs.state.value.lastRestoreIds
        // Batch by batch, so one that fails (a contact gone meanwhile) doesn't keep the others.
        Batches.chunks(ids).forEach { chunk ->
            val ops = chunk.map { ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it)) }
            catching { cr.applyInBatches(ops) }
        }
        prefs.update { it.putString("restoreRawIds", "") }
        contacts.refresh()
        ids.size
    }

    /**
     * The backup's archived calls, streamed in chunks: a 100k-call archive is never one list. The reader's section is
     * only readable inside its block, which isn't suspending: each chunk is written from it on this (IO) thread.
     */
    private suspend fun restoreArchive(opened: OpenedBackup, h: CallHistoryBackup): Int {
        val restore = h.beginRestore()
        try {
            return opened.reader.callHistory { seq -> seq.chunked(RESTORE_LINES_CHUNK).sumOf { chunk -> runBlocking { restore.add(chunk) } } } ?: 0
        } finally {
            restore.finish()
        }
    }

    private companion object {
        /** Archived calls restored per write. */
        const val RESTORE_LINES_CHUNK = 2_000

        /** Sections the backup writes itself (contacts, calls, blocking, speed dial, settings, private contacts, tunes). */
        val BUILT_IN_SECTIONS = with(PersistentStores.Sections) { setOf(CONTACTS, CALL_LOG, CALL_HISTORY, BLOCKING, SPEED_DIAL, SETTINGS, VAULT, TUNES) }
    }

    private fun idForKey(key: String): Long? = runCatching {
        ContactsContract.Contacts.lookupContact(cr, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key))?.let { ContentUris.parseId(it) }
    }.getOrNull()

    /** Adds rows (never removes) to the first writable raw contact of [existing]. */
    private fun addRows(existing: ContactRecord, rows: List<DataRow>): Int {
        val id = idForKey(existing.key) ?: return 0
        val raw = cr.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0", arrayOf(id.toString()), null)?.use { c ->
            var pick: Long? = null
            while (c.moveToNext()) {
                val type = c.getString(1)
                if (type == null || type == "com.google" || !Messengers.isMessengerAccount(type)) { pick = c.getLong(0); break }
            }
            pick
        } ?: return 0
        val ops = ArrayList<ContentProviderOperation>()
        rows.filter { it.mimeType != Mime.GROUP && it.mimeType != Mime.PHOTO }.forEach { row ->
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw)
                .withValue(ContactsContract.Data.MIMETYPE, row.mimeType)
            row.values.forEach { (k, v) -> if (k.startsWith("data") && k.removePrefix("data").toIntOrNull() in 1..14) b.withValue(k, v) }
            ops += b.build()
        }
        if (ops.isEmpty()) return 0
        return try {
            cr.applyBatch(ContactsContract.AUTHORITY, ops)
            ops.size
        } catch (_: Exception) {
            0
        }
    }

    private fun restoreCallLog(opened: OpenedBackup): Int {
        val existing = callLog.rowSignatures()
        var n = 0
        opened.reader.callLog { seq ->
            seq.filter { CallLogRepository.signature(it.number, it.date, it.duration, it.type) !in existing }.chunked(200).forEach { n += callLog.insert(it) }
        }
        return n
    }

    /** Label titles on this phone (the way label references are keyed; group row ids differ between phones). */
    private fun labelTitlesHere(): Set<String> = runCatching { contacts.groups().map { LabelRefs.key(it.title) }.toSet() }.getOrDefault(emptySet())

    /** Rules (deduplicated) and the blocked-call log (entries this phone doesn't have). Returns both counts. */
    private suspend fun restoreBlocking(opened: OpenedBackup): Pair<Int, Int> {
        val snap = opened.reader.blocking() ?: return 0 to 0
        val existing = blocks.allRules().map { it.kind.name + "|" + it.pattern + "|" + it.type.name }.toHashSet()
        val titles = labelTitlesHere()
        var n = 0
        snap.rules.forEach { r ->
            val rule = BlockRule(
                pattern = r.pattern,
                type = runCatching { RuleType.valueOf(r.type) }.getOrDefault(RuleType.EXACT),
                action = runCatching { BlockAction.valueOf(r.action) }.getOrDefault(BlockAction.REJECT),
                enabled = r.enabled, note = r.note,
                kind = runCatching { RuleKind.valueOf(r.kind) }.getOrDefault(RuleKind.BLOCK),
                simId = r.simId, schedule = Schedule.decode(r.schedule),
                notify = runCatching { NotifyLevel.valueOf(r.notify) }.getOrDefault(NotifyLevel.DEFAULT),
                ringtone = r.ringtone, expiresAt = r.expiresAt, label = r.label,
            )
            // Label rules are remapped by title; a rule for a label that doesn't exist here is dropped.
            val mapped = LabelRefs.restoreRule(rule, titles) ?: return@forEach
            if (!existing.add(mapped.kind.name + "|" + mapped.pattern.trim() + "|" + mapped.type.name)) return@forEach
            blocks.saveRule(mapped)
            n++
        }
        snap.systemBlockedNumbers.forEach { blocks.blockNumber(it) }
        var logged = 0
        val dao = db.blockDao()
        db.withTransaction {
            for (e in snap.blockedCalls) {
                if (dao.countBlocked(e.number, e.time) > 0) continue
                dao.logBlocked(BlockedCallEntity(number = e.number, reason = e.reason, action = e.action, time = e.time))
                logged++
            }
        }
        return n to logged
    }

    /** Ringtones made from a name: files the restored contacts and labels name by URI. Only missing ones are written. */
    private fun restoreTunes(opened: OpenedBackup) {
        val t = tuneFiles ?: return
        opened.reader.files(CallerTuneFiles.FOLDER) { name, bytes -> t.restore(name, bytes) }
    }

    private suspend fun restoreVault(opened: OpenedBackup): Int {
        val blob = opened.reader.vault()["vault.json"] ?: return 0
        val arr = JSONObject(String(blob)).optJSONArray("contacts") ?: return 0
        val have = vault.summariesNow().associate { (it.name to it.numbers.toSet()) to it.id }
        var n = 0

        // Private calls of an entry, restored once (the dedupe key skips calls already there).
        suspend fun restoreCalls(id: Long, o: JSONObject) {
            val calls = o.optJSONArray("calls") ?: return
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                catching {
                    vault.storePrivateCall(
                        id, c.optString("n"), c.optString("name"), c.optLong("d"), c.optLong("s"), c.optInt("t"), c.optBoolean("v"),
                        app = c.optString("app").ifBlank { null },
                    )
                }
            }
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = ContactDetailsJson.decode(o.getString("details"))
            val sig = d.displayName to d.phones.map { it.value }.toSet()
            have[sig]?.let { existing ->
                restoreCalls(existing, o)
                continue
            }
            val blobs = o.optJSONObject("recordBlobs")
            val record = o.optString("record").takeIf { it.isNotEmpty() }?.let { line ->
                runCatching {
                    RecordJson.decode(line) { h ->
                        blobs?.optString(h)?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
                    }
                }.getOrNull()
            }
            val expiresAt = o.optLong("expiresAt").takeIf { it > 0 }
            val id = vault.save(
                null, d.copy(photoUri = null), expiresAt,
                purgeHistory = if (expiresAt != null) o.optBoolean("purgeHistory", false) else null,
                record = record, recordOf = o.optString("recordOf").takeIf { record != null && it.isNotEmpty() },
                interactions = o.optString("interactions").takeIf { it.isNotEmpty() },
            )
            o.optString("photo").takeIf { it.isNotEmpty() }?.let { p ->
                runCatching { vault.setPhoto(id, Base64.decode(p, Base64.NO_WRAP)) }
            }
            // Labels found again by title on this phone (resolved whenever they are read).
            o.optJSONArray("labels")?.let { a ->
                val titles = (0 until a.length()).mapNotNull { a.optString(it).takeIf { t -> t.isNotBlank() } }
                if (titles.isNotEmpty()) runCatching { vault.updateCallerChoices(id) { s -> s.copy(labels = titles.map { PrivateLabels.Membership(0, it) }) } }
            }
            val vibration = o.optString("vibration").ifEmpty { null }
            if (vibration != null || o.optBoolean("autoAnswer")) {
                runCatching { vault.updateCallerChoices(id) { s -> s.copy(vibration = vibration, autoAnswer = o.optBoolean("autoAnswer")) } }
            }
            o.optLong("archivedAt").takeIf { it > 0 }?.let { at -> catching { vault.setArchived(id, at) } }
            restoreCalls(id, o)
            o.optJSONObject("parley")?.let { x -> runCatching { privateExtras?.importPrivate(id, x) } }
            n++
        }
        return n
    }
}

/** One private call in a backup; "app" only for a call an app made over the internet (older versions ignore it). */
private fun privateCallJson(c: PrivateCall): JSONObject =
    JSONObject().put("n", c.number).put("name", c.name).put("d", c.date).put("s", c.durationSec).put("t", c.type).put("v", c.video)
        .apply { c.app?.let { put("app", it) } }
