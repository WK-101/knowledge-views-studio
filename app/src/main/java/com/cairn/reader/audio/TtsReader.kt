package com.cairn.reader.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-aloud backed by the on-device [TextToSpeech] engine. Nothing leaves the phone:
 * the system engine synthesizes locally (and offline once its voice data is installed),
 * which keeps read-aloud consistent with Cairn's privacy-first promise.
 *
 * Plays a QUEUE of tracks (each an article's title + sentences) back-to-back, so a whole
 * feed can be listened to; utterance ids encode "trackIndex:chunkIndex". The platform engine
 * has no real pause, so "pause" stops synthesis and remembers the position; "resume" re-queues.
 */
@Singleton
class TtsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** One article in the listen queue. */
    data class Track(val title: String, val chunks: List<String>)

    data class State(
        val active: Boolean = false,
        val playing: Boolean = false,
        val index: Int = 0,          // chunk within the current track
        val total: Int = 0,          // chunks in the current track
        val trackIndex: Int = 0,     // position in the queue
        val trackCount: Int = 0,     // queue length
        val trackTitle: String = "",
        val speed: Float = 1.0f,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var tracks: List<Track> = emptyList()

    private fun ensureEngine(onReady: () -> Unit) {
        if (ready) { onReady(); return }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                val locale = Locale.getDefault()
                val available = (tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
                tts?.language = if (available) locale else Locale.US
                tts?.setOnUtteranceProgressListener(progressListener)
                onReady()
            } else {
                Toast.makeText(context, "Text-to-speech isn't available on this device.", Toast.LENGTH_SHORT).show()
                _state.update { State() }
            }
        }
    }

    private fun parseId(id: String?): Pair<Int, Int>? {
        val parts = id?.split(":") ?: return null
        if (parts.size != 2) return null
        val t = parts[0].toIntOrNull() ?: return null
        val i = parts[1].toIntOrNull() ?: return null
        return t to i
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (t, i) = parseId(utteranceId) ?: return
            val track = tracks.getOrNull(t)
            _state.update {
                it.copy(
                    active = true, playing = true, trackIndex = t, index = i,
                    total = track?.chunks?.size ?: it.total,
                    trackTitle = track?.title ?: it.trackTitle,
                )
            }
        }

        override fun onDone(utteranceId: String?) {
            val (t, i) = parseId(utteranceId) ?: return
            val track = tracks.getOrNull(t) ?: return
            if (i >= track.chunks.lastIndex) {
                if (t < tracks.lastIndex) enqueueTrack(t + 1, 0)
                else _state.update { State(speed = it.speed) }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = Unit
    }

    /** Begin reading a queue of tracks from [startAt]. */
    fun startQueue(newTracks: List<Track>, startAt: Int = 0) {
        val filtered = newTracks.filter { it.chunks.isNotEmpty() }
        if (filtered.isEmpty()) return
        tracks = filtered
        ensureEngine {
            _state.update { it.copy(active = true, trackCount = filtered.size) }
            enqueueTrack(startAt.coerceIn(0, filtered.lastIndex), 0)
        }
    }

    /** Convenience for a single article. */
    fun start(textChunks: List<String>) = startQueue(listOf(Track("", textChunks)))

    private fun enqueueTrack(t: Int, fromChunk: Int) {
        val track = tracks.getOrNull(t) ?: return
        val engine = tts ?: return
        engine.setSpeechRate(_state.value.speed)
        engine.stop()
        for (i in fromChunk until track.chunks.size) {
            val mode = if (i == fromChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(track.chunks[i], mode, null, "$t:$i")
        }
        _state.update {
            it.copy(
                active = true, playing = true, trackIndex = t, trackCount = tracks.size,
                trackTitle = track.title, index = fromChunk, total = track.chunks.size,
            )
        }
    }

    fun togglePlayPause() {
        val s = _state.value
        if (!s.active) return
        if (s.playing) {
            tts?.stop()
            _state.update { it.copy(playing = false) }
        } else {
            enqueueTrack(s.trackIndex, s.index)
        }
    }

    fun skipNext() {
        val s = _state.value
        if (s.trackIndex < tracks.lastIndex) enqueueTrack(s.trackIndex + 1, 0)
    }

    fun skipPrevious() {
        val s = _state.value
        // Restart the current track if we're past its start, else step back a track.
        if (s.index > 1 || s.trackIndex == 0) enqueueTrack(s.trackIndex, 0)
        else enqueueTrack(s.trackIndex - 1, 0)
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        val s = _state.value
        if (s.playing) enqueueTrack(s.trackIndex, s.index)
    }

    fun stop() {
        tts?.stop()
        tracks = emptyList()
        _state.update { State(speed = it.speed) }
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        _state.update { State() }
    }
}
