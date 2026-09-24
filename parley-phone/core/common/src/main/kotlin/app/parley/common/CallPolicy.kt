package app.parley.common

enum class RuleType { EXACT, PREFIX, WILDCARD }

enum class BlockAction { REJECT, SILENCE }

data class BlockRule(
    val id: Long = 0,
    val pattern: String,
    val type: RuleType,
    val action: BlockAction = BlockAction.REJECT,
    val enabled: Boolean = true,
    val note: String? = null,
)

data class ScreeningSettings(
    val blockHidden: Boolean = false,
    val blockNonContacts: Boolean = false,
    val blockNeighbourSpoofing: Boolean = false,
    val blockFailedVerification: Boolean = false,
    val defaultAction: BlockAction = BlockAction.REJECT,
)

enum class Verification { PASSED, FAILED, NOT_VERIFIED }

data class IncomingCallFacts(
    val number: String?,
    val hidden: Boolean,
    val isContact: Boolean,
    val verification: Verification = Verification.NOT_VERIFIED,
    val countryIso: String? = null,
    val ownNumbers: List<String> = emptyList(),
    val inSystemBlockList: Boolean = false,
    val isEmergency: Boolean = false,
)

enum class BlockReason { SYSTEM_LIST, RULE, HIDDEN, NOT_A_CONTACT, NEIGHBOUR_SPOOF, VERIFICATION_FAILED }

sealed interface Decision {
    data object Allow : Decision
    data class Block(val action: BlockAction, val reason: BlockReason, val rule: BlockRule? = null) : Decision
}

/** Pure, offline call-screening decision. No I/O: callers gather the facts first. */
object CallPolicy {

    fun evaluate(facts: IncomingCallFacts, rules: List<BlockRule>, settings: ScreeningSettings): Decision {
        if (facts.isEmergency) return Decision.Allow
        val action = settings.defaultAction
        if (facts.hidden || facts.number.isNullOrBlank()) {
            return if (settings.blockHidden) Decision.Block(action, BlockReason.HIDDEN) else Decision.Allow
        }
        // Contacts are always allowed unless the user put them in the system block list.
        if (facts.inSystemBlockList) return Decision.Block(BlockAction.REJECT, BlockReason.SYSTEM_LIST)
        if (facts.isContact) return Decision.Allow

        rules.firstOrNull { it.enabled && ruleMatches(it, facts.number, facts.countryIso) }?.let {
            return Decision.Block(it.action, BlockReason.RULE, it)
        }
        if (settings.blockFailedVerification && facts.verification == Verification.FAILED) {
            return Decision.Block(action, BlockReason.VERIFICATION_FAILED)
        }
        if (settings.blockNeighbourSpoofing && PhoneNumbers.looksLikeNeighbourSpoof(facts.number, facts.ownNumbers, facts.countryIso)) {
            return Decision.Block(action, BlockReason.NEIGHBOUR_SPOOF)
        }
        if (settings.blockNonContacts) return Decision.Block(action, BlockReason.NOT_A_CONTACT)
        return Decision.Allow
    }

    fun ruleMatches(rule: BlockRule, number: String, countryIso: String?): Boolean {
        val candidates = candidatesFor(number, countryIso)
        return when (rule.type) {
            RuleType.EXACT -> PhoneNumbers.same(rule.pattern, number, countryIso)
            RuleType.PREFIX -> {
                val p = PhoneNumbers.clean(rule.pattern)
                p.isNotEmpty() && candidates.any { it.startsWith(p) }
            }
            RuleType.WILDCARD -> {
                val regex = wildcardRegex(rule.pattern) ?: return false
                candidates.any { regex.matches(it) }
            }
        }
    }

    /** Forms of a number a pattern may be written against: E.164, raw digits, and national form. */
    private fun candidatesFor(number: String, countryIso: String?): Set<String> {
        val out = linkedSetOf(PhoneNumbers.clean(number))
        PhoneNumbers.toE164(number, countryIso)?.let { e ->
            out += e
            val cc = countryIso?.let { CountryCodes.callingCode(it) }
            if (cc != null && e.startsWith("+$cc")) {
                val national = e.substring(cc.length + 1)
                out += national
                if (countryIso.uppercase() !in CountryCodes.NO_TRUNK_PREFIX && cc != "1") out += "0$national"
            }
        }
        return out
    }

    /** '*' = any digits, '?' = exactly one digit. Everything else is taken literally after cleaning. */
    fun wildcardRegex(pattern: String): Regex? {
        val p = pattern.trim()
        if (p.isEmpty()) return null
        val sb = StringBuilder()
        for ((i, c) in p.withIndex()) {
            when {
                c == '*' -> sb.append("[0-9]*")
                c == '?' -> sb.append("[0-9]")
                c in '0'..'9' -> sb.append(c)
                c == '+' && i == 0 -> sb.append("\\+")
                c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' -> Unit
                else -> return null
            }
        }
        return Regex(sb.toString())
    }
}
