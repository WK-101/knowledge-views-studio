package com.cairn.reader.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.audio.TtsReader
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.prefs.AppPreferences
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.HighlightRepository
import com.cairn.reader.data.repo.ItemRepository
import com.cairn.reader.data.repo.ReaderData
import dagger.hilt.android.lifecycle.HiltViewModel
import org.jsoup.Jsoup
import java.text.BreakIterator
import java.util.Locale
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
    private val highlightRepository: HighlightRepository,
    private val ttsReader: TtsReader,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId").orEmpty()

    val tts: StateFlow<TtsReader.State> = ttsReader.state

    private val _state = MutableStateFlow(ReaderUiState())
    val state = _state.asStateFlow()

    val preferences: StateFlow<AppPreferences> =
        preferencesRepository.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    val highlights: StateFlow<List<HighlightEntity>> =
        highlightRepository.observeForItem(itemId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun addHighlight(blockIndex: Int, start: Int, end: Int, quote: String, color: Int = HighlightColors.Default) {
        if (itemId.isEmpty() || quote.isBlank()) return
        viewModelScope.launch { highlightRepository.add(itemId, blockIndex, start, end, quote, color) }
    }

    fun setHighlightNote(id: String, note: String?) = viewModelScope.launch { highlightRepository.setNote(id, itemId, note) }
    fun setHighlightColor(id: String, color: Int) = viewModelScope.launch { highlightRepository.setColor(id, itemId, color) }
    fun removeHighlight(id: String) = viewModelScope.launch { highlightRepository.remove(id, itemId) }

    /** Builds the shareable Markdown for this article's highlights off the main thread. */
    fun exportHighlights(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(highlightRepository.exportItem(itemId)) }
    }

    // -- Read aloud -----------------------------------------------------------

    fun toggleListen() {
        if (tts.value.active) {
            ttsReader.togglePlayPause()
        } else {
            val data = _state.value.data ?: return
            ttsReader.start(buildSpeechChunks(data))
        }
    }

    fun stopListen() = ttsReader.stop()
    fun setListenSpeed(speed: Float) = ttsReader.setSpeed(speed)

    private fun buildSpeechChunks(data: ReaderData): List<String> {
        val chunks = ArrayList<String>()
        data.title.takeIf { it.isNotBlank() }?.let { chunks += it }
        data.html?.let { html ->
            val text = runCatching { Jsoup.parse(html).text() }.getOrDefault("")
            chunks += splitSentences(text)
        }
        return chunks
    }

    private fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(text)
        val out = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            start = end
            end = iterator.next()
        }
        return out
    }

    override fun onCleared() {
        ttsReader.stop()
    }
}
