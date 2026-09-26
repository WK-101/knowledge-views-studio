package com.wkhan.hexis.ui

import android.net.Uri
import com.wkhan.hexis.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3, Stage 7 — the dedicated home for the **backup / export / import / sync** surface (JSON / Markdown /
 * CSV / ICS / habit-CSV export, passphrase-encrypted backup, SAF-free Downloads export, share-a-copy, folder
 * sync + account-free restore/import, and the auto-backup scheduling settings), lifted out of the 5.5k-line
 * [AppViewModel] following the exact collaborator pattern already proven by [TimeTrackingViewModel],
 * [NotesViewModel], [HabitsViewModel] and [GoalsRoutinesViewModel]: a plain class (not a `ViewModel`) that the
 * AppViewModel constructs once and drives with its own `viewModelScope`, so there is no second lifecycle.
 *
 * It OWNS the two data-layer collaborators [backup] (`BackupExporter`) and [restore] (`RestoreManager`) — both
 * previously private-with-no-external-callers on the parent, so they carry over cleanly — plus the thin
 * threading/UI-glue wrappers around them and the auto-backup/sync settings setters. It reaches back to the
 * parent only for the shared, cross-feature bits those data collaborators need: `app.appCtx`, `app.settings`,
 * `app.toast`, `app.zoneId`, `app.lists`, `app.displayNameOf` and the shared bounded-reader
 * `app.readImportTextBounded` (kept on the parent because three non-backup importers also use it). AppViewModel
 * keeps thin forwarding shims (`fun exportTo(uri, cb) = backupSyncVm.exportTo(uri, cb)`) so every existing
 * backup/settings call site across the Settings + import/export screens needs no edits.
 *
 * Deliberately left on the coordinating parent: the feature-specific importers that merely *look* backup-ish
 * (calendar `.ics`/vCard import, the note "courier" passphrase export/import) but belong to their own features;
 * the cross-feature read models `dataCounts` / `deviceInventory`; the DB-encryption (SecureDb) cluster; the
 * reminder-scheduling `setMorningBrief`; and the scattered app-level plain settings setters (sidebar, modules,
 * onboarding, muting, reduce-motion…), which are trivial one-liners whose move would be net-zero.
 */
