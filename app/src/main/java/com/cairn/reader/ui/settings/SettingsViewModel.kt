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
) : ViewModel() {

    val sources: StateFlow<List<SourceEntity>> =
        sourceRepository.sources().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val preferences: StateFlow<AppPreferences> =
        preferencesRepository.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    fun removeSource(id: String) = viewModelScope.launch { sourceRepository.delete(id) }
    fun syncNow() = viewModelScope.launch { runCatching { feedRepository.syncAll() } }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { preferencesRepository.setDynamicColor(enabled) }
    fun setReaderFont(font: ReaderFont) = viewModelScope.launch { preferencesRepository.setReaderFont(font) }
    fun setReaderTheme(theme: ReaderTheme) = viewModelScope.launch { preferencesRepository.setReaderTheme(theme) }
}
