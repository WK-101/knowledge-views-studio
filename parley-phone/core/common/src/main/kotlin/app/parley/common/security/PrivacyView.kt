package app.parley.common.security

import app.parley.common.AppSettings
import app.parley.common.calls.LockScreenCaller

/**
 * The one answer to "may private data show now?" (docs/SECURITY_MODEL.md, "One privacy rule"). Every feature reads
 * this snapshot instead of the switches behind it: "Hide private contacts" (discreet mode), a duress unlock's hiding,
 * "Caller on the lock screen" and the app lock. A detekt rule (RawPrivacySwitch) keeps the raw switches out of
 * feature code, so the duress promise doesn't depend on every author picking the right mix of them.
 *
 * Built by core/data's `Privacy` from the settings Parley runs on, the duress state and the app lock. When any of
 * them can't be read it is [CLOSED]: everything private hidden, nothing about a caller on the lock screen.
 */
data class PrivacyView(
    /** Discreet mode as it holds now: "Hide private contacts", forced on while a duress unlock hides things. */
    val discreet: Boolean,
    /** The duress unlock's state: whether it hides things now, and whether private details stay locked. */
    val duress: DuressState,
    /** "Caller on the lock screen", as it holds now (a duress session's change included). */
    val lockScreen: LockScreenCaller,
    /** Parley's app lock is on and Parley is locked now. */
    val appLocked: Boolean,
) {
    /** A duress unlock hides things now (until the next unlock with the real PIN). */
    val hiding: Boolean get() = duress.hiding

    /**
     * Private contacts may show: their names, numbers, calls and everything kept with them. False in discreet mode and
     * while a duress unlock hides things; a private contact then reads like a number nobody saved.
     */
    val privateShown: Boolean get() = !discreet && !hiding

    /** The opposite of [privateShown]: private contacts are hidden now. */
    val privateHidden: Boolean get() = !privateShown

    /** Whether [c] is hidden now (discreet mode's own items too). */
    fun hides(c: Concealed): Boolean = DuressPolicy.hidden(c, duress, discreet)

    /** Notes for calls, call notes and case files' notes may show (a duress unlock hides them). */
    val notesShown: Boolean get() = !hides(Concealed.NOTES)

    /** Circle notes and promises may show. */
    val circleNotesShown: Boolean get() = !hides(Concealed.CIRCLE_NOTES)

    /** Family safe words may show. */
    val safeWordsShown: Boolean get() = !hides(Concealed.SAFE_WORDS)

    /**
     * What a notification or screen that may show on the lock screen calls a caller saved as [name]: in full, by
     * initials or not at all, as "Caller on the lock screen" says, when [locked] (the phone's own lock).
     */
    fun lockScreenName(name: String?, locked: Boolean): String? = if (locked) lockScreen.shownName(name) else name

    companion object {
        /** Everything private hidden, as if a duress unlock hid things with details locked; nothing on the lock screen. */
        val CLOSED = PrivacyView(
            discreet = true, duress = DuressState(hiding = true, vaultLocked = true), lockScreen = LockScreenCaller.NONE, appLocked = true,
        )

        /** The view of [settings] (the settings Parley runs on, already with the duress overlay), [duress] and the lock. */
        fun of(settings: AppSettings, duress: DuressState, appLocked: Boolean): PrivacyView = PrivacyView(
            // DuressPolicy.effective forces discreet mode on while hiding; said again here so a view never depends on it.
            discreet = settings.hideVault || duress.hiding,
            duress = duress,
            lockScreen = settings.lockScreenCaller,
            appLocked = appLocked && settings.appLock,
        )

        /**
         * A view for code that has only the duress state (no settings at hand): discreet mode is taken as on and the
         * phone as locked, so only the duress-only items ([Concealed.NOTES], [Concealed.SAFE_WORDS]…) can show.
         */
        fun duressOnly(duress: DuressState): PrivacyView = CLOSED.copy(duress = duress)
    }
}
