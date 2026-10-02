package app.parley

import app.parley.calls.AbroadCalls
import app.parley.calltime.CallTimePlanner
import app.parley.common.PhoneNumbers
import app.parley.common.SimAccount
import app.parley.common.calls.EmergencyPolicy
import app.parley.data.DataContainer
import app.parley.data.EmergencyNumbers
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
    private val abroad = AbroadCalls(c)

    /**
     * The question to ask before calling [number], or null to call straight away. [simId]: the SIM the user already
     * picked (no SIM question then). [simCount]: SIMs that can call.
     */
    suspend fun check(number: String, name: String?, simCount: Int, simId: String? = null, skipConfirm: Boolean = false): PendingCall? {
        // An emergency call is never held up by a question: no confirmation, no SIM choice, no warnings, no allowance.
        if (isEmergency(number)) return null
        val settings = c.settings.current()
        // A label's SIM counts like a remembered one (the number's own choice wins).
        val remembered = simId ?: c.placer.resolveSim(number)
        val default = withContext(Dispatchers.IO) { c.sims.defaultOutgoing() }
        val chooseSim = simId == null && simCount >= 2 && remembered == null && default == null && !PhoneNumbers.isServiceCode(number)
        val confirm = settings.confirmBeforeCall && !skipConfirm
        // Contacts never get the guard's warnings (they are checked inside).
        val warnings = c.dialGuard.check(number)
        // A SIM still to be chosen changes the allowance: then it's checked once the SIM is known.
        val note = if (!chooseSim) callTime.outgoingWarning(number, remembered ?: default) else null
        // L6: abroad, the number with its country code and a local SIM (asked again in [place] once a SIM is chosen).
        val roam = if (!chooseSim) abroad.questions(number, remembered ?: default) else AbroadCalls.Questions()
        val ask = listOf(confirm, chooseSim, warnings.isNotEmpty(), note != null, roam.any).any { it }
        return if (ask) {
            PendingCall(number, name, confirm || note != null, chooseSim, note, simId, warnings, abroad = roam.plan, localSim = roam.localSim)
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
        if (isEmergency(number)) {
            // Only a SIM the user picked for this call; otherwise the platform routes it over whichever network can
            // carry it (a remembered or label SIM may have no service).
            CallManager.expectOutgoing(number, null)
            return Placed.Done(c.placer.call(EmergencyPolicy.asciiDigits(number), simId, simResolved = true))
        }
        if (remember && simId != null) c.prefs.setSimFor(number, simId)
        // Resolved once, off the main thread, and handed to the placer (which would otherwise look it up again).
        val resolved = simId ?: c.placer.resolveSim(number)
        val chosen = resolved ?: withContext(Dispatchers.IO) { c.sims.defaultOutgoing() }
        if (!confirmed) {
            val note = callTime.outgoingWarning(number, chosen)
            val roam = abroad.questions(number, chosen)
            if (note != null || roam.any) {
                return Placed.Ask(PendingCall(number, name, note != null, false, note, simId, abroad = roam.plan, localSim = roam.localSim))
            }
        }
        // "Calling via Work SIM…" until the call exists.
        CallManager.expectOutgoing(number, sims.takeIf { it.size >= 2 }?.firstOrNull { it.id == chosen }?.label)
        return Placed.Done(c.placer.call(number, resolved, simResolved = true))
    }

    /** Off the main thread: the platform check may cross into the phone process. */
    suspend fun isEmergency(number: String): Boolean = withContext(Dispatchers.IO) {
        EmergencyPolicy.Facts(emergencyNumber = EmergencyNumbers.isEmergency(c.appContext, number)).isEmergency
    }
}
