package com.cairn.reader.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams a single podcast episode (a feed's audio enclosure) with the platform
 * [MediaPlayer]. Streaming pulls the audio from its host, so — unlike the on-device
 * text-to-speech — this reaches the network only to fetch the episode the user chose.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class State(
        val active: Boolean = false,
        val playing: Boolean = false,
        val loading: Boolean = false,
        val title: String = "",
        val positionMs: Int = 0,
        val durationMs: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun play(url: String, title: String) {
        teardown()
        _state.value = State(active = true, playing = false, loading = true, title = title)
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setOnPreparedListener {
                it.start()
                _state.update { s -> s.copy(playing = true, loading = false, durationMs = runCatching { it.duration }.getOrDefault(0)) }
                startTicker()
            }
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(context, "Couldn't play this episode.", Toast.LENGTH_SHORT).show()
                stop()
                true
            }
            mp.setDataSource(url)
            mp.prepareAsync()
        }.onFailure {
            Toast.makeText(context, "Couldn't play this episode.", Toast.LENGTH_SHORT).show()
            stop()
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        runCatching {
            if (p.isPlaying) {
                p.pause()
                _state.update { it.copy(playing = false) }
            } else {
                p.start()
                _state.update { it.copy(playing = true) }
                startTicker()
            }
        }
    }

    fun seekBy(deltaMs: Int) {
        val p = player ?: return
        runCatching {
            val pos = (p.currentPosition + deltaMs).coerceIn(0, p.duration.coerceAtLeast(0))
            p.seekTo(pos)
            _state.update { it.copy(positionMs = pos) }
        }
    }

    fun stop() {
        teardown()
        _state.value = State()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player
                if (p != null && runCatching { p.isPlaying }.getOrDefault(false)) {
                    _state.update { it.copy(positionMs = runCatching { p.currentPosition }.getOrDefault(it.positionMs)) }
                }
                delay(500)
            }
        }
    }

    private fun teardown() {
        ticker?.cancel(); ticker = null
        runCatching { player?.stop() }
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
    }
}
