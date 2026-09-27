package app.parley.common.calls

/**
 * The one place that decides how emergency calls are treated. Every gate on the call path asks [bypasses] instead
 * of checking "is this an emergency?" on its own, so a new prompt, limit or filter can't forget the exemption.
 *
 * - An emergency call (an emergency number, or a call the network or the modem marks as one) skips every
 *   safeguard: confirm-before-calling, the SIM question, the dial guard, call-time allowances, limits and
 *   supervision, the pocket guard, screening and blocking.
 * - For [WINDOW_MS] after an emergency call starts (and again after it ends), incoming calls are never screened and
 *   no call is limited or silenced: the operator's call-back often comes from a hidden or unknown number.
 * - Numbers the user listed as starting that window (a GP, a school) are never limited and start the window, but
 *   are otherwise ordinary numbers: the user still gets the questions they asked for.
 *
 * Android glue: `app.parley.data.EmergencyNumbers` (the number check) and `app.parley.telecom.ScreeningGuard`
 * (the stored window).
 */
object EmergencyPolicy {
    const val WINDOW_MS = 60 * 60 * 1000L

    /** Everything that can stand between the user and a call, or between a caller and the user. */
    enum class Safeguard {
        CONFIRM_BEFORE_CALL,
        SIM_CHOICE,
        DIAL_GUARD,
        POCKET_GUARD,
        CALL_TIME_ALLOWANCE,
        CALL_LIMITS,
        SCREENING,
    }

    data class Facts(
        /** The platform says the number is an emergency number. */
        val emergencyNumber: Boolean = false,
        /**
         * The call carries an emergency property: identified as an emergency call by the network, or placed while the
         * phone is in emergency callback mode (an incoming call then is very likely the operator calling back).
         */
        val emergencyCallProperty: Boolean = false,
        /** Within [WINDOW_MS] of an emergency call. */
        val inWindow: Boolean = false,
        /** A number the user listed as starting the emergency window. */
        val userListed: Boolean = false,
    ) {
        val isEmergency: Boolean get() = emergencyNumber || emergencyCallProperty
    }

    /** True when [safeguard] must not apply to a call with these [facts]. */
    fun bypasses(safeguard: Safeguard, facts: Facts): Boolean {
        if (facts.isEmergency) return true
        return when (safeguard) {
            Safeguard.SCREENING -> facts.inWindow
            Safeguard.CALL_LIMITS, Safeguard.CALL_TIME_ALLOWANCE -> facts.inWindow || facts.userListed
            Safeguard.CONFIRM_BEFORE_CALL, Safeguard.SIM_CHOICE, Safeguard.DIAL_GUARD, Safeguard.POCKET_GUARD -> false
        }
    }

    /** Shorthand for an outgoing number known only by its platform check. */
    fun bypasses(safeguard: Safeguard, emergencyNumber: Boolean): Boolean = bypasses(safeguard, Facts(emergencyNumber = emergencyNumber))

    /**
     * Whether this call starts (or extends) the window: any emergency call, either direction (an incoming one is
     * itself a call-back: keep the way open for the next), and outgoing calls to a number the user listed.
     */
    fun startsWindow(facts: Facts, incoming: Boolean): Boolean = facts.isEmergency || (facts.userListed && !incoming)

    // ---- The window. Measured on the monotonic clock so changing the time can't end it early; after a reboot
    // (the monotonic clock restarts) the wall clock is the only reference left.

    /** When the window was (re)started: [elapsedMs] on the monotonic clock of boot [bootCount], and the wall time. */
    data class WindowMark(val elapsedMs: Long, val bootCount: Int, val wallMs: Long)

    /** Milliseconds left in the window, or null when none runs. [bootCount] -1: unknown (always use the wall clock). */
    fun windowLeftMs(mark: WindowMark?, nowElapsedMs: Long, bootCount: Int, nowWallMs: Long): Long? {
        if (mark == null) return null
        val sameBoot = bootCount >= 0 && mark.bootCount == bootCount && nowElapsedMs >= mark.elapsedMs
        val passed = if (sameBoot) nowElapsedMs - mark.elapsedMs else nowWallMs - mark.wallMs
        if (passed < 0 || passed > WINDOW_MS) return null
        return WINDOW_MS - passed
    }

    // ---- Fallback when the platform can't answer (no telephony service, an OEM exception).

    /**
     * Numbers every handset must treat as emergency numbers (3GPP TS 22.101): 112 and 911 always, and 000, 08, 110,
     * 118, 119 and 999 at least without a SIM. Used only when the platform check fails: a false "yes" skips a
     * question, a false "no" could delay help.
     */
    private val FALLBACK = setOf("112", "911", "000", "08", "110", "118", "119", "999")

    fun isFallbackEmergencyNumber(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        // Only separators may surround the digits: "112", "1 1 2", "(112)". "+112" or "*112#" are not dialled as such.
        if (number.any { !it.isDigit() && it !in " -(). " }) return false
        return number.filter { it.isDigit() } in FALLBACK
    }
}
