package app.parley.common.calls

/**
 * Auto-answer (Settings › Calls › Answer automatically). Off by default. When one of the chosen situations applies
 * (a headset or Bluetooth device is connected, simple mode is on, or the caller is a person or in a label chosen on
 * their page), a known caller's call is answered after a few seconds, with a visible countdown and Cancel on the call
 * screen. Never an unknown, hidden, blocked or likely-spam caller, and never while another call is going on.
 */
object AutoAnswer {
    val SECONDS_CHOICES = listOf(3, 5, 10, 15)
    const val DEFAULT_SECONDS = 5

    fun normalise(seconds: Int): Int = if (seconds in SECONDS_CHOICES) seconds else DEFAULT_SECONDS

    /** Why a call is answered automatically (for TalkBack and the countdown's wording). */
    enum class Reason { HEADSET, SIMPLE_MODE, CHOSEN, DRIVING }

    /**
     * The drive profile's own auto-answer ([DriveProfile]): only while a car marked in Settings › Calls › Drive profile
     * is connected, for favourites and/or the people chosen for auto-answer, after its own [seconds].
     */
    data class DriveScope(val seconds: Int, val favourites: Boolean, val chosen: Boolean)

    /** What the call path knows when the caller has been looked up. */
    data class Facts(
        /** The caller is a contact or a private contact (the lookup found them). */
        val knownCaller: Boolean,
        /** Hidden or unavailable number. */
        val hidden: Boolean = false,
        /** Screening blocked or silenced it, or flagged it as likely spam. */
        val blockedOrSpam: Boolean = false,
        /** Any other call exists (active, held, dialling or ringing). */
        val otherCall: Boolean = false,
        /** An emergency call-back (never answered by anything but the user). */
        val emergency: Boolean = false,
        val headsetConnected: Boolean = false,
        val simpleMode: Boolean = false,
        /** This person, or one of their labels, is chosen for auto-answer. */
        val chosen: Boolean = false,
        /** The caller is a favourite (a starred contact, or a private contact with Parley's star). */
        val favourite: Boolean = false,
        /** Set only while the drive profile's car is connected and its auto-answer is on. */
        val drive: DriveScope? = null,
    ) {
        /** A call never answered on its own, whatever is switched on. */
        val excluded: Boolean get() = listOf(hidden, blockedOrSpam, otherCall, emergency).any { it }
    }

    /** The reason to answer automatically, or null to let the call ring as usual. */
    fun reason(cfg: CallExtrasConfig, f: Facts): Reason? {
        if (!f.knownCaller || f.excluded) return null
        // In the car the drive profile's choice comes first, with its own wait.
        if (drives(f)) return Reason.DRIVING
        return when {
            cfg.autoAnswerChosen && f.chosen -> Reason.CHOSEN
            cfg.autoAnswerHeadset && f.headsetConnected -> Reason.HEADSET
            cfg.autoAnswerSimple && f.simpleMode -> Reason.SIMPLE_MODE
            else -> null
        }
    }

    /** Whether any auto-answer situation is switched on (the call path skips the checks otherwise). */
    fun enabled(cfg: CallExtrasConfig): Boolean = cfg.autoAnswerHeadset || cfg.autoAnswerSimple || cfg.autoAnswerChosen

    /** The drive profile's scope covers this caller (a favourite, or chosen). */
    private fun drives(f: Facts): Boolean {
        val d = f.drive ?: return false
        return (d.favourites && f.favourite) || (d.chosen && f.chosen)
    }

    /** How long a call armed for [reason] rings first: the drive profile has its own wait. */
    fun waitSeconds(cfg: CallExtrasConfig, reason: Reason, f: Facts): Int =
        normalise(if (reason == Reason.DRIVING) f.drive?.seconds ?: cfg.autoAnswerSeconds else cfg.autoAnswerSeconds)

    /** Whole seconds left before answering at [deadline] (both `elapsedRealtime`), never below 0; shown as "Answering in 3". */
    fun secondsLeft(deadline: Long, now: Long): Int = ((deadline - now + 999) / 1000).toInt().coerceAtLeast(0)
}
