package app.parley.common.ux

/**
 * The Contacts header's one lock button. Locking is one intent at the moment of use, so where both locks apply
 * (private contacts are unlocked, and the app lock is on) the button opens a small menu with both, instead of two
 * padlocks side by side.
 */
enum class LockButton {
    /** Nothing to lock: no button. */
    NONE,

    /** Locks private contacts again (Parley stays open). */
    PRIVATE_CONTACTS,

    /** Locks Parley now, without waiting for the timeout. */
    PARLEY,

    /** A menu: Lock private contacts · Lock Parley. */
    BOTH;

    companion object {
        fun of(privateUnlocked: Boolean, appLock: Boolean): LockButton = when {
            privateUnlocked && appLock -> BOTH
            privateUnlocked -> PRIVATE_CONTACTS
            appLock -> PARLEY
            else -> NONE
        }
    }
}
