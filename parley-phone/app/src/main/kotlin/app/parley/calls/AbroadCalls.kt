package app.parley.calls

import app.parley.common.PhoneIdentity
import app.parley.common.calls.AssistedDial
import app.parley.data.DataContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L6: the questions a call abroad adds to [app.parley.CallGate]'s one question: the number with its country code
 * ([AssistedDial.convert]) and, once per trip, a local SIM ([AssistedDial.localSimHint]). Asked only when the SIM the
 * call goes out on is known; the gate never asks it for emergency numbers.
 */
class AbroadCalls(private val c: DataContainer) {
    data class Questions(val plan: AssistedDial.Plan? = null, val localSim: AssistedDial.LocalSimHint? = null) {
        val any: Boolean get() = plan != null || localSim != null
    }

    /** What to ask before calling [number] on [simId] (the only SIM when null and there is one). */
    suspend fun questions(number: String, simId: String?): Questions = withContext(Dispatchers.IO) {
        val cfg = c.roaming.config.value
        if ((!cfg.assistedDialling && !cfg.localSimHint) || PhoneIdentity.isServiceCode(number)) return@withContext NONE
        val sims = runCatching { c.roaming.simStates(c.sims.accounts()) }.getOrDefault(emptyList())
        if (sims.isEmpty()) return@withContext NONE
        // Home again (or elsewhere): the next trip gets its suggestion.
        if (AssistedDial.tripOver(sims, c.roaming.hintedTrip)) c.roaming.hintedTrip = null
        val sim = sims.firstOrNull { it.id == simId } ?: sims.singleOrNull() ?: return@withContext NONE
        val plan = if (cfg.assistedDialling) AssistedDial.convert(number, sim) else null
        val hint = if (cfg.localSimHint) AssistedDial.localSimHint(sims, sim.id, c.roaming.hintedTrip) else null
        // Once per trip: offered now, whatever the answer.
        if (hint != null) c.roaming.hintedTrip = hint.trip
        Questions(plan, hint)
    }

    private companion object {
        val NONE = Questions()
    }
}
