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
 * The platform engine has no real pause, so "pause" stops synthesis and remembers the
 * position; "resume" re-queues from there. Speed changes take effect on the next chunk.
 */
@Singleton
class TtsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class State(
        val active: Boolean = false,
        val playing: Boolean = false,
        val index: Int = 0,
        val total: Int = 0,
        val speed: Float = 1.0f,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var chunks: List<String> = emptyList()

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

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val i = utteranceId?.toIntOrNull() ?: return
            _state.update { it.copy(index = i, playing = true, active = true) }
        }

        override fun onDone(utteranceId: String?) {
            val i = utteranceId?.toIntOrNull() ?: return
            if (i >= chunks.lastIndex) _state.update { State(speed = it.speed) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = Unit
    }

    /** Begin reading [textChunks] from the top (title first, then sentences). */
    fun start(textChunks: List<String>) {
        if (textChunks.isEmpty()) return
        chunks = textChunks
        ensureEngine {
            _state.update { it.copy(active = true, total = chunks.size) }
            enqueueFrom(0)
        }
    }

    private fun enqueueFrom(from: Int) {
        val engine = tts ?: return
        engine.setSpeechRate(_state.value.speed)
        engine.stop()
        for (i in from until chunks.size) {
            val mode = if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunks[i], mode, null, i.toString())
        }
        _state.update { it.copy(playing = true, active = true, index = from) }
    }

    fun togglePlayPause() {
        val s = _state.value
        if (!s.active) return
        if (s.playing) {
            tts?.stop()
            _state.update { it.copy(playing = false) }
        } else {
            enqueueFrom(s.index)
        }
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        if (_state.value.playing) enqueueFrom(_state.value.index)
    }

    fun stop() {
        tts?.stop()
        chunks = emptyList()
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
