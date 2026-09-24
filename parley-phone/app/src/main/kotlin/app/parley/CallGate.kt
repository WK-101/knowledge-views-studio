package app.parley

import app.parley.calltime.CallTimePlanner
import app.parley.common.PhoneNumbers
import app.parley.common.SimAccount
import app.parley.data.DataContainer
import app.parley.data.PlaceResult
import app.parley.telecom.CallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one path every outgoing call takes: keypad, Recents, contacts, "call with SIM", and numbers other apps hand
 * to Parley. The dial guard's warnings, a used-up call-time allowance, "confirm before calling" and the SIM
 * choice are asked in a single [PendingCall] (shown by `ui.common.CallQuestions`), then the call is placed.
 * Used by [AppViewModel] and by activities that have no view model ([app.parley.messaging.NumberActionActivity]).
 */
class CallGate(private val c: DataContainer) {
    private val callTime = CallTimePlanner(c)

    /**
     * The question to ask before calling [number], or null to call straight away. [simId]: the SIM the user already
     * picked (no SIM question then). [simCount]: SIMs that can call.
     */
    suspend fun check(number: String, name: String?, simCount: Int, simId: String? = null, skipConfirm: Boolean = false): PendingCall? {
        val settings = c.settings.current()
        val remembered = simId ?: withContext(Dispatchers.IO) { c.prefs.simFor(number) }
        val default = withContext(Dispatchers.IO) { c.sims.defaultOutgoing() }
        val chooseSim = simId == null && simCount >= 2 && remembered == null && default == null && !PhoneNumbers.isServiceCode(number)
        val confirm = settings.confirmBeforeCall && !skipConfirm
        // Contacts never get the guard's warnings (they are checked inside); emergency numbers are skipped too.
        val warnings = c.dialGuard.check(number)
        // A SIM still to be chosen changes the allowance: then it's checked once the SIM is known.
        val note = if (!chooseSim) callTime.outgoingWarning(number, remembered ?: default) else null
        return if (confirm || chooseSim || warnings.isNotEmpty() || note != null) {
            PendingCall(number, name, confirm || note != null, chooseSim, note, simId, warnings)
        } else {
            null
        }
    }

    /** What [place] did: placed the call (with its result), or needs the user to answer [Ask.pending] first. */
    sealed interface Placed {
        data class Ask(val pending: PendingCall) : Placed
        data class Done(val result: PlaceResult) : Placed
    }

    /** Places the call. [confirmed]: the user already said yes to everything the gate asked, the allowance included. */
    suspend fun place(number: String, simId: String?, name: String?, sims: List<SimAccount>, remember: Boolean = false, confirmed: Boolean = false): Placed {
        if (remember && simId != null) c.prefs.setSimFor(number, simId)
        val chosen = simId ?: withContext(Dispatchers.IO) { c.prefs.simFor(number) ?: c.sims.defaultOutgoing() }
        if (!confirmed) {
            callTime.outgoingWarning(number, chosen)?.let { note -> return Placed.Ask(PendingCall(number, name, true, false, note, simId)) }
        }
        // "Calling via Work SIM…" until the call exists (A10).
        CallManager.expectOutgoing(number, sims.takeIf { it.size >= 2 }?.firstOrNull { it.id == chosen }?.label)
        return Placed.Done(c.placer.call(number, simId))
    }
}
