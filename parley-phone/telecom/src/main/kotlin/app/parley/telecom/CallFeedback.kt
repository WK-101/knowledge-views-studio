package app.parley.telecom

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.parley.common.calltime.CallHaptic

/**
 * Haptics (A6) and earpiece beeps (T1, T5) during calls.
 *
 * Vibration respects silent mode and the "Call haptics" setting. Beeps use the voice-call stream, so they
 * play where the call audio plays and the other person doesn't hear them as a separate sound.
 */
internal class CallFeedback(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val handler = Handler(Looper.getMainLooper())

    /** Call event haptics, only when enabled in settings. */
    fun haptic(h: CallHaptic) {
        val enabled = runCatching { TelecomGraph.dependencies.callHaptics() }.getOrDefault(false)
        if (enabled) vibrate(h)
    }

    /** Vibration the user asked for explicitly (reminders); still silent in silent mode. */
    fun vibrate(h: CallHaptic) {
        val v = vibrator ?: return
        if (!v.hasVibrator() || audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        try {
            v.vibrate(VibrationEffect.createWaveform(h.timings, -1))
        } catch (_: Exception) {
        }
    }

    /** One short beep (reminder) or two (warning) in the earpiece. */
    fun beep(twice: Boolean) {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME)
        } catch (_: RuntimeException) {
            return // no audio resources right now: the vibration still tells the user
        }
        tone.startTone(if (twice) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, if (twice) 400 else 200)
        handler.postDelayed({ tone.release() }, 800)
    }

    private companion object {
        const val TONE_VOLUME = 80
    }
}