class BackupSyncViewModel(
    private val app: AppViewModel,
    private val scope: CoroutineScope,
    private val repo: AppRepository,
) {
    private val zone: java.time.ZoneId get() = app.zoneId

    // R75 — the file I/O lives in a standalone, unit-testable BackupExporter (context + repo + zone, no UI
    // state). These wrappers keep only the threading and the UI glue (settings stamp, widget refreshes,
    // user-facing messages), so behaviour is unchanged.
    private val backup by lazy { com.wkhan.hexis.data.backup.BackupExporter(app.appCtx, repo) { zone } }

    fun exportTo(uri: Uri, onDone: (Boolean) -> Unit) = scope.launch { onDone(backup.exportJson(uri)) }

    // Wave O — device-independent passphrase-encrypted backup (PBKDF2 + AES-GCM). Restores on any device from
    // the passphrase alone, unlike the Keystore-bound SQLCipher DB. Fully local — no network.
    fun exportEncryptedBackup(uri: Uri, passphrase: String, onDone: (Boolean) -> Unit) = scope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val jsonStr = repo.exportJson()
                val blob = com.wkhan.hexis.util.PortableCrypto.encrypt(jsonStr, passphrase.toCharArray())
                app.appCtx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob.toByteArray()) }
                true
            }.getOrDefault(false)
        }
        onDone(ok)
    }
    fun importEncryptedBackup(uri: Uri, passphrase: String, onDone: (Boolean, String) -> Unit) = scope.launch {
        val text = withContext(Dispatchers.IO) { runCatching { app.readImportTextBounded(uri) }.getOrNull() }
        if (text == null) { onDone(false, "Couldn't read the file"); return@launch }
        val plain = com.wkhan.hexis.util.PortableCrypto.decrypt(text, passphrase.toCharArray())
        if (plain == null) { onDone(false, "Wrong passphrase or damaged backup"); return@launch }
        withContext(Dispatchers.IO) { repo.importJsonMerge(plain) }
        onDone(true, "Restored from encrypted backup")
    }
    fun exportMarkdownTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = scope.launch { onDone(backup.exportMarkdown(uri, includeCompleted)) }
    fun exportCsvTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = scope.launch { onDone(backup.exportCsv(uri, includeCompleted)) }
    fun exportIcsTo(uri: Uri, includeCompleted: Boolean, onDone: (Boolean) -> Unit) = scope.launch { onDone(backup.exportIcs(uri, includeCompleted)) }
    fun exportHabitsCsvTo(uri: Uri, onDone: (Boolean) -> Unit) = scope.launch { onDone(backup.exportHabitsCsv(uri)) }

    /**
     * SAF-free export fallback: write the chosen export straight into the public Downloads folder (or the
     * app's files dir on older devices). Used when the device has no system document picker. [onDone] receives
     * a user-facing location like "Downloads/todo-companion-backup.json", or null.
     */
    fun exportToDownloads(kind: String, onDone: (String?) -> Unit) = scope.launch {
        val loc = backup.downloadExport(kind)
        // U10: a successful full backup stamps the "last backup" time the Momentum data-safety card reads.
        if (kind == "json" && loc != null) repo.saveSettings(app.settings.value.copy(lastBackupAt = System.currentTimeMillis(), lastSyncAt = System.currentTimeMillis()))
        onDone(loc)
    }
    fun importHabitsCsv(uri: Uri, onDone: (Boolean, String) -> Unit) = scope.launch {
        val n = backup.importHabitsCsv(uri)
        com.wkhan.hexis.reminders.AlarmScheduler.scheduleHabitReminders(app.appCtx, repo)
        com.wkhan.hexis.widget.HabitsWidget.refresh(app.appCtx)
        when { n < 0 -> onDone(false, "Couldn't read that CSV — export from Loop, or our habit CSV"); n == 0 -> onDone(false, "No check-ins found in that file"); else -> onDone(true, "Imported $n habit check-ins") }
    }

    // ── CU3 · import an .ics calendar into tasks (the other half of the 2-way bridge) ──────────────
    fun importIcs(uri: Uri, onDone: (Boolean, String) -> Unit) = scope.launch {
        val n = backup.importIcsAsTasks(uri)
        when { n < 0 -> onDone(false, "Couldn't read that file"); n == 0 -> onDone(false, "No events found in that .ics"); else -> onDone(true, "Imported $n event${if (n == 1) "" else "s"} as tasks") }
    }

    // ── CU4 · one-tap handoff — share a full copy through the system share sheet (0 permission) ────
    fun shareBackupCopy(onDone: (Boolean) -> Unit = {}) = scope.launch {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val json = repo.exportJson()
                val dir = java.io.File(app.appCtx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, "modular-backup-${java.time.LocalDate.now(zone)}.json").apply { writeText(json) }
                androidx.core.content.FileProvider.getUriForFile(app.appCtx, "${app.appCtx.packageName}.fileprovider", f)
            }.getOrNull()
        }
        if (uri == null) { app.toast("Couldn't prepare the copy"); onDone(false); return@launch }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(send, "Send a copy to another device").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.appCtx.startActivity(chooser) }.onFailure { app.toast("No app to share with") }
        onDone(true)
    }

    // ── Tier D: folder backup & account-free sync ──────────────────────────────────────────────────
    // R84 — sync + every restore/import path lives in data.backup/RestoreManager (the most data-sensitive
    // corner: a restore overwrites the whole store). This collaborator keeps the settings setters below and
    // thin scope wrappers; behaviour is identical.
    private val restore by lazy {
        com.wkhan.hexis.data.backup.RestoreManager(
            app.appCtx, repo,
            settings = { app.settings.value },
            saveSettings = { repo.saveSettings(it) },
            listsSnapshot = { app.lists.value },
            displayNameOf = { app.displayNameOf(it) },
        )
    }
    fun setSyncFolder(uri: String) = scope.launch { repo.saveSettings(app.settings.value.copy(syncFolder = uri, syncEnabled = uri.isNotBlank())) }
    /** Re-arm (or cancel) the auto-backup alarm from the latest settings, so enabling/retiming takes effect
     *  immediately rather than waiting for the next app launch or boot. */
    private fun rearmAutoBackup(s: com.wkhan.hexis.domain.AppSettings) {
        if (s.autoBackupEnabled && s.autoBackupFolder.ifBlank { s.syncFolder }.isNotBlank())
            com.wkhan.hexis.reminders.AlarmScheduler.scheduleAutoBackup(app.appCtx, s.autoBackupHour, s.autoBackupIntervalDays, s.lastBackupAt, s.autoBackupDow, s.autoBackupDom)
        else com.wkhan.hexis.reminders.AlarmScheduler.cancelAutoBackup(app.appCtx)
    }
    fun setAutoBackupFolder(uri: String) = scope.launch {
        val s = app.settings.value.copy(autoBackupFolder = uri, autoBackupEnabled = uri.isNotBlank()); repo.saveSettings(s); rearmAutoBackup(s)
    }
    fun setAutoBackupEnabled(on: Boolean) = scope.launch {
        val s = app.settings.value.copy(autoBackupEnabled = on); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** How often the automatic backup runs (days: 1 daily · 7 weekly · 30 monthly). */
    fun setAutoBackupInterval(days: Int) = scope.launch {
        val s = app.settings.value.copy(autoBackupIntervalDays = days.coerceIn(1, 30)); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** The hour of day (0–23) the automatic backup fires. */
    fun setAutoBackupHour(hour: Int) = scope.launch {
        val s = app.settings.value.copy(autoBackupHour = hour.coerceIn(0, 23)); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** For weekly backups: which ISO weekday to run on (1 = Mon … 7 = Sun). */
    fun setAutoBackupDow(dow: Int) = scope.launch {
        val s = app.settings.value.copy(autoBackupDow = dow.coerceIn(0, 7)); repo.saveSettings(s); rearmAutoBackup(s)
    }
    /** For monthly backups: which day-of-month to run on (1–31, clamped to the month's length). */
    fun setAutoBackupDom(dom: Int) = scope.launch {
        val s = app.settings.value.copy(autoBackupDom = dom.coerceIn(1, 31)); repo.saveSettings(s); rearmAutoBackup(s)
    }
    fun setSyncEnabled(on: Boolean) = scope.launch { repo.saveSettings(app.settings.value.copy(syncEnabled = on)) }
    fun setSyncPassphrase(pass: String) = scope.launch { repo.saveSettings(app.settings.value.copy(syncPassphrase = pass)) }

    fun runSyncNow(onDone: (Boolean, String) -> Unit) = scope.launch { restore.runSyncNow(onDone) }
    fun runBackupNow(onDone: (Boolean) -> Unit) = scope.launch { restore.runBackupNow(onDone) }
    /** Import tasks from a Todoist/TickTick CSV or MLO OPML/.mlobak file. Returns (ok, message). */
    fun importExternal(uri: Uri, onDone: (Boolean, String) -> Unit) = scope.launch { restore.importExternal(uri, onDone) }
    fun loadSavedBackups(broad: Boolean = false, onDone: (List<com.wkhan.hexis.util.FileExport.SavedFile>) -> Unit) = scope.launch { onDone(restore.loadSavedBackups(broad)) }
    fun importInboxHint(): String = restore.importInboxHint()
    fun importPastedText(text: String, onDone: (Boolean, String) -> Unit) = scope.launch { restore.importPastedText(text, onDone) }
    fun restoreSaved(s: com.wkhan.hexis.util.FileExport.SavedFile, onDone: (Boolean, String) -> Unit) = scope.launch { restore.restoreSaved(s, onDone) }
    fun importFromIntent(uri: Uri, merge: Boolean = false, onDone: (Boolean, String) -> Unit) = scope.launch { restore.importFromIntent(uri, merge, onDone) }
    fun importFrom(uri: Uri, onDone: (Boolean) -> Unit) = scope.launch { restore.importFrom(uri, onDone) }
}
