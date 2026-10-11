package app.parley.common.calls

import app.parley.common.SimAccount

/** What Android says about a SIM's line, where it says it: the network's name and the SIM's own number. */
data class SimLine(val carrier: String? = null, val number: String? = null)

/** Why a SIM is offered first in "Call with". */
enum class SimReason {
    /** Calls with this number drop or fail less on it ([SimAdvice]). */
    FEWER_DROPS,
}

data class SimSuggestion(val simId: String, val reason: SimReason)

/**
 * One SIM in the "Call with" chooser: its name as set on the phone ("Work", "Personal"), the network when that says
 * more than the name, its own number when known, its colour, whether it is roaming now, and whether it is suggested.
 */
data class SimCard(
    val id: String,
    val name: String,
    val carrier: String?,
    val number: String?,
    val color: Int,
    /** Where it sits in the phone (0 is the first slot), or -1 when Android doesn't say. */
    val slot: Int,
    val roaming: Boolean,
    val suggested: SimReason?,
)

object SimChoice {
    /**
     * The cards for [sims], in the order the SIMs sit in the phone (a SIM is always in the same place, suggested or
     * not). [lines]: carrier and number by SIM; [roaming]: SIMs roaming now; [suggestion]: the one to mark, if any.
     */
    fun cards(sims: List<SimAccount>, lines: Map<String, SimLine>, roaming: Set<String>, suggestion: SimSuggestion?): List<SimCard> =
        sims.map { s ->
            val line = lines[s.id]
            // The network's name repeats the SIM's name on many phones ("Vodafone" / "Vodafone"): said once then.
            val carrier = line?.carrier?.trim()?.takeIf { it.isNotEmpty() && !it.equals(s.label.trim(), ignoreCase = true) }
            SimCard(
                id = s.id, name = s.label, carrier = carrier, number = line?.number?.trim()?.ifEmpty { null }, color = s.color, slot = s.slotIndex,
                roaming = s.id in roaming, suggested = suggestion?.takeIf { it.simId == s.id }?.reason,
            )
        }
}
