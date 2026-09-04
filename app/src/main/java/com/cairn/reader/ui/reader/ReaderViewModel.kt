package com.cairn.reader.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.data.prefs.AppPreferences
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import com.cairn.reader.data.repo.ReaderData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val loading: Boolean = true,
    val extracting: Boolean = false,
    val data: ReaderData? = null,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val feedRepository: FeedRepository,
    private val preferencesRepository: PreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId").orEmpty()

    private val _state = MutableStateFlow(ReaderUiState())
    val state = _state.asStateFlow()

    val preferences: StateFlow<AppPreferences> =
        preferencesRepository.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    init {
        viewModelScope.launch {
            val data = itemRepository.reader(itemId)
            _state.value = ReaderUiState(loading = false, data = data)
            if (data != null) itemRepository.setRead(itemId, true)
        }
    }

    fun loadFullArticle() {
        viewModelScope.launch {
            _state.update { it.copy(extracting = true) }
            feedRepository.extractFull(itemId)
            val data = itemRepository.reader(itemId)
            _state.value = ReaderUiState(loading = false, extracting = false, data = data)
        }
    }

    fun toggleStar() {
        val current = _state.value.data ?: return
        viewModelScope.launch {
            itemRepository.setStarred(itemId, !current.isStarred)
            _state.update { it.copy(data = it.data?.copy(isStarred = !current.isStarred)) }
        }
    }

    fun toggleSave() {
        val current = _state.value.data ?: return
        viewModelScope.launch {
            itemRepository.setReadLater(itemId, !current.isReadLater)
            _state.update { it.copy(data = it.data?.copy(isReadLater = !current.isReadLater)) }
        }
    }

    fun setProgress(progress: Float) {
        if (itemId.isEmpty()) return
        viewModelScope.launch { itemRepository.setProgress(itemId, progress) }
    }

    fun setFontScale(scale: Float) = viewModelScope.launch { preferencesRepository.setReaderFontScale(scale) }
    fun setReaderTheme(theme: ReaderTheme) = viewModelScope.launch { preferencesRepository.setReaderTheme(theme) }
    fun setReaderFont(font: ReaderFont) = viewModelScope.launch { preferencesRepository.setReaderFont(font) }
}
