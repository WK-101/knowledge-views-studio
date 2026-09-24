package app.parley.data.backup

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.DocumentsContract
import app.parley.common.backup.BackupArchiveReader
import app.parley.common.backup.BackupArchiveWriter
import app.parley.common.backup.ArchiveMeta
import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.BlockRuleRecord
import app.parley.common.backup.BlockedCallRecord
import app.parley.common.backup.BlockingSnapshot
import app.parley.common.backup.CallLogRecord
import app.parley.common.backup.MergeAction
import app.parley.common.backup.MergePlan
import app.parley.common.backup.MergePlanner
import app.parley.common.backup.NumberSimRecord
import app.parley.common.backup.Recipient
import app.parley.common.backup.RecoveryKey
import app.parley.common.backup.RestoreMode
import app.parley.common.backup.RetentionDecider
import app.parley.common.backup.SpeedDialRecord
import app.parley.common.backup.Unlock
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.RuleType
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.data.BlockRepository
import app.parley.data.ContactDetailsJson
import app.parley.data.ContactsRepository
import app.parley.data.PrefsRepository
import app.parley.data.SettingsRepository
import app.parley.data.db.AppDatabase
import app.parley.data.records.ContactRecordStore
import app.parley.data.vault.VaultCrypto
import app.parley.data.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
)

data class BackupFileInfo(val uri: Uri, val name: String, val time: Long, val size: Long)

