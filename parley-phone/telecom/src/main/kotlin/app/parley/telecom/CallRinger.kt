package app.parley.telecom

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Parley's own ringer: a distinct tone (a rule's, a label's, the unknown-caller tone) played instead of Telecom's,
 * with the vibration Telecom would have made (or the caller's own haptic caller ID), and "Ring loud". At most one call
 * has the tone and at most one the boost. Main thread only.
 */
internal class CallRinger(private val scope: CoroutineScope, private val silenceTelecom: () -> Unit) {
    private var tone: Ringtone? = null

    /** The call the tone is claimed for (from the moment it is chosen, before it plays). */
    var toneFor: String? = null
        private set
    private var vibrator: Vibrator? = null

    /**
     * The caller's haptic caller ID found after the tone was claimed but before its vibration started: used when it
     * starts (else the call would vibrate the default way).
     */
    private var pendingPattern: LongArray? = null

    /** The call whose ring volume is raised. */
    var boostedFor: String? = null
        private set

    /**
     * Plays [uri] for [session]'s call: silences Telecom first and waits (bounded) until its ringtone has stopped, so
     * the two never overlap, then re-checks [stillRinging] since the call may have been answered, silenced or ended
     * meanwhile. Only in normal ringer mode with Do Not Disturb off, and never while another call is active, so
     * Parley never rings when the system wouldn't.
     */
    fun play(
        context: Context,
        session: CallSession,
        uri: String,
        otherCallActive: Boolean,
        stillRinging: () -> Boolean,
        /** The caller's haptic caller ID (repeating waveform), instead of the platform's 1 s on / 1 s off. */
        pattern: LongArray? = null,
        played: () -> Unit,
    ) {
        val am = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (otherCallActive) return
        val t = runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri)) }.getOrNull() ?: return
        t.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= 28) t.isLooping = true
        // Claimed now so a second lookup/screening result doesn't start another tone while we wait.
        tone = t
        toneFor = session.id
        pendingPattern = null
        silenceTelecom()
        scope.launch {
            val waitedUntil = SystemClock.elapsedRealtime() + RINGER_STOP_MAX_MS
            delay(RINGER_STOP_MIN_MS)
            while (systemRingtonePlaying(am) && SystemClock.elapsedRealtime() < waitedUntil) delay(RINGER_POLL_MS)
            if (tone !== t || session.silenced || !stillRinging() || am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                if (tone === t) release()
                return@launch
            }
            runCatching { t.play() }
            played()
            startVibration(context, am, pendingPattern ?: pattern)
        }
    }

    /**
     * The phone is on vibrate: Telecom's vibration is swapped for the caller's own [pattern] (haptic caller ID). Like
     * [play], only with Do Not Disturb off and no other call active; silent mode stays silent. Telecom is silenced
     * first (which stops its vibration), then ours starts once [stillRinging] is still true.
     */
    fun vibrateOnly(context: Context, session: CallSession, pattern: LongArray, otherCallActive: Boolean, stillRinging: () -> Boolean, started: () -> Unit) {
        val am = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_VIBRATE) return
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (otherCallActive) return
        // Claimed now (no tone), so stop() and follow() end the vibration with the ringing.
        tone = null
        toneFor = session.id
        pendingPattern = null
        silenceTelecom()
        scope.launch {
            delay(RINGER_STOP_MIN_MS)
            val stillOurs = toneFor == session.id && !session.silenced
            if (!stillOurs || !stillRinging() || am.ringerMode != AudioManager.RINGER_MODE_VIBRATE) {
                if (toneFor == session.id) release()
                return@launch
            }
            startVibration(context, am, pendingPattern ?: pattern)
            started()
        }
    }

    /** The caller turned out to have a haptic caller ID after the tone already started: its vibration takes over. */
    fun useVibration(context: Context, id: String, pattern: LongArray) {
        if (toneFor != id) return
        // Still waiting for Telecom's ringer to stop: the vibration starts with the tone, the caller's way.
        if (vibrator == null) {
            pendingPattern = pattern
            return
        }
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
        startVibration(context, context.getSystemService(AudioManager::class.java), pattern)
    }

    /** Another player (Telecom's ringer) is still playing a ringtone. Our own tone isn't playing yet at this point. */
    private fun systemRingtonePlaying(am: AudioManager): Boolean = runCatching {
        am.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }
    }.getOrDefault(false)

    /**
     * Silencing Telecom also stops its vibration, so vibrate like it would: only when the system's "Vibrate for calls"
     * is on (the tone only plays in normal ringer mode, where that setting decides).
     */
    private fun startVibration(context: Context, am: AudioManager, pattern: LongArray? = null) {
        if (!ringVibrates(context, am)) return
        val v = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(pattern?.takeIf { it.size >= 2 } ?: RING_VIBRATION, 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            }
            vibrator = v
        }
    }

    private fun release() {
        tone = null
        toneFor = null
        pendingPattern = null
    }

    /** Stops the tone and its vibration. */
    fun stop() {
        runCatching { tone?.stop() }
        release()
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
    }

    /** "Ring loud" for [id]: the ring volume at its maximum until [restoreBoost]. */
    fun boost(context: Context, id: String) {
        RingBoost.boostAsync(context)
        boostedFor = id
    }

    fun restoreBoost(context: Context?) {
        boostedFor = null
        if (context != null) RingBoost.restoreAsync(context)
    }

    /** After every change: the tone and the boost end with their call's ringing. */
    fun follow(context: Context?, ringing: (String) -> Boolean) {
        toneFor?.let { if (!ringing(it)) stop() }
        boostedFor?.let { if (!ringing(it)) restoreBoost(context) }
    }

    companion object {
        /**
         * Whether a ringing call vibrates now: on vibrate, or in normal mode with the system's "Vibrate for calls" on
         * (and, on Android 13+, its ring vibration intensity not off). Never in silent mode.
         */
        fun ringVibrates(context: Context, am: AudioManager = context.getSystemService(AudioManager::class.java)): Boolean {
            if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
            val cr = context.contentResolver
            val vibrateWhenRinging = am.ringerMode == AudioManager.RINGER_MODE_VIBRATE ||
                runCatching { Settings.System.getInt(cr, Settings.System.VIBRATE_WHEN_RINGING, 0) != 0 }.getOrDefault(false)
            // Android 13+ also has a ring vibration intensity; 0 means off.
            val intensityOff = runCatching { Settings.System.getInt(cr, "ring_vibration_intensity", -1) == 0 }.getOrDefault(false)
            return vibrateWhenRinging && !intensityOff
        }

        /** After silencing Telecom, wait at least this long, and at most the max for its ringtone to stop. */
        private const val RINGER_STOP_MIN_MS = 120L
        private const val RINGER_STOP_MAX_MS = 700L
        private const val RINGER_POLL_MS = 40L

        /** Like the platform ringer: 1 s on, 1 s off, repeated. */
        private val RING_VIBRATION = longArrayOf(0, 1000, 1000)
    }
}
