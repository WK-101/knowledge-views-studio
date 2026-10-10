package app.parley.common.calls

import kotlin.math.max

/** Settings › Calls › Answering › Ringing › "Ring style": how an incoming call rings. */
enum class RingStyle {
    /** As the phone rings: Android's tone at your ring volume from the start. */
    NORMAL,

    /** Starts quietly and grows to your ring volume over [RingRamp.DURATION_MS]. */
    INCREASING,

    /** Vibrates on its own for [RingRamp.VIBRATE_FIRST_MS], then rings, growing to your ring volume. */
    VIBRATE_FIRST,
}

/**
 * Whether a ringing call vibrates, and how: one decision for every way Parley rings (its own tone, the caller's haptic
 * caller ID, a call waiting during another call, a Rescue call). Pure, so the whole table is tested; docs/SETTINGS.md
 * ("Ringing and vibration") has it in words.
 *
 * Telecom vibrates on its own for every call Parley leaves to it. Parley's ringer has to vibrate the same way whenever
 * it takes the ringing over (silencing Telecom silences its vibration too), so the system's "Vibrate for calls" is
 * read the way Telecom reads it ([systemVibrates]).
 */
object RingVibration {
    /** The platform ringer's pattern: 1 s on, 1 s off, repeated. "The phone's usual vibration". */
    val USUAL: LongArray = longArrayOf(0, 1000, 1000)

    /** A call waiting during another call: two short taps, then a long pause, so it is felt without buzzing on. */
    val WAITING: LongArray = longArrayOf(0, 120, 140, 120, WAITING_PAUSE_MS)

    /** The pause after each rhythm while a second call waits. */
    const val WAITING_PAUSE_MS = 3_600L

    /**
     * The system's "Vibrate for calls" as Settings stores it. [vibrateWhenRinging] (`Settings.System.VIBRATE_WHEN_RINGING`)
     * and [ringIntensity] (`ring_vibration_intensity`) are null when the phone has no value stored; [rampingRinger] is
     * Android's own "Vibrate first, then ring gradually" (`apply_ramping_ringer`).
     */
    data class SystemSetting(val sdk: Int, val vibrateWhenRinging: Int?, val ringIntensity: Int?, val rampingRinger: Boolean = false)

    /**
     * Whether Telecom vibrates a call in normal ringer mode. Android 13 deprecated VIBRATE_WHEN_RINGING: from then on
     * only the ring vibration intensity turns it on or off, and a phone that never stored one uses its default, which
     * is on. Reading VIBRATE_WHEN_RINGING there (usually 0 or missing) made Parley's own ringing silent while Settings
     * showed "Vibrate for calls" on. Before Android 13 the switch decides, and the ramping ringer vibrates by itself.
     */
    fun systemVibrates(s: SystemSetting): Boolean {
        if (s.ringIntensity == 0) return false
        if (s.sdk >= ANDROID_13) return true
        return (s.vibrateWhenRinging ?: 0) != 0 || s.rampingRinger
    }

    /** What the vibration is for, which the system weighs against Do Not Disturb and its settings. */
    enum class Usage { RINGTONE, ALARM }

    /** Why a call doesn't vibrate. */
    enum class Quiet { NO_VIBRATOR, SILENCED, PHONE_SILENT, DND, VIBRATION_OFF }

    sealed interface Decision {
        data class None(val why: Quiet) : Decision

        /** Vibrate [timings] (Android waveform timings, repeated from index 0) as [usage]. */
        class Vibrate(val timings: LongArray, val usage: Usage) : Decision
    }

    /** Everything the decision depends on, as known when the call rings. */
    data class Facts(
        val ringer: RingerMode,
        /** [systemVibrates] for the phone's settings. */
        val systemVibrates: Boolean,
        val dnd: DndState = DndState.OFF,
        /** In priority mode: whether Do Not Disturb lets this call ring (null: unknown, which counts as no). */
        val dndAllowsCall: Boolean? = null,
        /**
         * The caller's chosen pattern (their own, else their label's: [CallerHaptics.resolve]) as a repeating waveform;
         * null for "the phone's usual vibration". A Situation or the drive profile never picks a pattern: they let a
         * call ring or keep it quiet ([silenced]).
         */
        val pattern: LongArray? = null,
        /** Another call goes on (active or on hold): this one waits. */
        val callWaiting: Boolean = false,
        /** A Rescue call the user asked for: Do Not Disturb doesn't stop it. */
        val rescue: Boolean = false,
        /** Kept quiet: by a rule, an allowance, a Situation, the drive profile, or the user (Silence, a key, flip). */
        val silenced: Boolean = false,
        val hasVibrator: Boolean = true,
    )

    /** Whether, and how, the call vibrates. The table is in docs/SETTINGS.md. */
    @Suppress("CyclomaticComplexMethod") // One branch per row of the table.
    fun decide(f: Facts): Decision {
        if (!f.hasVibrator) return Decision.None(Quiet.NO_VIBRATOR)
        if (f.silenced) return Decision.None(Quiet.SILENCED)
        if (f.ringer == RingerMode.SILENT) return Decision.None(Quiet.PHONE_SILENT)
        val dndBlocks = when (f.dnd) {
            DndState.OFF, DndState.UNKNOWN -> false
            DndState.PRIORITY -> f.dndAllowsCall != true
            DndState.ALARMS, DndState.TOTAL_SILENCE -> true
        }
        if (dndBlocks && !f.rescue) return Decision.None(Quiet.DND)
        val vibrates = f.ringer == RingerMode.VIBRATE || f.systemVibrates
        if (!vibrates) return Decision.None(Quiet.VIBRATION_OFF)
        val chosen = usable(f.pattern)
        val timings = when {
            f.callWaiting && chosen != null -> waiting(chosen)
            f.callWaiting -> WAITING
            else -> chosen ?: USUAL
        }
        // A Rescue call breaks through Do Not Disturb as the alarm the user set it up to be.
        return Decision.Vibrate(timings, if (dndBlocks) Usage.ALARM else Usage.RINGTONE)
    }

