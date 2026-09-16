package com.cairn.reader.ui.transcript

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cairn.reader.audio.AudioPlayer
import com.cairn.reader.data.db.ItemDao
import com.cairn.reader.data.db.ItemType
import com.cairn.reader.data.repo.HighlightRepository
import com.cairn.reader.data.repo.TranscriptRepository
import com.cairn.reader.data.repo.TranscriptResult
import com.cairn.reader.domain.transcript.CaptionFetcher
import com.cairn.reader.domain.transcript.Transcript
import com.cairn.reader.domain.transcript.TranscriptCue
import com.cairn.reader.domain.transcript.TranscriptSourceKind
import com.cairn.reader.domain.transcript.formatTimestamp
import com.cairn.reader.util.coRunCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TranscriptUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val unavailable: Boolean = false,
    val onDeviceSupported: Boolean = false,
    val onDeviceModelReady: Boolean = false,
    val cues: List<TranscriptCue> = emptyList(),
    /** Start-ms of cues that carry a saved annotation, for the highlight marker. */
    val highlightedStarts: Set<Long> = emptySet(),
    /** Whether the whole transcript is permanently saved (vs. re-fetchable). */
    val saved: Boolean = false,
    val provenance: TranscriptSourceKind = TranscriptSourceKind.UNKNOWN,
    val language: String? = null,
    val title: String = "",
    val isAudio: Boolean = false,
    val youtubeId: String? = null,
)

/**
 * Backs the transcript screen: resolves a transcript (saved copy → captions → honest unavailability),
 * keeps the highlighted-cue markers live, drives podcast playback for tap-to-seek, and toggles both
 * whole-transcript keeping and per-cue annotations. All on-device.
 */
@HiltViewModel
class TranscriptViewModel @Inject constructor(
    private val transcriptRepository: TranscriptRepository,
    private val highlightRepository: HighlightRepository,
    private val itemDao: ItemDao,
    private val captionFetcher: CaptionFetcher,
    private val audioPlayer: AudioPlayer,
) : ViewModel() {

    val audio: StateFlow<AudioPlayer.State> = audioPlayer.state

    private val _state = MutableStateFlow(TranscriptUiState())
    val state: StateFlow<TranscriptUiState> = _state.asStateFlow()

    private var itemId: String = ""
    private var transcript: Transcript? = null
    private var enclosureUrl: String? = null
    private var mediaTitle: String = ""
    // start-ms → highlight id, so a second tap on a cue removes its annotation.
    private var idByStart: Map<Long, String> = emptyMap()

    fun start(itemId: String) {
        if (this.itemId == itemId && !_state.value.loading) return
        this.itemId = itemId
        observeHighlights(itemId)
        observeSaved(itemId)
        load(itemId)
    }

    private fun load(itemId: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, unavailable = false)
            val item = coRunCatching { itemDao.getItem(itemId) }.getOrNull()
            enclosureUrl = item?.enclosureUrl
            mediaTitle = item?.title.orEmpty()
            val ytId = item?.url?.let { captionFetcher.youtubeVideoId(it) }
            val isAudio = item?.type == ItemType.AUDIO.name && !item.enclosureUrl.isNullOrBlank()
            when (val r = coRunCatching { transcriptRepository.load(itemId) }
                .getOrElse { TranscriptResult.Failed(it.message ?: "Couldn't load the transcript") }) {
                is TranscriptResult.Ready -> {
                    transcript = r.transcript
                    _state.value = _state.value.copy(
                        loading = false, error = null, unavailable = false,
                        cues = r.transcript.cues, provenance = r.transcript.source,
                        language = r.transcript.language, title = mediaTitle,
                        isAudio = isAudio, youtubeId = ytId,
                    )
                }
                is TranscriptResult.Unavailable -> _state.value = _state.value.copy(
                    loading = false, unavailable = true,
                    onDeviceSupported = r.onDeviceSupported, onDeviceModelReady = r.onDeviceModelReady,
                    title = mediaTitle, isAudio = isAudio, youtubeId = ytId,
                )
                is TranscriptResult.Failed -> _state.value = _state.value.copy(loading = false, error = r.message, title = mediaTitle)
            }
        }
    }

    private fun observeHighlights(itemId: String) {
        viewModelScope.launch {
            highlightRepository.observeForItem(itemId).collect { list ->
                val timed = list.mapNotNull { h ->
                    val sel = h.startSelector ?: return@mapNotNull null
                    if (!sel.startsWith("t:")) return@mapNotNull null
                    val ms = sel.removePrefix("t:").toLongOrNull() ?: return@mapNotNull null
                    ms to h.id
                }
                idByStart = timed.toMap()
                _state.value = _state.value.copy(highlightedStarts = idByStart.keys)
            }
        }
    }

    private fun observeSaved(itemId: String) {
        viewModelScope.launch {
            transcriptRepository.observeSaved(itemId).collect { saved ->
                _state.value = _state.value.copy(saved = saved)
            }
        }
    }

    /** Keep or forget the whole transcript. Keeping needs a loaded transcript. */
    fun toggleSaveWhole() {
        val t = transcript ?: return
        viewModelScope.launch {
            if (_state.value.saved) coRunCatching { transcriptRepository.forget(itemId) }
            else coRunCatching { transcriptRepository.saveWhole(itemId, t) }
        }
    }

    /** Toggle a cue's annotation: add a timestamped highlight, or remove the existing one. */
    fun toggleHighlight(cue: TranscriptCue, color: Int) {
        viewModelScope.launch {
            val existing = idByStart[cue.startMs]
            if (existing != null) {
                coRunCatching { highlightRepository.remove(existing, itemId) }
            } else {
                coRunCatching {
                    highlightRepository.addTimestamped(
                        itemId, cue.startMs, cue.endMs, cue.text, color,
                        note = "[${formatTimestamp(cue.startMs)}]",
                    )
                }
            }
        }
    }

    /** Tap a cue: seek the episode if it's playing, otherwise start it (podcast tap-to-listen). */
    fun onCueTap(cue: TranscriptCue) {
        if (!_state.value.isAudio) return
        if (audioPlayer.isActiveFor()) {
            audioPlayer.seekTo(cue.startMs.toInt())
        } else {
            enclosureUrl?.let { audioPlayer.play(it, mediaTitle) }
        }
    }

    fun audioToggle() = audioPlayer.togglePlayPause()
    fun seekBy(deltaMs: Int) = audioPlayer.seekBy(deltaMs)

    /** External-open URL for a YouTube cue (opens the video at the cue's second). */
    fun youtubeUrlAt(ms: Long): String? =
        _state.value.youtubeId?.let { "https://www.youtube.com/watch?v=$it&t=${ms / 1000}s" }
}
