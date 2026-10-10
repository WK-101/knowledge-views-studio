package app.parley.common.security

import app.parley.common.AppSettings
import app.parley.common.DuressView
import app.parley.common.calls.LockScreenCaller

/*
 * Duress unlock. The threat model and the choices behind these rules are in docs/SECURITY_MODEL.md, "Duress
 * unlock". In short: a duress PIN opens Parley looking normal, with private contacts and the sensitive things listed in
 * [Concealed] out of sight, until the next unlock with the real Parley PIN. Nothing is deleted or changed on disk.
 */

/** Where the app lock is: locked, open after a normal unlock, or open after a duress unlock (a duress session). */
enum class LockPhase { LOCKED, OPEN, DURESS }

/**
 * [hiding] starts with a duress unlock and lasts, across locks and restarts, until the next unlock with the real Parley
 * PIN: someone who still holds the phone after locking Parley (or a call arriving then) must not see what the duress
 * PIN hid. The duress *session* (the unlocked screens, with their in-memory settings overlay) ends at the next lock.
 */
data class DuressState(val phase: LockPhase = LockPhase.LOCKED, val hiding: Boolean = false, val vaultLocked: Boolean = false) {
    val session: Boolean get() = phase == LockPhase.DURESS
}

object DuressMachine {
    /** After a process start: locked, with the hiding that was stored. */
    fun restored(hiding: Boolean, vaultLocked: Boolean) = DuressState(LockPhase.LOCKED, hiding, hiding && vaultLocked)

    /** Any lock (Lock now, the timeout, leaving with "lock immediately"): the session ends, the hiding stays. */
    fun locked(s: DuressState) = s.copy(phase = LockPhase.LOCKED)

    /** A PIN typed on the lock screen. The real PIN ends the hiding; the duress PIN starts (or continues) it. */
    fun pinEntered(s: DuressState, verdict: PinVerdict, lockVaultOnDuress: Boolean): DuressState = when (verdict) {
        PinVerdict.NORMAL -> DuressState(LockPhase.OPEN, hiding = false, vaultLocked = false)
        PinVerdict.DURESS -> DuressState(LockPhase.DURESS, hiding = true, vaultLocked = lockVaultOnDuress)
        PinVerdict.WRONG -> s
    }

    /**
     * Unlocking another way: the fingerprint or screen lock, or no lock at all. Null when it may not unlock now: with a
     * Parley PIN set ([pinRequired]), only a PIN opens Parley, whether or not a duress PIN is set too. Otherwise "use
     * your fingerprint" would undo the duress PIN, and a lock screen that offered it only without one would say which
     * it is (docs/SECURITY_MODEL.md, "Duress unlock"). Without a PIN nothing can be hidden (a duress PIN is needed to
     * start hiding), so whatever was left is cleared.
     */
    fun otherUnlock(s: DuressState, pinRequired: Boolean): DuressState? =
        if (pinRequired) null else s.copy(phase = LockPhase.OPEN, hiding = false, vaultLocked = false)
}

/** What a duress unlock hides, beside what discreet mode already hides. */
enum class Concealed {
    // Discreet mode's own ("Hide private contacts"), forced on.
    PRIVATE_CONTACTS, PRIVATE_CALL_HISTORY, PRIVATE_NUMBER_MEMORY, PRIVATE_TO_CALL, DELETED_PRIVATE_CONTACTS,

    // Beyond discreet mode.
    /** Circle notes, and with them the promises (promises are lines of a note). */
    CIRCLE_NOTES,

    /** Notes for calls (pinned notes) and call notes, about anyone. */
    NOTES,
    SAFE_WORDS,

    /** My card › Shared with: every entry, not only private contacts'. */
    SHARED_WITH,

    /** A private contact's own ringtone: it would tell a "number" apart on the next call. */
    PRIVATE_RINGTONES,

    /** The duress PIN's own setting: shown as off, exactly as on a phone where none was ever set. */
    DURESS_SETTINGS,

    /** Private contacts' sealed details refuse to open even after the phone's own unlock (the "lock" option). */
    PRIVATE_DETAILS,
}

