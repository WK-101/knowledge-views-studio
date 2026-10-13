package app.parley.common.security

/**
 * How Parley's window is kept out of Android's recents thumbnails and screenshots (docs/SECURITY_MODEL.md, "Lock screen
 * and home screen"). Android saves a picture of the last screen of every app for the recents overview, on its own
 * storage and outside Parley's keys, and shows it to whoever opens recents next. Without the app lock that picture
 * could be a private contact's page or a list with private names, still there after they are hidden or locked.
 *
 * So the thumbnail is left blank while the app lock is on (as before) and also while private contacts are on screen in
 * Parley ([privateExposed]); "Hide screen content" keeps the window secure all the time.
 */
object WindowShield {
    /** Android 13: recents thumbnails can be turned off per window; before it only FLAG_SECURE blanks them. */
    const val RECENTS_SWITCH_SDK = 33

    /** What the window gets: the recents thumbnail allowed or not, and FLAG_SECURE on or off. */
    data class Window(val recentsScreenshot: Boolean, val secure: Boolean)

    /**
     * Whether private contacts can be on Parley's screen now: some exist and may show ([PrivacyView.privateShown]), or
     * a private contact's details were opened lately ([detailsOpen], the vault's own unlocked window).
     */
    fun privateExposed(view: PrivacyView, hasPrivate: Boolean, detailsOpen: Boolean): Boolean =
        view.privateShown && (hasPrivate || detailsOpen)

    /**
     * The window for [sdk] with the app lock [appLock] (Parley [locked] now), "Hide screen content" [secureScreen], and
     * [privateExposed]. [leaving]: Parley is going to the background, the moment before Android 13 takes its picture.
     */
    fun window(sdk: Int, appLock: Boolean, locked: Boolean, secureScreen: Boolean, privateExposed: Boolean, leaving: Boolean): Window {
        val blankRecents = appLock || privateExposed
        val hideNow = (appLock && locked) || (blankRecents && leaving && sdk < RECENTS_SWITCH_SDK)
        return Window(recentsScreenshot = !blankRecents, secure = secureScreen || hideNow)
    }
}
