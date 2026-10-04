package app.parley.common.blocking

import app.parley.common.BlockRule
import app.parley.common.PhoneIdentity
import app.parley.common.RuleKind
import app.parley.common.RuleType

/**
 * The decisions behind every "Block" and "Unblock" in Parley (Recents, the contact page, number history, the post-call
 * card, notifications and blocking suggestions), so each place does the same thing.
 *
 * A number is blocked when it is on Android's blocked list or an enabled exact Parley rule blocks it. Blocking puts it
 * on Android's list when Parley may write there (it is the default phone app), and otherwise writes a Parley rule,
 * which works while Parley screens calls. An "Always allow" rule for the same number would contradict the block, so
 * it is lifted (and comes back with Undo). Unblocking takes away exactly what makes the number blocked here: the
 * system entry and the exact rules. Wider rules (a prefix, a pattern, a list) are not this number's own and stay.
 */
object BlockPlan {
    /** What is in place for one number now. */
    data class Now(
        val number: String,
        val onSystemList: Boolean,
        /** Enabled exact block rules for this number. */
        val blockRules: List<BlockRule>,
        /** Enabled exact allow rules for this number. */
        val allowRules: List<BlockRule>,
    ) {
        val blocked: Boolean get() = onSystemList || blockRules.isNotEmpty()
    }

    /** Where a new block is written. */
    enum class Where { SYSTEM_LIST, PARLEY_RULE }

    data class Block(val number: String, val where: Where, val liftAllows: List<BlockRule>)

    data class Unblock(val number: String, val fromSystemList: Boolean, val deleteRules: List<BlockRule>)

    /** Enabled exact rules of [kind] for [number] (in any stored form of it). */
    fun exactRules(rules: List<BlockRule>, number: String, region: String?, kind: RuleKind): List<BlockRule> =
        rules.filter { it.enabled && it.kind == kind && it.type == RuleType.EXACT && PhoneIdentity.same(it.pattern, number, region) }

    /** What is in place for [number], from Android's list ([systemNumbers]) and Parley's [rules]. */
    fun now(number: String, systemNumbers: List<String>, rules: List<BlockRule>, region: String?): Now = Now(
        number = number,
        onSystemList = systemNumbers.any { PhoneIdentity.same(it, number, region) },
        blockRules = exactRules(rules, number, region, RuleKind.BLOCK),
        allowRules = exactRules(rules, number, region, RuleKind.ALLOW),
    )

    /** What blocking changes; null when the number is already blocked (the place shows Unblock instead). */
    fun block(now: Now, systemListUsable: Boolean): Block? {
        if (now.blocked || now.number.isBlank()) return null
        return Block(now.number, if (systemListUsable) Where.SYSTEM_LIST else Where.PARLEY_RULE, now.allowRules)
    }

    /** What unblocking changes; null when nothing of this number's own blocks it. */
    fun unblock(now: Now): Unblock? = if (!now.blocked) null else Unblock(now.number, now.onSystemList, now.blockRules)
}
