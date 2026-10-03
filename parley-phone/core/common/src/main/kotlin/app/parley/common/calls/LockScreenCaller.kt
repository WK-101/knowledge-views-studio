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
     * Whether a call is shown with less on the lock screen; [saved] is true when the caller is one of your contacts
     * or private contacts. An unknown number keeps its number under Initials, even when the network sends a name with
     * it: there's no saved name to shorten, and the number is what the user needs to decide.
     */
    fun masks(saved: Boolean): Boolean = when (this) {
        NAME -> false
        INITIALS -> saved
        NONE -> true
    }
}
