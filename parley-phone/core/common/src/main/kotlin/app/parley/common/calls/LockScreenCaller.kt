package app.parley.common.calls

import app.parley.common.Initials
import java.util.Locale

/**
 * Settings › Privacy & security › "Caller on the lock screen": how much of a caller's name the call notifications
 * and the call screen show while the phone is locked. Private contacts and discreet mode hide more on their own;
 * this never brings back what they hide.
 */
enum class LockScreenCaller {
    /** The name, as before. */
    NAME,

    /** Only the initials ("AL"): enough to recognise someone you know, little for anyone else. */
    INITIALS,

    /** Just "Incoming call", with nothing about who it is. */
    NONE,
    ;

    /**
     * What stands for a caller saved as [name] on the lock screen: the name, its initials, or null for the plain
     * "Incoming call" (also when the name has no letters to take initials from).
     */
    fun shownName(name: String?, locale: Locale = Locale.getDefault()): String? = when (this) {
        NAME -> name
        INITIALS -> name?.let { Initials.of(it, locale) }?.takeIf { it.isNotEmpty() }
        NONE -> null
    }

    /**
     * Whether a call with [name] (null: no contact) is shown with less on the lock screen. An unknown number keeps
     * its number under Initials: there's no name to shorten, and the number is what the user needs to decide.
     */
    fun masks(name: String?): Boolean = when (this) {
        NAME -> false
        INITIALS -> name != null
        NONE -> true
    }
}
