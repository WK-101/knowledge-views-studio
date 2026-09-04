package com.cairn.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.db.SourceEntity
import com.cairn.reader.data.prefs.AppPreferences
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.prefs.ThemeMode
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.HighlightRepository
import com.cairn.reader.data.repo.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sourceRepository: SourceRepository,
    private val feedRepository: FeedRepository,
    private val preferencesRepository: PreferencesRepository,
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

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setDynamicColor(enabled) }
    fun setReaderFont(font: ReaderFont) = viewModelScope.launch { preferencesRepository.setReaderFont(font) }
    fun setReaderTheme(theme: ReaderTheme) = viewModelScope.launch { preferencesRepository.setReaderTheme(theme) }
}