    /**
     * [pattern] when Android can play it: at least an off and an on time, none negative, and some vibration in it. An
     * empty or all-pause pattern would throw (or buzz nothing) and leave the call without any vibration: it means the
     * phone's usual vibration instead.
     */
    fun usable(pattern: LongArray?): LongArray? {
        val p = pattern ?: return null
        if (p.size < 2 || p.any { it < 0 }) return null
        if (p.indices.none { it % 2 == 1 && p[it] > 0 }) return null
        return p
    }

    /** A caller's rhythm for a call waiting: the rhythm once, then the long pause before it comes again. */
    fun waiting(pattern: LongArray): LongArray {
        // Odd size: it ends on a pause (index 0 is the delay before the first pulse), which is lengthened.
        return if (pattern.size % 2 == 1) {
            pattern.copyOf().also { it[it.lastIndex] = max(it.last(), WAITING_PAUSE_MS) }
        } else {
            pattern + WAITING_PAUSE_MS
        }
    }

    private const val ANDROID_13 = 33
}

/**
 * "Increasing" and "Vibrate first, then ring" ([RingStyle]): the ring volume starts low and steps up to the user's own
 * ring volume, which is put back however the ringing ends (answered, declined, silenced, a crash). Pure: the app moves
 * the volume and keeps the saved value on disk before the first change.
 */
object RingRamp {
    /** From the first step to the user's own volume. */
    const val DURATION_MS = 20_000L

    /** "Vibrate first": vibration alone for this long (two of the usual pulses), then the tone. */
    const val VIBRATE_FIRST_MS = 4_000L

    /** The volume a ramp starts at: the lowest that still rings (0 would switch the phone to vibrate). */
    const val START_VOLUME = 1

    /**
     * Whether this call's ring volume ramps: a ramping style, the phone ringing out loud in normal mode with Do Not
     * Disturb off, no other call going on (Telecom only plays a waiting tone then), no "Ring loud" for it, Android's own
     * ramping ringer off (it would ramp twice), and a volume above [START_VOLUME] to grow to.
     */
    @Suppress("LongParameterList") // The facts it weighs.
    fun applies(
        style: RingStyle,
        ringer: RingerMode,
        dnd: DndState,
        otherCall: Boolean,
        ringLoud: Boolean,
        systemRamps: Boolean,
        volume: Int,
    ): Boolean = style != RingStyle.NORMAL && ringer == RingerMode.NORMAL && dnd == DndState.OFF && !otherCall && !ringLoud &&
        !systemRamps && volume > START_VOLUME

    /** "Vibrate first" takes the ringing over only when the call vibrates; otherwise it rings increasing. */
    fun vibratesFirst(style: RingStyle, vibrates: Boolean): Boolean = style == RingStyle.VIBRATE_FIRST && vibrates

    /** One change of volume, [atMs] after the ramp starts. */
    data class Step(val atMs: Long, val volume: Int)

    /**
     * The steps from [START_VOLUME] up to [target], evenly over [durationMs] after [delayMs]: one per volume level, the
     * last at the target. None when there is nothing to grow to.
     */
    fun steps(target: Int, durationMs: Long = DURATION_MS, delayMs: Long = 0): List<Step> {
        if (target <= START_VOLUME) return emptyList()
        val levels = target - START_VOLUME
        return (1..levels).map { i -> Step(delayMs + durationMs * i / levels, START_VOLUME + i) }
    }

    /** What to do with a saved volume when the ramp ends or is found left over. */
    sealed interface Restore {
        /** Nothing was saved. */
        data object Nothing : Restore

        /** Not now (the phone is on vibrate or silent, where the ring volume reads as muted): keep it and try later. */
        data object Later : Restore

        /** Put [volume] back. */
        data class To(val volume: Int) : Restore

        /** The user changed the volume during the ramp: theirs stays, and the saved one is dropped. */
        data object KeepUsers : Restore
    }

    /**
     * [saved] is the user's volume before the ramp, [lastSet] the last volume the ramp set, [current] the volume now.
     * Restored only when nobody else moved it since, so the volume keys and the volume panel always win.
     */
    fun restore(saved: Int?, lastSet: Int?, current: Int, ringerNormal: Boolean): Restore = when {
        saved == null || saved < 0 -> Restore.Nothing
        !ringerNormal -> Restore.Later
        lastSet == null || current == lastSet -> Restore.To(saved)
        else -> Restore.KeepUsers
    }

    /** The user moved the volume during the ramp: it stops there, at their choice. */
    fun userTookOver(lastSet: Int, current: Int): Boolean = current != lastSet
}

/** The volume keys while a call rings: they silence it (never decline it), as on Android's own phone app. */
object RingKeys {
    /** `KeyEvent.KEYCODE_VOLUME_UP`, `KEYCODE_VOLUME_DOWN` and `KEYCODE_VOLUME_MUTE`. */
    private val VOLUME = setOf(24, 25, 164)

    /**
     * Whether a key press silences the ringing: a volume key going down while a call rings and isn't silenced yet, with
     * no other call going on (then the keys set the call's volume, as Telecom does: the waiting call only beeps).
     * Android normally does this itself before the key reaches any app; Parley's call screen does it when the key
     * reaches it instead.
     */
    fun silences(keyCode: Int, down: Boolean, ringing: Boolean, silenced: Boolean, otherCall: Boolean): Boolean =
        down && keyCode in VOLUME && ringing && !silenced && !otherCall
}
