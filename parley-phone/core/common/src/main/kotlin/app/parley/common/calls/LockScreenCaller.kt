package app.parley.common.calls

import app.parley.common.Initials
import java.util.Locale

/**
 * Settings › Privacy & security › "Caller on the lock screen": how much about a caller the call notifications and the
 * call screen show while the phone is locked. Private contacts and discreet mode hide more on their own; this never
 * brings back what they hide. Stored by name, so the order here is only the order of the choices.
 */
enum class LockScreenCaller {
    /** The name and everything Parley knows to show with it: the pinned note, "Who is this?" and the last call. */
    NAME_AND_NOTES,

    /**
     * The name (the default). The pinned note, "Who is this?" and the last call wait until the phone is unlocked:
     * anyone can ring a locked phone and read them otherwise.
     */
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
        NAME_AND_NOTES, NAME -> name
        INITIALS -> name?.let { Initials.of(it, locale) }?.takeIf { it.isNotEmpty() }
        NONE -> null
    }

    /**
     * Whether a call is shown with less on the lock screen; [saved] is true when the caller is one of your contacts
     * or private contacts. An unknown number keeps its number under Initials, even when the network sends a name with
     * it: there's no saved name to shorten, and the number is what the user needs to decide.
     */
    fun masks(saved: Boolean): Boolean = when (this) {
        NAME_AND_NOTES, NAME -> false
        INITIALS -> saved
        NONE -> true
    }

    /** Whether the caller's name shows in full (only the notes may be held back). */
    val showsName: Boolean get() = this == NAME_AND_NOTES || this == NAME

    /** Whether the pinned note, "Who is this?" and the last call show while the phone is locked. */
    val showsNotes: Boolean get() = this == NAME_AND_NOTES
}
