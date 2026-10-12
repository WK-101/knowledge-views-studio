package app.parley.common.people

import app.parley.common.security.PrivacyView

/**
 * Private contacts' dates and what follows from them (a birthday today on Next up and on the call screen, a private
 * Circle member's catch-up on Next up): shown while private contacts may show, gone the moment they may not.
 */
object PrivateDates {
    /**
     * Whether private contacts' dates and Next up lines may show now: the privacy view shows private contacts (not
     * hidden, no duress unlock hiding them), they aren't locked with "Lock private contacts" ([lockedByPerson]), and
     * this isn't the lock screen ([onLockScreen]), whatever "Caller on the lock screen" allows for device contacts.
     */
    fun mayShow(view: PrivacyView, lockedByPerson: Boolean, onLockScreen: Boolean = false): Boolean =
        !onLockScreen && PrivateArchive.mayShow(hidden = view.privateHidden, hiding = view.hiding, locked = lockedByPerson)
}
