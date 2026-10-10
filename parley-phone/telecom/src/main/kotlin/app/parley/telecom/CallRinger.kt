package app.parley.telecom

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import app.parley.common.calls.DndState
import app.parley.common.calls.RingRamp
import app.parley.common.calls.RingStyle
import app.parley.common.calls.RingVibration
import app.parley.common.calls.RingerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Parley's own ringer: a distinct tone (a rule's, a label's, the unknown-caller tone) played instead of Telecom's,
 * with the vibration Telecom would have made (or the caller's own haptic caller ID), "Vibrate first, then ring", a
 * gentle vibration for a call waiting during another call, and "Ring loud". At most one call has the ringer and at
 * most one the boost. Main thread only.
 *
 * Whether and how it vibrates is [RingVibration.decide]'s, for every path alike.
 */
internal class CallRinger(private val scope: CoroutineScope, private val silenceTelecom: () -> Unit) {
    private var tone: Ringtone? = null

    /** The call the ringer is claimed for (from the moment it is chosen, before it plays). */
    var toneFor: String? = null
        private set
    private var vibrator: Vibrator? = null

    /** The vibration playing, to start again when the screen goes off (see [screenWatch]). */
    private var playing: RingVibration.Decision.Vibrate? = null

    /** The claimed call is a call waiting: its vibration is the gentle one, and Telecom's waiting tone stays. */
    private var waiting = false

    /**
     * The caller's haptic caller ID found after the ringer was claimed but before its vibration started: used when it
     * starts (else the call would vibrate the default way).
     */
    private var pendingPattern: LongArray? = null

    /** The call whose ring volume is raised. */
    var boostedFor: String? = null
        private set

    /** The call whose ring volume ramps ("Increasing", "Vibrate first"). */
    var rampFor: String? = null
        private set

    /**
     * Plays [uri] for [session]'s call: silences Telecom first and waits (bounded) until its ringtone has stopped, so
     * the two never overlap, then re-checks [stillRinging] since the call may have been answered, silenced or ended
     * meanwhile. Only in normal ringer mode with Do Not Disturb off, and never while another call goes on, so
     * Parley never rings when the system wouldn't. The vibration starts first: nothing after it (the tone failing to
     * play) can leave the call without one.
     */
    @Suppress("CyclomaticComplexMethod") // Each check that the call still rings, and each ringer mode.
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
        val t = ringtone(context, uri) ?: return
        takeOverFrom(session.id)
        // Claimed now so a second lookup/screening result doesn't start another tone while we wait.
        tone = t
        toneFor = session.id
        waiting = false
        pendingPattern = null
        silenceTelecom()
        scope.launch {
            val waitedUntil = SystemClock.elapsedRealtime() + RINGER_STOP_MAX_MS
            delay(RINGER_STOP_MIN_MS)
            while (systemRingtonePlaying(am) && SystemClock.elapsedRealtime() < waitedUntil) delay(RINGER_POLL_MS)
            if (tone !== t || session.silenced || !stillRinging()) {
                if (tone === t) release()
                return@launch
            }
            when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> {
                    startVibration(context, pendingPattern ?: pattern)
                    sound(t, played)
                }
                // Switched to vibrate while Telecom stopped: Telecom is silent now, so the call vibrates Parley's way.
                AudioManager.RINGER_MODE_VIBRATE -> {
                    tone = null
                    startVibration(context, pendingPattern ?: pattern)
                }
                else -> release()
            }
        }
    }

    /**
     * "Vibrate first, then ring": Telecom is silenced at once, the call vibrates alone for [RingRamp.VIBRATE_FIRST_MS],
     * then [uri] (asked then, when the caller is known) rings, at the ramp's low volume. Same conditions as [play]; the
     * caller checks that the call vibrates at all ([RingRamp.vibratesFirst]).
     */
    @Suppress("CyclomaticComplexMethod") // Each check that the call still rings.
    fun vibrateFirst(
        context: Context,
        session: CallSession,
        pattern: () -> LongArray?,
        otherCallActive: Boolean,
        stillRinging: () -> Boolean,
        uri: () -> String?,
        played: () -> Unit,
    ) {
        val am = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (otherCallActive || toneFor != null) return
        tone = null
        toneFor = session.id
        waiting = false
        pendingPattern = null
        silenceTelecom()
        fun ours() = toneFor == session.id && !session.silenced && stillRinging()
        scope.launch {
            delay(RINGER_STOP_MIN_MS)
            if (!ours()) {
                if (toneFor == session.id) release()
                return@launch
            }
            startVibration(context, pendingPattern ?: pattern())
            delay(RingRamp.VIBRATE_FIRST_MS)
            if (!ours()) return@launch
            when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> Unit
                // On vibrate by now: the vibration is all the call gets, as Telecom would do.
                AudioManager.RINGER_MODE_VIBRATE -> return@launch
                // On silent by now: nothing, as Telecom would do (the vibration would otherwise go on to the end).
                else -> {
                    cancelVibration()
                    return@launch
                }
            }
            val t = toneOrDefault(context, uri()) ?: return@launch
            tone = t
            sound(t, played)
        }
    }

    /**
     * The ringer moves to call [id]: whatever it still plays for another call (a tone, a vibration) stops first, so
     * nothing is left running unclaimed when this claim gives up, which [follow] and the call's end could never stop.
     */
    private fun takeOverFrom(id: String) {
        if (toneFor != null && toneFor != id) stop()
    }

    /** Starts [t] and records it; neither failing stops the vibration already going. */
    private fun sound(t: Ringtone, played: () -> Unit) {
        runCatching { t.play() }
        runCatching { played() }
    }

    /** The vibration [d] from the start (after the system cancelled it). */
    private fun restartVibration(c: Context, d: RingVibration.Decision.Vibrate) {
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = vibrate(c, d.timings, d.usage)
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
        takeOverFrom(session.id)
        // Claimed now (no tone), so stop() and follow() end the vibration with the ringing.
        tone = null
        toneFor = session.id
        waiting = false
        pendingPattern = null
        silenceTelecom()
        scope.launch {
            delay(RINGER_STOP_MIN_MS)
            val stillOurs = toneFor == session.id && !session.silenced
            if (!stillOurs || !stillRinging() || am.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                if (toneFor == session.id) release()
                return@launch
            }
            // Back in normal mode meanwhile: still vibrate, since Telecom is silenced (the decision reads the mode).
            startVibration(context, pendingPattern ?: pattern)
            started()
        }
    }

    /**
     * A call waiting during another call: Telecom plays only its quiet waiting tone in the earpiece and never vibrates,
     * so a phone on the desk (speaker, headset, car) or in a pocket missed the second call. It vibrates gently here
     * ([RingVibration.WAITING], or the caller's rhythm with a long pause), and only when a ringing call would vibrate.
     * Telecom's waiting tone stays.
     */
    fun waiting(context: Context, session: CallSession, pattern: LongArray?) {
        if (toneFor != null || session.silenced) return
        val d = decide(context, pattern, callWaiting = true) as? RingVibration.Decision.Vibrate ?: return
        tone = null
        toneFor = session.id
        waiting = true
        pendingPattern = null
        vibrate(context, d)
    }

    /** The caller turned out to have a haptic caller ID after the ringer already started: its vibration takes over. */
    fun useVibration(context: Context, id: String, pattern: LongArray) {
        if (toneFor != id) return
        // Still waiting for Telecom's ringer to stop: the vibration starts with the tone, the caller's way.
        if (playing == null) {
            pendingPattern = pattern
            return
        }
        startVibration(context, pattern)
    }

    /** Another player (Telecom's ringer) is still playing a ringtone. Our own tone isn't playing yet at this point. */
    private fun systemRingtonePlaying(am: AudioManager): Boolean = runCatching {
        am.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE }
    }.getOrDefault(false)

    /** Silencing Telecom also stops its vibration, so vibrate as it would have ([RingVibration.decide]). */
    private fun startVibration(context: Context, pattern: LongArray?) {
        val d = decide(context, pattern, callWaiting = waiting) as? RingVibration.Decision.Vibrate
        if (d == null) {
            cancelVibration()
            return
        }
        vibrate(context, d)
    }

    private fun vibrate(context: Context, d: RingVibration.Decision.Vibrate) {
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = vibrate(context, d.timings, d.usage)
        playing = d
        watchScreen(context)
    }

    private fun cancelVibration() {
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
        playing = null
    }

    /**
     * Android 10 to 13 cancel every app's vibration when the screen goes off (Android 14 still does for the power
     * button), Telecom's own excepted: a call that rang with the screen on stopped vibrating once the screen timed out
     * while the tone played on. While Parley's vibration plays it starts again a moment after the screen goes off.
     * Silencing with the power key keeps the screen on, and ends the ringing before this runs.
     */
    private val screenWatch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            scope.launch {
                delay(SCREEN_OFF_REARM_MS)
                val d = playing ?: return@launch
                if (toneFor != null) restartVibration(c, d)
            }
        }
    }
    private var watchingScreen: Context? = null

    private fun watchScreen(context: Context) {
        if (watchingScreen != null) return
        val app = context.applicationContext
        val ok = runCatching {
            ContextCompat.registerReceiver(app, screenWatch, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        if (ok) watchingScreen = app
    }

    private fun unwatchScreen() {
        watchingScreen?.let { c -> runCatching { c.unregisterReceiver(screenWatch) } }
        watchingScreen = null
    }

    private fun release() {
        tone = null
        toneFor = null
        waiting = false
        pendingPattern = null
    }

    /** Stops the tone and its vibration. */
    fun stop() {
        runCatching { tone?.stop() }
        release()
        cancelVibration()
        unwatchScreen()
    }

    /** "Ring loud" for [id]: the ring volume at its maximum until [restoreBoost]. A ramp gives way to it. */
    fun boost(context: Context, id: String) {
        rampFor = null
        RingBoost.boostAsync(context)
        boostedFor = id
    }

    fun restoreBoost(context: Context?) {
        boostedFor = null
        if (context != null) RingBoost.restoreAsync(context)
    }

    /** "Increasing" or "Vibrate first" for [id]: the ring volume ramps up until [endRamp]. */
    fun ramp(context: Context, id: String, style: RingStyle, delayMs: Long) {
        rampFor = id
        RingVolumeRamp.startAsync(context, style, delayMs)
    }

    /** The ramped call stopped ringing: the user's ring volume comes back. */
    fun endRamp(context: Context?) {
        if (rampFor == null) return
        rampFor = null
        if (context != null) RingVolumeRamp.stopAsync(context)
    }

    /** After every change: the tone, the ramp and the boost end with their call's ringing. */
    fun follow(context: Context?, ringing: (String) -> Boolean) {
        toneFor?.let { if (!ringing(it)) stop() }
        rampFor?.let { if (!ringing(it)) endRamp(context) }
        boostedFor?.let { if (!ringing(it)) restoreBoost(context) }
    }

    companion object {
        /** The system's "Vibrate for calls" settings as stored (null where the phone stored none). */
        fun systemSetting(context: Context): RingVibration.SystemSetting {
            val cr = context.contentResolver
            fun system(key: String): Int? = runCatching { Settings.System.getInt(cr, key) }.getOrNull()
            fun global(key: String): Int? = runCatching { Settings.Global.getInt(cr, key) }.getOrNull()
            return RingVibration.SystemSetting(
                sdk = Build.VERSION.SDK_INT,
                vibrateWhenRinging = system(Settings.System.VIBRATE_WHEN_RINGING),
                ringIntensity = system(RING_VIBRATION_INTENSITY),
                rampingRinger = (system(APPLY_RAMPING_RINGER) ?: global(APPLY_RAMPING_RINGER) ?: 0) != 0,
            )
        }

        /** Whether a ringing call vibrates now, as Telecom decides it: on vibrate always, on silent never, else the setting. */
        fun ringVibrates(context: Context, am: AudioManager = context.getSystemService(AudioManager::class.java)): Boolean = when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> false
            AudioManager.RINGER_MODE_VIBRATE -> true
            else -> RingVibration.systemVibrates(systemSetting(context))
        }

        /** [RingVibration.decide] with the phone's ringer mode, settings, Do Not Disturb and vibrator as they are now. */
        fun decide(
            context: Context,
            pattern: LongArray?,
            callWaiting: Boolean = false,
            rescue: Boolean = false,
            silenced: Boolean = false,
        ): RingVibration.Decision {
            val am = context.getSystemService(AudioManager::class.java)
            val nm = context.getSystemService(NotificationManager::class.java)
            val ringer = when (runCatching { am?.ringerMode }.getOrNull()) {
                AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
                AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
                AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
                else -> RingerMode.UNKNOWN
            }
            val dnd = when (runCatching { nm?.currentInterruptionFilter }.getOrNull()) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> DndState.OFF
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndState.PRIORITY
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndState.ALARMS
                NotificationManager.INTERRUPTION_FILTER_NONE -> DndState.TOTAL_SILENCE
                else -> DndState.UNKNOWN
            }
            return RingVibration.decide(
                RingVibration.Facts(
                    ringer = ringer,
                    systemVibrates = RingVibration.systemVibrates(systemSetting(context)),
                    dnd = dnd,
                    pattern = pattern,
                    callWaiting = callWaiting,
                    rescue = rescue,
                    silenced = silenced,
                    hasVibrator = vibratorOf(context)?.hasVibrator() == true,
                ),
            )
        }

        private fun vibratorOf(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") // VibratorManager needs Android 12; this is the older path.
            context.getSystemService(Vibrator::class.java)
        }

        /**
         * Vibrates [timings] repeating, as a ringtone (or an alarm, for a Rescue call through Do Not Disturb); the
         * vibrator to cancel, or null when there is none or it refused. [RingVibration.decide] made the timings valid.
         * Ringtone vibrations go on in the background and in battery saver, where other app vibrations are dropped.
         */
        fun vibrate(context: Context, timings: LongArray, usage: RingVibration.Usage): Vibrator? {
            val v = vibratorOf(context)?.takeIf { it.hasVibrator() } ?: return null
            return runCatching {
                val effect = VibrationEffect.createWaveform(timings, 0)
                if (Build.VERSION.SDK_INT >= 33) {
                    val u = if (usage == RingVibration.Usage.ALARM) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_RINGTONE
                    v.vibrate(effect, VibrationAttributes.createForUsage(u))
                } else {
                    val u = if (usage == RingVibration.Usage.ALARM) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                    @Suppress("DEPRECATION") // The AudioAttributes form keeps the ring vibration on the ringtone's volume rules.
                    v.vibrate(effect, AudioAttributes.Builder().setUsage(u).build())
                }
                v
            }.getOrNull()
        }

        /**
         * [uri] as a looping ringtone on the ring stream, or null when it can't be read. Its haptic channels stay muted
         * (the AudioAttributes default), so a haptic ringtone never takes the vibrator from the ring vibration.
         */
        internal fun ringtone(context: Context, uri: String): Ringtone? {
            val t = runCatching { RingtoneManager.getRingtone(context, Uri.parse(uri)) }.getOrNull() ?: return null
            runCatching {
                t.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) t.isLooping = true
            }
            return t
        }

        /** [uri] as a ringtone, else the phone's default one. */
        internal fun toneOrDefault(context: Context, uri: String?): Ringtone? =
            uri?.let { ringtone(context, it) } ?: Settings.System.DEFAULT_RINGTONE_URI?.let { ringtone(context, it.toString()) }

        /** After silencing Telecom, wait at least this long, and at most the max for its ringtone to stop. */
        const val RINGER_STOP_MIN_MS = 120L
        private const val RINGER_STOP_MAX_MS = 700L
        private const val RINGER_POLL_MS = 40L

        /** After the screen goes off, the system cancels the vibration first; it starts again after this. */
        private const val SCREEN_OFF_REARM_MS = 300L

        /** Android's ring vibration intensity (Android 9+); 0 is off. */
        private const val RING_VIBRATION_INTENSITY = "ring_vibration_intensity"
        private const val APPLY_RAMPING_RINGER = "apply_ramping_ringer"
    }
}