/** A decrypted, integrity-checked backup ready for preview/restore. */
class OpenedBackup internal constructor(val uri: Uri, val reader: BackupArchiveReader) {
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
) {
    fun summary() = error ?: buildList {
        add("$added contacts added")
        if (enriched > 0) add("$enriched updated with $rowsAdded details")
        if (deleted > 0) add("$deleted replaced")
        if (calls > 0) add("$calls calls")
        if (rules > 0) add("$rules blocking rules")
        if (vault > 0) add("$vault private contacts")
        if (failed > 0) add("$failed failed")
        skipped.forEach { add("not restored: $it") }
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
) {
    private val cr = context.contentResolver

    /** Feature data stored alongside the settings (see [BackupExtras]); set by the container. */
    var extras: () -> List<BackupExtras> = { emptyList() }

    private suspend fun extrasMap(): Map<String, String> = extras().fold(emptyMap()) { acc, x -> acc + runCatching { x.export() }.getOrDefault(emptyMap()) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Parley's call-history archive, backed up in its own optional section (set by the container). */
    var callHistory: CallHistoryBackup? = null

    // ------------------------------------------------------------------ keys

    /** First-time setup: returns the recovery key to show the user once. */
    suspend fun setupKeys(passphrase: CharArray): RecoveryKey = withContext(Dispatchers.Default) {
        val recovery = RecoveryKey.generate()
        prefs.saveKeyBundle(BackupCrypto.createKeyBundle(passphrase, recovery))
        recovery
    }

    suspend fun changePassphrase(old: CharArray, new: CharArray): Boolean = withContext(Dispatchers.Default) {
        val bundle = prefs.keyBundle() ?: return@withContext false
        try {
            prefs.saveKeyBundle(BackupCrypto.changePassphrase(bundle, old, new))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setFolder(uri: Uri, name: String?) {
        runCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
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
        val bundle = prefs.keyBundle() ?: return@withContext BackupOutcome(false, message = "Set a backup passphrase first")
        val state = prefs.state.value
        val folder = state.folderUri?.let(Uri::parse)
        if (target == null && folder == null) return@withContext BackupOutcome(false, message = "Choose a backup folder first")

        if (target == null) cleanupPartials(folder!!)
        val now = Instant.now()
        val finalName = RetentionDecider.fileName(now, zone)
        val doc: Uri = target ?: try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            DocumentsContract.createDocument(cr, parent, "application/octet-stream", "$finalName.partial")
        } catch (e: Exception) {
            null
        } ?: return@withContext fail("The backup folder is no longer accessible. Choose it again.")

        var contactCount = 0
        var callCount = 0
        var vaultIncluded = false
        val dataKey: SecretKey
        val manifest = try {
            cr.openOutputStream(doc, "wt")!!.use { raw ->
                val enc = BackupCrypto.encrypt(raw.buffered(), listOf(Recipient.PublicKey(bundle)))
                dataKey = enc.dataKey
                val writer = BackupArchiveWriter(enc, ArchiveMeta(now.toEpochMilli(), appVersion(), device()))
                writer.writeContacts(records.readAll(fullPhoto = true).onEach { contactCount++ })
                writer.writeCallLog(readCallLog().onEach { callCount++ })
                callHistory?.let { h -> writer.writeCallHistory(h.backupLines()) }
                writer.writeBlocking(blocking())
                writer.writeSpeedDial(prefsRepo.speedDials.first().map { SpeedDialRecord(it.key, it.number, it.label) })
                writer.writeNumberSims(prefsRepo.numberSims.first().map { NumberSimRecord(it.matchKey, it.phoneAccountId) })
                writer.writeSettings(settings.exportMap() + extrasMap())
                val v = vaultBlob()
                if (v != null) {
                    writer.writeVault(mapOf("vault.json" to v))
                    vaultIncluded = true
                }
                val m = writer.finish()
                enc.finish()
                m
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            return@withContext fail("Backup failed: ${e.message}")
        }

        // Verify: decrypt with this archive's key and check every entry's hash.
        val verified = try {
            val reader = BackupArchiveReader.open({ BackupCrypto.decrypt(cr.openInputStream(doc)!!, dataKey) })
            reader.contactCount == contactCount.toLong()
        } catch (_: Exception) {
            false
        }
        if (!verified) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            return@withContext fail("The written backup didn't verify, so it was discarded. Check free space and try again.")
        }
        if (target != null) {
            return@withContext BackupOutcome(true, null, contactCount, callCount, verified = true, vaultIncluded = vaultIncluded, message = "Backup ready: $contactCount contacts")
        }

        val hash = manifest.contentHash()
        if (scheduled && hash == state.lastContentHash) {
            runCatching { DocumentsContract.deleteDocument(cr, doc) }
            prefs.update { it.putLong("verifiedAt", System.currentTimeMillis()).putString("lastResult", "Unchanged since the last backup") }
            return@withContext BackupOutcome(true, state.lastBackupName, contactCount, callCount, unchanged = true, verified = true, message = "Nothing changed since the last backup")
        }
        val renamed = runCatching { DocumentsContract.renameDocument(cr, doc, finalName) }.getOrNull() ?: doc

        // Rotation, paused if many contacts disappeared (protects the last good backups). The reference count is
        // a high-water mark: it only moves while rotation runs, so the pause lasts until the user resumes it.
        val paused = !safety && state.lastContactCount >= 0 && RetentionDecider.mustPauseRotation(state.lastContactCount, contactCount)
        val vaultMissing = !vaultIncluded && vault.contacts.value.isNotEmpty()
        if (!paused && !safety) rotate(protect = if (vaultIncluded) finalName else state.lastVaultBackupName)
        val result = "Backed up $contactCount contacts and $callCount calls" + if (vaultMissing) ". Private contacts were not included: open Parley and unlock them, then back up again." else ""
        prefs.update {
            it.putLong("lastAt", System.currentTimeMillis()).putString("lastName", finalName).putLong("verifiedAt", System.currentTimeMillis())
                .putString("lastHash", hash).putBoolean("paused", paused)
                .putString("lastResult", result)
            if (!paused && !safety) it.putInt("lastCount", contactCount)
            if (vaultIncluded) it.putString("vaultName", finalName)
        }
        BackupOutcome(true, finalName, contactCount, callCount, verified = true, rotationPaused = paused, vaultIncluded = vaultIncluded, message = "Backed up $contactCount contacts" + if (paused) " — rotation paused because many contacts disappeared" else "")
    }

    /** Accepts the current contact count after a rotation pause, so old backups rotate again. */
    fun resumeRotation() = prefs.update { it.putInt("lastCount", -1).putBoolean("paused", false) }

    private fun fail(msg: String): BackupOutcome {
        prefs.update { it.putString("lastResult", msg) }
        return BackupOutcome(false, message = msg)
    }

    private fun appVersion(): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun device(): Map<String, String> = mapOf("model" to Build.MODEL, "sdk" to Build.VERSION.SDK_INT.toString())

    private fun readCallLog(): Sequence<CallLogRecord> = sequence {
        val c = runCatching {
            cr.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.NUMBER_PRESENTATION, CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, CallLog.Calls.CACHED_NAME, CallLog.Calls.NEW, CallLog.Calls.IS_READ),
                null, null, CallLog.Calls.DATE + " ASC",
            )
        }.getOrNull() ?: return@sequence
        c.use {
            while (it.moveToNext()) {
                yield(CallLogRecord(it.getString(0), it.getLong(1), it.getLong(2), it.getInt(3), it.getInt(4), it.getString(5), it.getString(6), it.getString(7), it.getInt(8) != 0, it.getInt(9) != 0))
            }
        }
    }

    private suspend fun blocking(): BlockingSnapshot {
        val rules = blocks.rules.value.map {
            BlockRuleRecord(it.pattern, it.type.name, it.action.name, it.enabled, it.note, it.kind.name, it.simId, it.schedule?.encode(), it.notify.name, it.ringtone, it.expiresAt, it.label)
        }
        val system = blocks.loadSystemNow().map { it.number }
        val log = blocks.blockedCalls.first().map { BlockedCallRecord(it.number, it.reason, it.action, it.time) }
        return BlockingSnapshot(rules, system, log)
    }

    /** Private contacts, re-encrypted under the archive key. Needs the vault unlocked (otherwise skipped). */
    private suspend fun vaultBlob(): ByteArray? {
        val list = vault.contacts.value
        if (list.isEmpty()) return null
        if (VaultCrypto.detailNeedsUnlock()) return null
        val arr = JSONArray()
        for (v in list) {
            val d = runCatching { vault.details(v.id) }.getOrNull() ?: continue
            arr.put(JSONObject().put("details", ContactDetailsJson.encode(d)).put("expiresAt", v.expiresAt ?: 0L))
        }
        return JSONObject().put("contacts", arr).toString().toByteArray()
    }

    // ------------------------------------------------------------------ folder

    fun listBackups(): List<BackupFileInfo> {
        val folder = prefs.state.value.folderUri?.let(Uri::parse) ?: return emptyList()
        val out = ArrayList<BackupFileInfo>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
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
        cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
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
        val decision = RetentionDecider.decide(files.map { app.parley.common.backup.BackupFile(it.name, it.time) }, prefs.state.value.policy, Instant.now(), zone)
        val toDelete = decision.delete.map { it.name }.toSet()
        files.filter { it.name in toDelete }.forEach { runCatching { DocumentsContract.deleteDocument(cr, it.uri) } }
    }

    // ------------------------------------------------------------------ restore

    /** Decrypts and fully verifies a backup. Throws WrongKeyException / BackupIntegrityException. */
    suspend fun open(uri: Uri, unlock: Unlock): OpenedBackup = withContext(Dispatchers.IO) {
        val header = cr.openInputStream(uri)!!.use { BackupCrypto.readHeader(it) }
        val key = BackupCrypto.unwrapDataKey(header, unlock)
        OpenedBackup(uri, BackupArchiveReader.open({ BackupCrypto.decrypt(cr.openInputStream(uri)!!, key) }))
    }

    suspend fun plan(opened: OpenedBackup, mode: RestoreMode): MergePlan = withContext(Dispatchers.IO) {
        val existing = records.readAll(fullPhoto = false).toList()
        val backup = opened.reader.contacts { it.toList() }
        MergePlanner.plan(existing, backup, mode)
    }

    /**
     * Restores the chosen parts. Runs to completion even if the screen goes away; each part is isolated so one
     * failure (e.g. a locked vault) doesn't abandon the rest. A Replace restore first takes a safety backup and
     * doesn't start if that fails.
     */
    suspend fun restore(opened: OpenedBackup, plan: MergePlan, o: RestoreOptions): RestoreReport = withContext(Dispatchers.IO + NonCancellable) {
        var r = RestoreReport()
        val skipped = ArrayList<String>()
        if (o.contacts && plan.mode == RestoreMode.REPLACE && plan.toDelete.isNotEmpty()) {
            val safety = backupNow(scheduled = false, safety = true)
            if (!safety.ok) return@withContext RestoreReport(error = "Nothing was changed: the safety backup of your current contacts failed (${safety.message})")
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
            news.chunked(200).forEach { chunk ->
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
                val n = addRows(existing, rows)
                if (n > 0) r = r.copy(enriched = r.enriched + 1, rowsAdded = r.rowsAdded + n)
            }
        } catch (e: Exception) {
            skipped += "some contacts (${e.message ?: e.javaClass.simpleName})"
        }
        suspend fun part(name: String, block: suspend () -> Unit) = try {
            block()
        } catch (e: Exception) {
            skipped += name
        }
        if (o.callLog) part("call history") {
            r = r.copy(calls = restoreCallLog(opened))
            val h = callHistory
            val archived = if (h != null) opened.reader.callHistory { it.toList() } else null
            if (h != null && archived != null) r = r.copy(calls = r.calls + h.restoreLines(archived))
        }
        if (o.blocking) part("blocking rules") { r = r.copy(rules = restoreBlocking(opened)) }
        if (o.speedDial) part("speed dial") {
            opened.reader.speedDial()?.forEach { prefsRepo.setSpeedDial(it.slot, it.number, it.label) }
            opened.reader.numberSims()?.forEach { db.prefsDao().setSim(app.parley.data.db.NumberSimEntity(it.matchKey, it.phoneAccountId)) }
        }
        if (o.settings) part("settings") {
            opened.reader.settings()?.let { all ->
                settings.importMap(all.filterKeys { !it.startsWith(BackupExtras.PREFIX) })
                val x = all.filterKeys { it.startsWith(BackupExtras.PREFIX) }
                if (x.isNotEmpty()) extras().forEach { it.import(x) }
            }
        }
        if (o.vault) try {
            r = r.copy(vault = restoreVault(opened))
        } catch (e: VaultCrypto.LockedException) {
            skipped += "private contacts (unlock them and restore again)"
        } catch (e: Exception) {
            skipped += "private contacts"
        }
        contacts.refresh()
        r.copy(skipped = skipped)
    }

    /** Removes the raw contacts the last restore added (existing contacts they joined keep their own entries). */
    suspend fun undoLastRestore(): Int = withContext(Dispatchers.IO + NonCancellable) {
        val ids = prefs.state.value.lastRestoreIds
        ids.chunked(200).forEach { chunk ->
            val ops = chunk.map { ContentProviderOperation.newDelete(android.content.ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, it)).build() }
            runCatching { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops)) }
        }
        prefs.update { it.putString("restoreRawIds", "") }
        contacts.refresh()
        ids.size
    }

    private fun idForKey(key: String): Long? = runCatching {
        ContactsContract.Contacts.lookupContact(cr, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, key))?.let { android.content.ContentUris.parseId(it) }
    }.getOrNull()

    /** Adds rows (never removes) to the first writable raw contact of [existing]. */
    private fun addRows(existing: ContactRecord, rows: List<DataRow>): Int {
        val id = idForKey(existing.key) ?: return 0
        val raw = cr.query(ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0", arrayOf(id.toString()), null)?.use { c ->
            var pick: Long? = null
            while (c.moveToNext()) {
                val type = c.getString(1)
                if (type == null || type == "com.google" || !app.parley.common.record.Messengers.isMessengerAccount(type)) { pick = c.getLong(0); break }
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
        val existing = HashSet<String>()
        runCatching {
            cr.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) existing += "${c.getString(0)}|${c.getLong(1)}|${c.getLong(2)}|${c.getInt(3)}"
            }
        }
        var n = 0
        opened.reader.callLog { seq ->
            seq.filter { "${it.number}|${it.date}|${it.duration}|${it.type}" !in existing }.chunked(200).forEach { chunk ->
                val values = chunk.map { rec ->
                    ContentValues().apply {
                        put(CallLog.Calls.NUMBER, rec.number)
                        put(CallLog.Calls.DATE, rec.date)
                        put(CallLog.Calls.DURATION, rec.duration)
                        put(CallLog.Calls.TYPE, rec.type)
                        put(CallLog.Calls.NUMBER_PRESENTATION, rec.presentation)
                        put(CallLog.Calls.PHONE_ACCOUNT_ID, rec.accountId)
                        put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, rec.accountComponent)
                        put(CallLog.Calls.CACHED_NAME, rec.name)
                        put(CallLog.Calls.NEW, if (rec.isNew) 1 else 0)
                        put(CallLog.Calls.IS_READ, if (rec.isRead) 1 else 0)
                    }
                }.toTypedArray()
                n += runCatching { cr.bulkInsert(CallLog.Calls.CONTENT_URI, values) }.getOrDefault(0)
            }
        }
        return n
    }

    private suspend fun restoreBlocking(opened: OpenedBackup): Int {
        val snap = opened.reader.blocking() ?: return 0
        val existing = blocks.rules.value.map { it.kind.name + "|" + it.pattern + "|" + it.type.name }.toSet()
        var n = 0
        snap.rules.filter { it.kind + "|" + it.pattern + "|" + it.type !in existing }.forEach { r ->
            blocks.saveRule(
                BlockRule(
                    pattern = r.pattern,
                    type = runCatching { RuleType.valueOf(r.type) }.getOrDefault(RuleType.EXACT),
                    action = runCatching { BlockAction.valueOf(r.action) }.getOrDefault(BlockAction.REJECT),
                    enabled = r.enabled, note = r.note,
                    kind = runCatching { app.parley.common.RuleKind.valueOf(r.kind) }.getOrDefault(app.parley.common.RuleKind.BLOCK),
                    simId = r.simId, schedule = app.parley.common.Schedule.decode(r.schedule),
                    notify = runCatching { app.parley.common.NotifyLevel.valueOf(r.notify) }.getOrDefault(app.parley.common.NotifyLevel.DEFAULT),
                    ringtone = r.ringtone, expiresAt = r.expiresAt, label = r.label,
                ),
            )
            n++
        }
        snap.systemBlockedNumbers.forEach { blocks.blockNumber(it) }
        return n
    }

    private suspend fun restoreVault(opened: OpenedBackup): Int {
        val blob = opened.reader.vault()["vault.json"] ?: return 0
        val arr = JSONObject(String(blob)).optJSONArray("contacts") ?: return 0
        val have = vault.contacts.value.map { it.name to it.numbers.toSet() }.toSet()
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = ContactDetailsJson.decode(o.getString("details"))
            val sig = d.displayName to d.phones.map { it.value }.toSet()
            if (sig in have) continue
            vault.save(null, d, o.optLong("expiresAt").takeIf { it > 0 })
            n++
        }
        return n
    }
}
