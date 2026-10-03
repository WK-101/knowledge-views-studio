package app.parley.common.calls

import kotlin.math.sqrt

/** Settings › Calls › During calls › "Start calls on speaker": when a call starts on the speaker by itself. */
enum class SpeakerDefault {
    /** Calls start where Android puts them (the earpiece, or a connected headset). */
    OFF,

    /** Every call. */
    ALWAYS,

    /** Calls with a number that is neither a contact nor a private contact (hidden numbers too). */
    UNKNOWN_NUMBERS,
}

/**
 * Whether a call starts on the speaker ([SpeakerDefault]). Decided once per call, as soon as it is answered (incoming)
 * or dialled (outgoing); after that the Speaker button is the user's, so switching back always sticks.
 *
 * It only ever replaces the earpiece: a connected headset, earbuds or car keep the call. Never an emergency call (the
 * phone behaves exactly as Android would), never while another call goes on (the audio is already where the user
 * put it), and never in hold mode, which turns the speaker on and back off by itself.
 */
object SpeakerOnStart {
    /** Where the call's audio is going now, as far as the speaker decision cares. */
    enum class Route { EARPIECE, SPEAKER, HEADSET, UNKNOWN }

    enum class Step {
        /** Not yet: the call hasn't started, the routes or the caller lookup aren't known. Ask again on the next change. */
        WAIT,

        /** Turn the speaker on now (once). */
        TURN_ON,

        /** Leave the audio alone for this call, for good. */
        LEAVE,
    }

    data class Facts(
        val choice: SpeakerDefault,
        /** Answered (incoming), or dialling, connecting or connected (outgoing). */
        val started: Boolean,
        val emergency: Boolean,
        /** The caller is a contact or a private contact; null while the lookup hasn't finished. */
        val savedCaller: Boolean?,
        /** Another call is live (waiting, on hold, being dialled). */
        val otherCall: Boolean,
        val route: Route,
        /** The phone offers a speaker route for this call. */
        val speakerAvailable: Boolean,
        val holdMode: Boolean = false,
    )

    fun step(f: Facts): Step = when {
        f.choice == SpeakerDefault.OFF || f.emergency || f.otherCall || f.holdMode -> Step.LEAVE
        !f.started || f.route == Route.UNKNOWN -> Step.WAIT
        // A headset or car wins; on the speaker already, nothing to do.
        f.route != Route.EARPIECE -> Step.LEAVE
        !f.speakerAvailable -> Step.LEAVE
        f.choice == SpeakerDefault.ALWAYS -> Step.TURN_ON
        f.savedCaller == null -> Step.WAIT
        f.savedCaller -> Step.LEAVE
        else -> Step.TURN_ON
    }
}

/**
 * Settings › Calls › During calls › "Turn the screen off at your ear": off, during calls (while dialling too), or
 * only once the call is answered, so the screen stays on while an outgoing call rings out (to see it connect, or
 * hang up). Never while a call rings in: the screen has to show who is calling.
 */
object ScreenAtEar {
    enum class Mode { OFF, DURING_CALLS, ONCE_ANSWERED }

    fun mode(enabled: Boolean, onceAnswered: Boolean): Mode = when {
        !enabled -> Mode.OFF
        onceAnswered -> Mode.ONCE_ANSWERED
        else -> Mode.DURING_CALLS
    }

    /**
     * Whether the proximity screen-off is held: a call connected (or, in [Mode.DURING_CALLS], being dialled), on the
     * earpiece, with the call screen in front.
     */
    fun holds(mode: Mode, connected: Boolean, dialling: Boolean, earpiece: Boolean, screenInFront: Boolean): Boolean {
        val inCall = when (mode) {
            Mode.OFF -> false
            Mode.DURING_CALLS -> connected || dialling
            Mode.ONCE_ANSWERED -> connected
        }
        return inCall && earpiece && screenInFront
    }
}

/**
 * Settings › Calls › Answering › "Flip to silence": turning the phone face down while it rings stops the ringing.
 * Read from the gravity sensor (or the accelerometer), which needs no permission. It silences only, like the Silence
 * button: the call rings on quietly and is never declined.
 *
 * Feed it one reading at a time while a call rings; [onSample] says true once, when the phone has lain face down for
 * [holdMs]. The phone has to be seen the other way up first, so a phone already lying face down when the call came in
 * still rings (it wasn't turned over), and a reading in the middle of a shake doesn't count.
 */
class FlipDetector(private val holdMs: Long = HOLD_MS) {
    private var armed = false
    private var downSince = -1L
    private var fired = false

    /** [x], [y], [z] in m/s² (Android's sensor axes: z points out of the screen), [atMs] a monotonic time. */
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): Boolean {
        if (fired) return false
        if (!faceDown(x, y, z)) {
            // Moving, upright or face up: from now on, lying face down counts.
            if (!moving(x, y, z)) armed = true
            downSince = -1L
            return false
        }
        if (!armed) return false
        if (downSince < 0) downSince = atMs
        if (atMs - downSince >= holdMs) {
            fired = true
            return true
        }
        return false
    }

    /** A new call rings: start over. */
    fun reset() {
        armed = false
        downSince = -1L
        fired = false
    }

    companion object {
        /** How long the phone must stay face down. */
        const val HOLD_MS = 600L

        /** Face down within about 35° of flat: gravity's share on the screen's axis, pointing away from the screen. */
        const val FACE_DOWN_SHARE = 0.82f

        /** Readings this far from 1 g are a hand moving the phone, not the phone lying still. */
        const val STILL_MIN = 7.0f
        const val STILL_MAX = 12.5f

        fun faceDown(x: Float, y: Float, z: Float): Boolean {
            val g = sqrt(x * x + y * y + z * z)
            if (g < STILL_MIN || g > STILL_MAX) return false
            return z / g <= -FACE_DOWN_SHARE
        }

        private fun moving(x: Float, y: Float, z: Float): Boolean {
            val g = sqrt(x * x + y * y + z * z)
            return g < STILL_MIN || g > STILL_MAX
        }
    }
}
