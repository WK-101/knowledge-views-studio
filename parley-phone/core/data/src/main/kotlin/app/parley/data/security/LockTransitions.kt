package app.parley.data.security

import app.parley.common.security.DuressMachine
import app.parley.common.security.PinVerdict
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * I21: what the app lock's transitions mean for the stores, in one place (the app lock calls these; tests too).
 */
object LockTransitions {
    /**
     * A PIN was right ([attempt] is not wrong): the Parley PIN ends any hiding; the duress PIN starts a duress session.
     * Returns false for a wrong PIN, which changes nothing.
     */
    suspend fun pinEntered(c: DataContainer, attempt: AppPinStore.Attempt): Boolean {
        if (attempt.verdict == PinVerdict.WRONG) return false
        val next = DuressMachine.pinEntered(Concealment.state.value, attempt.verdict, attempt.lockVaultOnDuress)
        withContext(Dispatchers.IO) { Concealment.move(next) }
        if (next.session) c.settings.beginDuressSession() else c.settings.endDuressSession()
        c.appPin.endSession()
        // Nothing opened before stays open, and safe words follow the hiding.
        c.vault.forgetOpened()
        c.familySafety.refresh()
        return true
    }

    /** Parley locked: a duress session ends (its in-memory settings changes go); the hiding stays. */
    fun locked(c: DataContainer) {
        c.settings.endDuressSession()
        c.appPin.endSession()
        Concealment.lock()
    }
}
