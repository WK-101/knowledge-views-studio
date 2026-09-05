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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val feedRepository: FeedRepository,
    private val preferencesRepository: PreferencesRepository,
    private val backupManager: BackupManager,
    private val blobStore: BlobStore,
    @ApplicationContext private val context: Context,
    highlightRepository: HighlightRepository,
) : ViewModel() {

    val sources: StateFlow<List<SourceEntity>> =
        sourceRepository.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val highlightCount: StateFlow<Int> =
        highlightRepository.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val preferences: StateFlow<AppPreferences> =
        preferencesRepository.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    val folders: StateFlow<List<String>> =
        sourceRepository.folders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeSource(id: String) = viewModelScope.launch { sourceRepository.delete(id) }
    fun syncNow() = viewModelScope.launch { runCatching { feedRepository.syncAll() } }

    fun setFolder(id: String, folder: String?) = viewModelScope.launch { sourceRepository.setFolder(id, folder) }
    fun setFullText(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setFullText(id, enabled) }
    fun setNotify(id: String, enabled: Boolean) = viewModelScope.launch { sourceRepository.setNotify(id, enabled) }

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

    fun importBackup(text: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val summary = runCatching { backupManager.import(text) }.getOrElse { "Couldn't read that backup file" }
        onResult(summary)
    }

    fun addBlockedKeyword(term: String) = viewModelScope.launch { preferencesRepository.addBlockedKeyword(term) }
    fun removeBlockedKeyword(term: String) = viewModelScope.launch { preferencesRepository.removeBlockedKeyword(term) }
    fun setHideDuplicates(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setHideDuplicates(enabled) }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setDynamicColor(enabled) }
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
        // Re-schedule the background sync so the new network constraint takes effect immediately.
        CairnWork.schedulePeriodicSync(context, enabled)
    }

    fun setCacheImagesOffline(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setCacheImagesOffline(enabled) }
    fun setImagesWifiOnly(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setImagesWifiOnly(enabled) }
    fun setMaxItemsPerFeed(max: Int) = viewModelScope.launch { preferencesRepository.setMaxItemsPerFeed(max) }
    fun setMaxAgeDays(days: Int) = viewModelScope.launch { preferencesRepository.setMaxAgeDays(days) }

    fun setBottomTab(name: String, enabled: Boolean) = viewModelScope.launch { preferencesRepository.setBottomTab(name, enabled) }

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

    /** Bytes on disk used by cached article bodies, offline images, and imported PDFs. */
    suspend fun storageBytes(): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { blobStore.storageBytes() }
}
