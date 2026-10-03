package app.parley.data.security

import app.parley.common.security.DuressMachine
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
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
        c.people.privateNames.endSession()
        c.appPin.endSession()
        if (next.session) {
            c.appPin.beginSession()
            // L2: Parley's own notifications posted before (missed calls with private names, reminders, notes) go.
            HiddenNotifications.clear(c.appContext)
        }
        // Nothing opened before stays open, and safe words follow the hiding.
        c.vault.forgetOpened()
        c.familySafety.refresh()
        return true
    }

    /** Parley locked: a duress session ends (its in-memory settings changes go); the hiding stays. */
    fun locked(c: DataContainer) {
        c.settings.endDuressSession()
        c.people.privateNames.endSession()
        c.appPin.endSession()
        Concealment.lock()
    }
}

/**
 * L2: at a duress unlock, Parley's own notifications in the shade could name what it now hides (a missed call from a
 * private contact, a To call reminder, an expected-call hint, a note). They are all cancelled, except ongoing ones (a
 * call in progress, a running backup), which say nothing private and couldn't be dismissed anyway. Whatever still
 * matters is posted again by its own schedule, through the hiding.
 */
object HiddenNotifications {
    fun clear(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { nm.activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            val ongoing = sbn.isOngoing || sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            if (!ongoing) runCatching { nm.cancel(sbn.tag, sbn.id) }.onFailure { Log.w("HiddenNotifications", "Couldn't cancel: ${it.javaClass.simpleName}") }
        }
    }
}
