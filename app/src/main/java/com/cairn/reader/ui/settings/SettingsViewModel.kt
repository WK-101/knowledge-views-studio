package com.cairn.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.prefs.AppPreferences
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.prefs.SwipeAction
import com.cairn.reader.data.prefs.ThemeMode
import android.content.Context
import com.cairn.reader.data.backup.BackupManager
import com.cairn.reader.data.blob.BlobStore
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.HighlightRepository
import com.cairn.reader.data.repo.SourceRepository
import com.cairn.reader.work.CairnWork
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val feedRepository: FeedRepository,
    private val preferencesRepository: PreferencesRepository,
    private val backupManager: BackupManager,
    private val markdownExportManager: com.cairn.reader.data.export.MarkdownExportManager,
    private val ebookExportManager: com.cairn.reader.data.export.EbookExportManager,
    private val blobStore: BlobStore,
    private val storageManager: com.cairn.reader.data.blob.StorageManager,
    private val bookmarkImporter: com.cairn.reader.domain.importer.BookmarkImporter,
    @ApplicationContext private val context: Context,
    highlightRepository: HighlightRepository,
    ruleRepository: com.cairn.reader.data.repo.RuleRepository,
    itemRepository: com.cairn.reader.data.repo.ItemRepository,
) : ViewModel() {

    val sources: StateFlow<List<SourceEntity>> =
        sourceRepository.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val highlightCount: StateFlow<Int> =
        highlightRepository.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Total articles held on this device — the "your data is safe here" figure. */
    val savedCount: StateFlow<Int> =
        itemRepository.libraryCounts().map { it.allCount }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** How many automation rules are enabled — shown as the Rules row subtitle. */
    val ruleCount: StateFlow<Int> =
        ruleRepository.observeRules().map { rules -> rules.count { it.enabled } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val preferences: StateFlow<AppPreferences> =
        preferencesRepository.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    val folders: StateFlow<List<String>> =
        sourceRepository.folders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeSource(id: String) = viewModelScope.launch { sourceRepository.delete(id) }
    fun syncNow() = viewModelScope.launch { runCatching { feedRepository.syncAll() } }

    fun setFolder(id: String, folder: String?) = viewModelScope.launch { sourceRepository.setFolder(id, folder) }
    fun setFullText(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setFullText(id, enabled) }
    fun setNotify(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setNotify(id, enabled) }
    fun setMuted(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setMuted(id, enabled) }

    fun importOpml(text: String, onResult: (Int) -> Unit) = viewModelScope.launch {
        val added = runCatching { feedRepository.importOpml(text) }.getOrDefault(0)
        onResult(added)
        if (added > 0) runCatching { feedRepository.syncAll() }
    }

    fun exportOpml(onReady: (String) -> Unit) = viewModelScope.launch { onReady(feedRepository.exportOpml()) }

    fun importPdf(name: String, bytes: ByteArray, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching { feedRepository.importPdf(name, bytes) }.getOrNull()?.isSuccess == true
        onResult(ok)
    }

    fun exportBackup(onReady: (String) -> Unit) = viewModelScope.launch { onReady(backupManager.export()) }

    /** Build a spreadsheet-friendly CSV of every item and hand it back to be written to a file. */
    fun exportCsv(onReady: (String) -> Unit) = viewModelScope.launch { onReady(backupManager.exportCsv()) }

    /** Fetch og:image thumbnails for items that arrived without one. Reports how many were filled. */
    fun backfillThumbnails(onDone: (Int) -> Unit) = viewModelScope.launch {
        onDone(runCatching { feedRepository.backfillThumbnails() }.getOrDefault(0))
    }

    fun importBackup(text: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val summary = runCatching { backupManager.import(text) }.getOrElse { "Couldn't read that backup file" }
        onResult(summary)
    }

    /** Write a full `.zip` archive (data + offline copies) to a document the user picked. */
    fun exportArchive(uri: android.net.Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { backupManager.exportArchive(it) } ?: error("no output stream")
        }.isSuccess
        onDone(ok)
    }

    /** Export the whole curated library as Markdown files into a folder the user picked (an
     *  Obsidian/Logseq vault). Reports how many files were written. */
    fun exportMarkdownVault(treeUri: android.net.Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        val summary = runCatching {
            val r = markdownExportManager.exportVault(treeUri)
            when {
                r.written == 0 && r.failed == 0 -> "Nothing to export — save some articles to your library first."
                r.failed == 0 -> "Exported ${r.written} article${if (r.written == 1) "" else "s"} as Markdown."
                else -> "Exported ${r.written}, skipped ${r.failed}."
            }
        }.getOrElse { "Couldn't write to that folder" }
        onDone(summary)
    }

    /** Build one EPUB of the whole curated library and hand back the file to share (Send to Kindle). */
    fun exportLibraryEpub(onReady: (java.io.File?) -> Unit) = viewModelScope.launch {
        onReady(runCatching { ebookExportManager.epubForLibrary() }.getOrNull())
    }

    /** Restore from a file the user picked — auto-detecting a `.zip` archive vs a `.json` data backup. */
    fun importFrom(uri: android.net.Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        val summary = runCatching {
            val name = queryDisplayName(uri).orEmpty().lowercase()
            val isZip = name.endsWith(".zip") || firstBytesAreZip(uri)
            if (isZip) {
                context.contentResolver.openInputStream(uri)?.use { backupManager.importArchive(it) } ?: error("no stream")
            } else {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("no stream")
                backupManager.import(text)
            }
        }.getOrElse { "Couldn't read that backup" }
        onResult(summary)
    }

    /**
     * Account-free device-to-device transfer: write a full self-contained archive (data + offline
     * copies) to a shareable file and hand back its content:// URI, so it can be sent to another
     * device over Quick Share / Nearby / Bluetooth / any share target. The other device restores it
     * with the ordinary Restore flow. Nothing goes through a server.
     */
    fun transferToDevice(onReady: (android.net.Uri?) -> Unit) = viewModelScope.launch {
        val uri = runCatching {
            val dir = java.io.File(context.cacheDir, "media").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            val file = java.io.File(dir, "cairn-transfer-$stamp.zip")
            file.outputStream().use { backupManager.exportArchive(it) }
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull()
        onReady(uri)
    }

    /** Import a Pocket / Instapaper / Raindrop export (HTML or CSV) as Read Later items. */
    fun importBookmarks(uri: android.net.Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        val report = runCatching {
            val name = queryDisplayName(uri)
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("no stream")
            bookmarkImporter.import(name, text)
        }.getOrNull()
        onResult(report?.message ?: "Couldn't read that file")
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Peek the magic bytes so a renamed / extension-less pick is still detected as a zip ("PK"). */
    private fun firstBytesAreZip(uri: android.net.Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
        } ?: false
    }.getOrDefault(false)

    fun setBackupIncludeOffline(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setBackupIncludeOffline(enabled) }

    fun addBlockedKeyword(term: String) = viewModelScope.launch { preferencesRepository.addBlockedKeyword(term) }
    fun removeBlockedKeyword(term: String) = viewModelScope.launch { preferencesRepository.removeBlockedKeyword(term) }
    fun setHideDuplicates(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setHideDuplicates(enabled) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setDynamicColor(enabled) }
    fun setAppAccent(name: String) = viewModelScope.launch { preferencesRepository.setAppAccent(name) }
    fun setAppSeedColor(argb: Int) = viewModelScope.launch { preferencesRepository.setAppSeedColor(argb) }
    fun setTrueBlack(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setTrueBlack(enabled) }
    fun setReaderFont(font: ReaderFont) = viewModelScope.launch { preferencesRepository.setReaderFont(font) }
    fun setReaderTheme(theme: ReaderTheme) = viewModelScope.launch { preferencesRepository.setReaderTheme(theme) }
    fun setSwipeRight(action: SwipeAction) = viewModelScope.launch { preferencesRepository.setSwipeRight(action) }
    fun setSwipeLeft(action: SwipeAction) = viewModelScope.launch { preferencesRepository.setSwipeLeft(action) }
    fun setSwipeRightHalf(action: SwipeAction) = viewModelScope.launch { preferencesRepository.setSwipeRightHalf(action) }
    fun setSwipeRightFull(action: SwipeAction) = viewModelScope.launch { preferencesRepository.setSwipeRightFull(action) }
    fun setSwipeLeftHalf(action: SwipeAction) = viewModelScope.launch { preferencesRepository.setSwipeLeftHalf(action) }
    fun setSwipeLeftFull(action: SwipeAction) = viewModelScope.launch { preferencesRepository.setSwipeLeftFull(action) }
    fun setCompactDensity(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setCompactDensity(enabled) }
    fun setReaderJustify(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setReaderJustify(enabled) }
    fun setReaderFontScale(scale: Float) = viewModelScope.launch { preferencesRepository.setReaderFontScale(scale) }
    fun setReaderShowImages(show: Boolean) = viewModelScope.launch { preferencesRepository.setReaderShowImages(show) }
    fun setReaderImmersive(on: Boolean) = viewModelScope.launch { preferencesRepository.setReaderImmersive(on) }
    fun setReaderFullScreen(on: Boolean) = viewModelScope.launch { preferencesRepository.setReaderFullScreen(on) }

    // -- Offline & storage policy ---------------------------------------------

    fun setSyncWifiOnly(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setSyncWifiOnly(enabled)
        rescheduleSync()
    }

    fun setSyncChargingOnly(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setSyncChargingOnly(enabled)
        rescheduleSync()
    }

    fun setSyncIntervalMinutes(minutes: Int) = viewModelScope.launch {
        preferencesRepository.setSyncIntervalMinutes(minutes)
        rescheduleSync()
    }

    /** Re-schedule background sync so a changed network / charging / interval preference applies now. */
    private suspend fun rescheduleSync() {
        val p = preferencesRepository.preferences.first()
        CairnWork.schedulePeriodicSync(context, p.syncWifiOnly, p.syncChargingOnly, p.syncIntervalMinutes)
    }

    fun setCacheImagesOffline(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setCacheImagesOffline(enabled) }
    fun setCacheOnOpen(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setCacheOnOpen(enabled) }
    fun setImagesWifiOnly(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setImagesWifiOnly(enabled) }
    fun setMaxItemsPerFeed(max: Int) = viewModelScope.launch { preferencesRepository.setMaxItemsPerFeed(max) }
    fun setMaxAgeDays(days: Int) = viewModelScope.launch { preferencesRepository.setMaxAgeDays(days) }
    fun setKeepUnread(on: Boolean) = viewModelScope.launch { preferencesRepository.setKeepUnread(on) }

    fun setBottomTab(name: String, enabled: Boolean) = viewModelScope.launch { preferencesRepository.setBottomTab(name, enabled) }

    fun setTtsEnabled(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setTtsEnabled(enabled) }
    fun setStripTrackingParams(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setStripTrackingParams(enabled) }
    fun setLinkCheckEnabled(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setLinkCheckEnabled(enabled) }

    /** The on-device diagnostics log (Logcat mirror), for the "Share diagnostics" action. */
    fun diagnostics(onReady: (String) -> Unit) = viewModelScope.launch {
        val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.cairn.reader.util.AppLog.dump().ifBlank { "No diagnostics recorded yet." }
        }
        onReady(text)
    }
    fun setSanitizeArticles(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setSanitizeArticles(enabled) }
    fun setAutoOfflinePack(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setAutoOfflinePack(enabled) }
    fun setDailyBriefNotify(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setDailyBriefNotify(enabled)
        com.cairn.reader.work.CairnWork.scheduleDailyBrief(context, enabled)
    }
    fun setMarkReadOnScroll(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setMarkReadOnScroll(enabled) }
    fun setStartDestination(name: String) = viewModelScope.launch { preferencesRepository.setStartDestination(name) }
    fun setStartFilter(name: String) = viewModelScope.launch { preferencesRepository.setStartFilter(name) }
    fun setShowThumbnail(on: Boolean) = viewModelScope.launch { preferencesRepository.setShowThumbnail(on) }
    fun setShowExcerpt(on: Boolean) = viewModelScope.launch { preferencesRepository.setShowExcerpt(on) }
    fun setShowReadingTime(on: Boolean) = viewModelScope.launch { preferencesRepository.setShowReadingTime(on) }
    fun setStickyDateHeaders(on: Boolean) = viewModelScope.launch { preferencesRepository.setStickyDateHeaders(on) }
    fun setForceSingleColumn(on: Boolean) = viewModelScope.launch { preferencesRepository.setForceSingleColumn(on) }
    fun setTapZonePaging(on: Boolean) = viewModelScope.launch { preferencesRepository.setTapZonePaging(on) }
    fun setVolumeKeyPaging(on: Boolean) = viewModelScope.launch { preferencesRepository.setVolumeKeyPaging(on) }
    fun setOpenArticlesInWeb(on: Boolean) = viewModelScope.launch { preferencesRepository.setOpenArticlesInWeb(on) }
    fun setTrashRetentionDays(days: Int) = viewModelScope.launch { preferencesRepository.setTrashRetentionDays(days) }
    fun moveBottomTab(name: String, up: Boolean) = viewModelScope.launch { preferencesRepository.moveBottomTab(name, up) }

    /** Point automatic backups at a folder the user picked (SAF tree URI), default to daily, run one now. */
    fun setBackupFolder(uri: String) = viewModelScope.launch {
        preferencesRepository.setBackupFolder(uri)
        val current = preferencesRepository.preferences.first().backupFrequencyHours
        val hours = if (current <= 0) 24 else current
        preferencesRepository.setBackupFrequency(hours)
        CairnWork.scheduleBackup(context, hours)
        CairnWork.backupNow(context)
    }

    fun setBackupFrequency(hours: Int) = viewModelScope.launch {
        preferencesRepository.setBackupFrequency(hours)
        CairnWork.scheduleBackup(context, hours)
    }

    fun disableBackup() = viewModelScope.launch {
        preferencesRepository.setBackupFrequency(0)
        preferencesRepository.setBackupFolder(null)
        CairnWork.scheduleBackup(context, 0)
    }

    // -- WebDAV / Nextcloud mirror --------------------------------------------

    /** Save WebDAV credentials; when a server is set, ensure a schedule exists so it actually runs. */
    fun setWebDav(url: String, user: String, pass: String) = viewModelScope.launch {
        preferencesRepository.setWebDav(url, user, pass)
        if (url.isBlank()) return@launch
        val current = preferencesRepository.preferences.first().backupFrequencyHours
        if (current <= 0) {
            preferencesRepository.setBackupFrequency(24)
            CairnWork.scheduleBackup(context, 24)
        }
    }

    /** Test a WebDAV target the user typed, before saving it. */
    fun testWebDav(url: String, user: String, pass: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(backupManager.testWebDav(url, user, pass).isSuccess)
    }

    /** Push a backup to the configured WebDAV server right now. */
    fun backupToWebDavNow(onResult: (String) -> Unit) = viewModelScope.launch {
        val r = backupManager.backupToWebDav()
        onResult(r.fold({ "Uploaded $it to your server." }, { it.message ?: "Upload failed" }))
    }

    /** Pull and merge the latest backup from the configured WebDAV server. */
    fun restoreFromWebDav(onResult: (String) -> Unit) = viewModelScope.launch {
        val r = backupManager.restoreFromWebDav()
        onResult(r.getOrElse { it.message ?: "Restore failed" })
    }

    /** Bytes on disk used by cached article bodies, offline images, and imported PDFs. */
    suspend fun storageBytes(): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { blobStore.storageBytes() }

    /** Full storage breakdown for the Settings storage dashboard. */
    suspend fun storageBreakdown(): com.cairn.reader.data.blob.StorageManager.Breakdown = storageManager.breakdown()

    /** Reclaim space: delete orphaned blobs, clear the image cache, and compact the database. */
    fun optimizeStorage(onDone: (com.cairn.reader.data.blob.StorageManager.OptimizeResult) -> Unit) =
        viewModelScope.launch { onDone(storageManager.optimize()) }
}
