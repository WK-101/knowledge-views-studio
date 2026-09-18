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

/** A saved transcript annotation, anchored to a media time range and to a character range in the
 *  reflowed prose (so it repaints over the exact words). */
data class TranscriptAnnotation(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val charStart: Int,
    val charEnd: Int,
    val color: Int,
    val quote: String,
    val note: String?,
)

data class TranscriptUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val unavailable: Boolean = false,
    val onDeviceSupported: Boolean = false,
    val onDeviceModelReady: Boolean = false,
    /** Set when an on-device transcription attempt produced nothing, so the UI can explain it. */
    val generateError: Boolean = false,
    val cues: List<TranscriptCue> = emptyList(),
    /** Saved annotations for this transcript, for painting + management. */
    val annotations: List<TranscriptAnnotation> = emptyList(),
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
 * keeps annotations live, drives podcast playback for tap-to-seek, and toggles whole-transcript
 * keeping and per-selection annotations. On-device transcription can be run at any time (even when a
 * caption transcript is already showing), so the reader can always fall back to speech-to-text.
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

    /** 0f..1f while an on-device transcription runs, else null. */
    private val _generating = MutableStateFlow<Float?>(null)
    val generating: StateFlow<Float?> = _generating.asStateFlow()

    private var itemId: String = ""
    private var transcript: Transcript? = null
    private var enclosureUrl: String? = null
    private var mediaTitle: String = ""

    fun start(itemId: String) {
        if (this.itemId == itemId && !_state.value.loading) return
        this.itemId = itemId
        observeAnnotations(itemId)
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
                    // Report on-device readiness even when captions loaded, so "Transcribe on device"
                    // stays available from the menu over a caption transcript.
                    val (onDeviceSupported, onDeviceReady) = transcriptRepository.onDeviceStatus()
                    _state.value = _state.value.copy(
                        loading = false, error = null, unavailable = false,
                        cues = r.transcript.cues, provenance = r.transcript.source,
                        language = r.transcript.language, title = mediaTitle,
                        isAudio = isAudio, youtubeId = ytId,
                        onDeviceSupported = onDeviceSupported, onDeviceModelReady = onDeviceReady,
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

    private fun observeAnnotations(itemId: String) {
        viewModelScope.launch {
            highlightRepository.observeForItem(itemId).collect { list ->
                val anns = list.mapNotNull { h ->
                    val sel = h.startSelector ?: return@mapNotNull null
                    if (!sel.startsWith("t:")) return@mapNotNull null
                    val startMs = sel.removePrefix("t:").toLongOrNull() ?: return@mapNotNull null
                    val endMs = h.endSelector?.removePrefix("t:")?.toLongOrNull() ?: startMs
                    TranscriptAnnotation(
                        id = h.id, startMs = startMs, endMs = endMs,
                        charStart = h.startOffset, charEnd = h.endOffset,
                        color = h.color, quote = h.quote, note = h.note,
                    )
                }.sortedBy { it.startMs }
                _state.value = _state.value.copy(annotations = anns)
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

    /** Run offline on-device transcription — available whether or not a caption transcript is loaded,
     *  so the reader can always get a speech-to-text version. On success it replaces what's shown. */
    fun generateOnDevice() {
        if (_generating.value != null) return
        viewModelScope.launch {
            _generating.value = 0f
            _state.value = _state.value.copy(generateError = false)
            val t = coRunCatching {
                transcriptRepository.transcribeOnDevice(itemId) { p -> _generating.value = p }
            }.getOrNull()
            _generating.value = null
            if (t != null && !t.isEmpty) {
                transcript = t
                _state.value = _state.value.copy(
                    unavailable = false, error = null, generateError = false,
                    cues = t.cues, provenance = t.source, language = t.language,
                )
            } else {
                _state.value = _state.value.copy(generateError = true)
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

    /** Save a selected passage as a timestamped annotation over a character range of the prose. */
    fun annotate(charStart: Int, charEnd: Int, startMs: Long, endMs: Long, quote: String, color: Int) {
        if (quote.isBlank()) return
        viewModelScope.launch {
            coRunCatching {
                highlightRepository.addTimestamped(
                    itemId, startMs, endMs, quote.trim(), color,
                    note = "[${formatTimestamp(startMs)}]",
                    charStart = charStart, charEnd = charEnd,
                )
            }
        }
    }

    fun removeAnnotation(id: String) {
        viewModelScope.launch { coRunCatching { highlightRepository.remove(id, itemId) } }
    }

    fun recolorAnnotation(id: String, color: Int) {
        viewModelScope.launch { coRunCatching { highlightRepository.setColor(id, itemId, color) } }
    }

    fun noteAnnotation(id: String, note: String?) {
        viewModelScope.launch { coRunCatching { highlightRepository.setNote(id, itemId, note) } }
    }

    /** Tap the transcript at a media time: seek the episode if it's playing, else start it. */
    fun onSeek(ms: Long) {
        if (!_state.value.isAudio) return
        if (audioPlayer.isActiveFor()) audioPlayer.seekTo(ms.toInt())
        else enclosureUrl?.let { audioPlayer.play(it, mediaTitle) }
    }

    fun audioToggle() = audioPlayer.togglePlayPause()
    fun seekBy(deltaMs: Int) = audioPlayer.seekBy(deltaMs)

    /** External-open URL for a YouTube transcript position (opens the video at that second). */
    fun youtubeUrlAt(ms: Long): String? =
        _state.value.youtubeId?.let { "https://www.youtube.com/watch?v=$it&t=${ms / 1000}s" }
}
