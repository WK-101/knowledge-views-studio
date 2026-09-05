package com.cairn.reader.ui.brief

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.audio.AudioPlayer
import com.cairn.reader.audio.SpeechText
import com.cairn.reader.audio.TtsReader
import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.repo.InsightsRepository
import com.cairn.reader.data.repo.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BriefUiState(
    val loading: Boolean = true,
    val items: List<ItemListRow> = emptyList(),
    val totalMinutes: Int = 0,
)

/**
 * The Daily Brief: a short, focus-ranked digest of the freshest things worth reading, that you can
 * read through or listen to hands-free. Composed on-device from the same Focus scorer as Top Picks.
 */
@HiltViewModel
class BriefViewModel @Inject constructor(
    private val insightsRepository: InsightsRepository,
    private val itemRepository: ItemRepository,
    private val ttsReader: TtsReader,
    private val audioPlayer: AudioPlayer,
) : ViewModel() {

    private val _state = MutableStateFlow(BriefUiState())
    val state: StateFlow<BriefUiState> = _state.asStateFlow()

    val tts: StateFlow<TtsReader.State> = ttsReader.state

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val picks = runCatching { insightsRepository.topPicks(8) }.getOrDefault(emptyList())
            _state.value = BriefUiState(loading = false, items = picks, totalMinutes = picks.sumOf { it.readingMinutes })
        }
    }

    /** Read the whole brief aloud, back-to-back. */
    fun listen() = viewModelScope.launch {
        val tracks = _state.value.items.mapNotNull { row ->
            val (title, body) = itemRepository.articleText(row.id) ?: return@mapNotNull null
            val chunks = SpeechText.chunks(title, body)
            if (chunks.isEmpty()) null else TtsReader.Track(title, chunks)
        }
        if (tracks.isNotEmpty()) {
            audioPlayer.stop()
            ttsReader.startQueue(tracks)
        }
    }

    fun listenToggle() = ttsReader.togglePlayPause()
    fun listenStop() = ttsReader.stop()
    fun listenNext() = ttsReader.skipNext()
}
