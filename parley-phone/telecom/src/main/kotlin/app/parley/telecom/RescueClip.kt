package app.parley.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build

/**
 * The sound a user picked for an answered rescue call, played once at the ear like a voice on the line (or on the
 * speaker when chosen). It needs no permission: the file was picked through Android's file picker, and holding the
 * phone in communication mode only takes "change audio settings", which Parley already has. Nothing is recorded and
 * the microphone is never opened. Anything that fails leaves the call silent. Main thread only.
 */
internal class RescueClip(private val context: Context) {
    private val am: AudioManager? = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null

    /** Parley set communication mode for the sound, so it puts it back afterwards (and only then). */
    private var tookMode = false

    fun play(uri: String, speaker: Boolean) {
        val audio = am ?: return
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            tookMode = true
            route(speaker)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(context, Uri.parse(uri))
                setOnPreparedListener { it.start() }
                // Played once; the call goes on in silence afterwards, and the phone's audio mode goes back at once
                // (not when the call ends, which may be an hour later): other apps' sound isn't held up meanwhile.
                setOnCompletionListener { this@RescueClip.stop() }
                setOnErrorListener { _, _, _ ->
                    this@RescueClip.stop()
                    true
                }
                prepareAsync()
            }
        }.onFailure { stop() }
    }

    /** The earpiece, or the speaker. */
    fun route(speaker: Boolean) {
        val audio = am ?: return
        if (!tookMode) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val type = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                audio.availableCommunicationDevices.firstOrNull { it.type == type }?.let { audio.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = speaker
            }
        }
    }

    /** Stops the sound and gives the audio mode back, unless something else (a real call) has taken it since. */
    fun stop() {
        releasePlayer()
        val audio = am ?: return
        if (!tookMode) return
        tookMode = false
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) audio.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.release() } }
        player = null
    }
}
