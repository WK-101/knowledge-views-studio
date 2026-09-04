package com.cairn.reader.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.audio.AudioPlayer
import com.cairn.reader.audio.TtsReader
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.prefs.AppPreferences
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.prefs.ReaderFont
import com.cairn.reader.data.prefs.ReaderTheme
import com.cairn.reader.data.db.CollectionWithCount
import com.cairn.reader.data.db.TagEntity
import com.cairn.reader.data.db.TagWithCount
import com.cairn.reader.data.repo.CollectionRepository
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.HighlightRepository
import com.cairn.reader.data.repo.ItemRepository
import com.cairn.reader.data.repo.ReaderData
import com.cairn.reader.data.repo.TagRepository
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
    private val collectionRepository: CollectionRepository,
    private val tagRepository: TagRepository,
    private val ttsReader: TtsReader,
    private val audioPlayer: AudioPlayer,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val itemId: String = savedStateHandle.get<String>("itemId").orEmpty()

    val tts: StateFlow<TtsReader.State> = ttsReader.state
    val audio: StateFlow<AudioPlayer.State> = audioPlayer.state

    val collections: StateFlow<List<CollectionWithCount>> =
        collectionRepository.collections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val itemTags: StateFlow<List<TagEntity>> =
        tagRepository.tagsForItem(itemId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags: StateFlow<List<TagWithCount>> =
        tagRepository.allWithCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addTag(name: String) = viewModelScope.launch { tagRepository.addToItem(itemId, name) }
    fun removeTag(tagId: String) = viewModelScope.launch { tagRepository.removeFromItem(itemId, tagId) }

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
            if (data != null) {
                itemRepository.setRead(itemId, true)
                // Automatically fetch the full article the first time it's opened, so RSS
                // items that only carry a summary read like the real thing — no button.
                // Feed content is shown immediately and swapped when extraction returns;
                // on failure the feed content stays and the status becomes FAILED.
                if (data.extractStatus == "NONE") {
                    _state.update { it.copy(extracting = true) }
                    feedRepository.extractFull(itemId)
                    _state.update { ReaderUiState(loading = false, extracting = false, data = itemRepository.reader(itemId)) }
                }
            }
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

    /** Mark this article unread again — pairs with the reader's UNREAD action. */
    fun markUnread() {
        if (itemId.isEmpty()) return
        viewModelScope.launch { itemRepository.setRead(itemId, false) }
    }

    fun setProgress(progress: Float) {
        if (itemId.isEmpty()) return
        viewModelScope.launch { itemRepository.setProgress(itemId, progress) }
    }

    fun setFontScale(scale: Float) = viewModelScope.launch { preferencesRepository.setReaderFontScale(scale) }
    fun setReaderTheme(theme: ReaderTheme) = viewModelScope.launch { preferencesRepository.setReaderTheme(theme) }
    fun setReaderFont(font: ReaderFont) = viewModelScope.launch { preferencesRepository.setReaderFont(font) }
    fun setReaderJustify(justify: Boolean) = viewModelScope.launch { preferencesRepository.setReaderJustify(justify) }

    fun addHighlight(blockIndex: Int, start: Int, end: Int, quote: String, color: Int = HighlightColors.Default) {
        if (itemId.isEmpty() || quote.isBlank()) return
        viewModelScope.launch { highlightRepository.add(itemId, blockIndex, start, end, quote, color) }
    }

    fun moveToCollection(collectionId: String?) {
        val current = _state.value.data ?: return
        viewModelScope.launch {
            collectionRepository.moveItem(itemId, collectionId)
            if (!current.isReadLater && !current.isStarred) itemRepository.setReadLater(itemId, true)
            _state.update { it.copy(data = it.data?.copy(collectionId = collectionId, isReadLater = current.isReadLater || (collectionId != null))) }
        }
    }

    fun createCollection(name: String, onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(collectionRepository.create(name)) }
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
            audioPlayer.stop() // one thing plays at a time
            ttsReader.startQueue(listOf(TtsReader.Track(data.title, buildSpeechChunks(data))))
        }
    }

    fun stopListen() = ttsReader.stop()
    fun setListenSpeed(speed: Float) = ttsReader.setSpeed(speed)
    fun listenNext() = ttsReader.skipNext()
    fun listenPrev() = ttsReader.skipPrevious()

    // -- Podcast episode audio -------------------------------------------------

    fun playEpisode() {
        val data = _state.value.data ?: return
        val url = data.enclosureUrl ?: return
        ttsReader.stop() // one thing plays at a time
        audioPlayer.play(url, data.title)
    }

    fun audioToggle() = audioPlayer.togglePlayPause()
    fun audioSeek(deltaMs: Int) = audioPlayer.seekBy(deltaMs)
    fun audioStop() = audioPlayer.stop()

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
        // Playback is global and follows the user out of the reader; the ListenBar / audio
        // bar (with its stop button) controls it, so we intentionally don't stop here.
    }
}
