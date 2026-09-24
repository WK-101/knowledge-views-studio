package app.parley.data

import android.content.Context
import app.parley.common.CallPolicy
import app.parley.common.Decision
import app.parley.common.IncomingCallFacts
import app.parley.common.Verification

/** Gathers the facts about an incoming call and asks the pure [CallPolicy] for a decision. */
class CallScreener(
    private val context: Context,
    private val contacts: ContactsRepository,
    private val blocks: BlockRepository,
    private val sims: SimRepository,
    private val settings: SettingsRepository,
) {
    /** True when any screening feature is on; lets the call path skip I/O entirely otherwise. */
    fun isActive(): Boolean {
        val s = settings.settings.value.screening
        return s.blockHidden || s.blockNonContacts || s.blockNeighbourSpoofing || s.blockFailedVerification ||
            blocks.rules.value.any { it.enabled } || blocks.canUseSystemList()
    }

    suspend fun screen(number: String?, hidden: Boolean, verification: Verification): Decision {
        val s = settings.settings.value.screening
        val emergency = PhoneEnv.isEmergency(context, number)
        val facts = IncomingCallFacts(
            number = number,
            hidden = hidden,
            isContact = !number.isNullOrBlank() && contacts.lookup(number) != null,
            verification = verification,
            countryIso = PhoneEnv.countryIso(context),
            ownNumbers = if (s.blockNeighbourSpoofing) sims.ownNumbers() else emptyList(),
            inSystemBlockList = !number.isNullOrBlank() && blocks.isSystemBlocked(number),
            isEmergency = emergency,
        )
        val decision = CallPolicy.evaluate(facts, blocks.enabledRules(), s)
        if (decision is Decision.Block) blocks.logBlocked(number, decision.reason.name, decision.action)
        return decision
    }
}