/** The safety switches changed during a duress session: in memory only, dropped at the next lock (never stored). */
data class SafetyOverlay(
    val appLock: Boolean? = null,
    val lockAfterMinutes: Int? = null,
    val secureScreen: Boolean? = null,
    val hideVault: Boolean? = null,
    val privateVaultHistory: Boolean? = null,
    /** "Caller on the lock screen": a session must not leave notes showing to anyone ringing the locked phone. */
    val lockScreenCaller: LockScreenCaller? = null,
)

object DuressPolicy {
    /** What discreet mode already hides; a duress unlock forces it on. */
    val BY_DISCREET_MODE: Set<Concealed> = setOf(
        Concealed.PRIVATE_CONTACTS, Concealed.PRIVATE_CALL_HISTORY, Concealed.PRIVATE_NUMBER_MEMORY, Concealed.PRIVATE_TO_CALL,
        Concealed.DELETED_PRIVATE_CONTACTS,
    )

    fun hidden(c: Concealed, s: DuressState, discreet: Boolean): Boolean = when {
        c in BY_DISCREET_MODE -> discreet || s.hiding
        c == Concealed.PRIVATE_DETAILS -> s.hiding && s.vaultLocked
        else -> s.hiding
    }

    /** The settings as the screens show them: stored, with the session's changes on top. */
    fun shown(stored: AppSettings, overlay: SafetyOverlay?): AppSettings = if (overlay == null) {
        stored
    } else {
        stored.copy(
            appLock = overlay.appLock ?: stored.appLock,
            lockAfterMinutes = overlay.lockAfterMinutes ?: stored.lockAfterMinutes,
            secureScreen = overlay.secureScreen ?: stored.secureScreen,
            hideVault = overlay.hideVault ?: stored.hideVault,
            privateVaultHistory = overlay.privateVaultHistory ?: stored.privateVaultHistory,
            lockScreenCaller = overlay.lockScreenCaller ?: stored.lockScreenCaller,
        )
    }

    /**
     * The settings Parley runs on. While hiding: discreet mode on whatever the switch says, and "Private call history"
     * as stored (turning it off in a session must not put private calls into the system call log). The app lock, its
     * delay and "Hide screen content" follow the session's changes, so the screens behave as they look; the lock comes
     * back as stored after the next restart. [AppSettings.duress] carries what the Privacy page shows.
     */
    fun effective(stored: AppSettings, hiding: Boolean, overlay: SafetyOverlay?): AppSettings {
        val shown = shown(stored, overlay)
        if (!hiding) return shown
        return shown.copy(
            hideVault = true,
            privateVaultHistory = stored.privateVaultHistory,
            duress = DuressView(hideVault = shown.hideVault, privateVaultHistory = shown.privateVaultHistory),
        )
    }

    /**
     * A change made during a duress session ([next], from the shown settings): what may be stored (everything except
     * the safety switches, which keep their stored values) and the session's new overlay. So someone made to turn off
     * the app lock or discreet mode sees it off, and the stored settings are untouched after the session.
     */
    fun split(stored: AppSettings, next: AppSettings): Pair<AppSettings, SafetyOverlay?> {
        val toStore = next.copy(
            appLock = stored.appLock, lockAfterMinutes = stored.lockAfterMinutes, secureScreen = stored.secureScreen,
            hideVault = stored.hideVault, privateVaultHistory = stored.privateVaultHistory, lockScreenCaller = stored.lockScreenCaller,
            duress = null,
        )
        val overlay = SafetyOverlay(
            appLock = next.appLock.takeIf { it != stored.appLock },
            lockAfterMinutes = next.lockAfterMinutes.takeIf { it != stored.lockAfterMinutes },
            secureScreen = next.secureScreen.takeIf { it != stored.secureScreen },
            hideVault = next.hideVault.takeIf { it != stored.hideVault },
            privateVaultHistory = next.privateVaultHistory.takeIf { it != stored.privateVaultHistory },
            lockScreenCaller = next.lockScreenCaller.takeIf { it != stored.lockScreenCaller },
        )
        return toStore to overlay.takeIf { it != SafetyOverlay() }
    }
}
