package app.parley.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import app.parley.data.calls.Voicemail
import app.parley.telecom.CallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the voicemail player is doing, for the inbox rows. */
data class PlayerState(
    val id: Long? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speaker: Boolean = true,
    val error: String? = null,
)

/**
 * Plays one voicemail at a time from its content URI (V1). Speaker by default; "Earpiece" plays it quietly at the ear
 * like a call, using the communication audio mode only while it plays (never during a real call, which owns the audio).
 */
class VoicemailPlayer(context: Context, private val onStarted: (Voicemail) -> Unit = {}) {
    private val app = context.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var current: Voicemail? = null
    private var ticker: Job? = null
    private var focus: AudioFocusRequest? = null
    private var modeChanged = false

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Play [v], or pause/resume it if it's the one loaded. */
    fun toggle(v: Voicemail) {
        val p = player
        if (p != null && current?.id == v.id) {
            if (p.isPlaying) pause() else resume()
            return
        }
        start(v, 0)
    }

    fun seek(ms: Long) {
        val p = player ?: return
        runCatching { p.seekTo(ms.toInt()) }
        _state.value = _state.value.copy(positionMs = ms)
    }

    /** Switch between speaker and earpiece, keeping the position. */
    fun setSpeaker(on: Boolean) {
        if (_state.value.speaker == on) return
        _state.value = _state.value.copy(speaker = on)
        val v = current ?: return
        val p = player ?: return
        val pos = runCatching { p.currentPosition.toLong() }.getOrDefault(0)
        val wasPlaying = runCatching { p.isPlaying }.getOrDefault(false)
        releasePlayer()
        if (wasPlaying) start(v, pos) else prepareOnly(v, pos)
    }

    private fun start(v: Voicemail, from: Long) {
        releasePlayer()
        if (CallManager.state.value.any { it.isLive }) {
            _state.value = PlayerState(id = v.id, speaker = _state.value.speaker, error = "Can't play during a call")
            return
        }
        current = v
        val speaker = _state.value.speaker
        _state.value = PlayerState(id = v.id, speaker = speaker, durationMs = v.durationSec * 1000)
        val mp = create(v, speaker) ?: return
        mp.setOnPreparedListener {
            if (from > 0) runCatching { it.seekTo(from.toInt()) }
            if (!requestFocus()) {
                _state.value = _state.value.copy(error = "Another app is using the audio")
                return@setOnPreparedListener
            }
            routeForEarpiece(!speaker)
            it.start()
            _state.value = _state.value.copy(playing = true, durationMs = it.duration.toLong().takeIf { d -> d > 0 } ?: _state.value.durationMs)
            startTicker()
            onStarted(v)
        }
        runCatching { mp.prepareAsync() }.onFailure { fail("Couldn't play this voicemail") }
    }

    private fun prepareOnly(v: Voicemail, at: Long) {
        current = v
        val mp = create(v, _state.value.speaker) ?: return
        mp.setOnPreparedListener {
            runCatching { it.seekTo(at.toInt()) }
            _state.value = _state.value.copy(positionMs = at, durationMs = it.duration.toLong())
        }
        runCatching { mp.prepareAsync() }
    }

    private fun create(v: Voicemail, speaker: Boolean): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (speaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(app, v.uri)
            setOnCompletionListener {
                stopTicker()
                _state.value = _state.value.copy(playing = false, positionMs = 0)
                runCatching { it.seekTo(0) }
                restoreAudio()
            }
            setOnErrorListener { _, _, _ ->
                fail("Couldn't play this voicemail")
                true
            }
        }.also { player = it }
    } catch (_: Exception) {
        fail(if (!v.hasAudio) "The audio hasn't been downloaded yet" else "Couldn't open this voicemail")
        null
    }

    private fun pause() {
        runCatching { player?.pause() }
        stopTicker()
        _state.value = _state.value.copy(playing = false)
        restoreAudio()
    }

    private fun resume() {
        val p = player ?: return
        if (CallManager.state.value.any { it.isLive }) {
            _state.value = _state.value.copy(error = "Can't play during a call")
            return
        }
        if (!requestFocus()) return
        routeForEarpiece(!_state.value.speaker)
        runCatching { p.start() }
        _state.value = _state.value.copy(playing = true, error = null)
        startTicker()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                player?.let { p -> runCatching { _state.value = _state.value.copy(positionMs = p.currentPosition.toLong()) } }
                delay(200)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun fail(message: String) {
        releasePlayer()
        _state.value = _state.value.copy(playing = false, error = message)
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause() }
            .build()
        focus = req
        return runCatching { am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(true)
    }

    /** Earpiece: the communication mode routes voice to the earpiece; speaker mode leaves the audio mode alone. */
    private fun routeForEarpiece(earpiece: Boolean) {
        if (!earpiece) return
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            modeChanged = true
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let { am.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        }
    }

    private fun restoreAudio() {
        focus?.let { f -> runCatching { am.abandonAudioFocusRequest(f) } }
        focus = null
        if (!modeChanged) return
        modeChanged = false
        // Never touch the audio of a call that started meanwhile.
        if (CallManager.state.value.any { it.isLive }) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun releasePlayer() {
        stopTicker()
        player?.let { runCatching { it.release() } }
        player = null
        restoreAudio()
    }

    /** Stops playback and forgets the loaded voicemail. */
    fun stop() {
        releasePlayer()
        current = null
        _state.value = PlayerState(speaker = _state.value.speaker)
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
