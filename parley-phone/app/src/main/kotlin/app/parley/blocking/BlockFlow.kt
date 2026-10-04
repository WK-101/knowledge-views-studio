package app.parley.blocking

import app.parley.common.BlockRule
import app.parley.common.RuleTools
import app.parley.common.RuleType
import app.parley.common.blocking.BlockPlan
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.Permissions
import app.parley.common.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one Block and Unblock behind every place that offers it (Recents, the contact page, number history, the
 * post-call card, missed-call notifications, blocking suggestions and swipes). [BlockPlan] decides; this applies the
 * decision and hands back the exact way back, so Undo never lifts a block or an allowance that was there before.
 */
object BlockFlow {
    /** The numbers that changed and how to put everything back as it was. */
    class Done(val numbers: List<String>, val undo: suspend () -> Unit)

    /**
     * Android's list takes Parley's entries only while Parley is the default phone app; reading it needs the same
     * role, so otherwise only Parley's rules are known (and used).
     */
    fun systemListUsable(c: DataContainer): Boolean = Permissions.isDefaultDialer(c.appContext) && c.blocks.canUseSystemList()

    suspend fun now(c: DataContainer, number: String): BlockPlan.Now {
        val system = if (systemListUsable(c)) withContext(Dispatchers.IO) { c.blocks.loadSystemNow().map { it.number } } else emptyList()
        return BlockPlan.now(number, system, c.blocks.allRules(), PhoneEnv.countryIso(c.appContext))
    }

    /** What blocking [numbers] would change, for the confirmation (numbers already blocked are left out). */
    suspend fun plans(c: DataContainer, numbers: List<String>): List<BlockPlan.Block> {
        val usable = systemListUsable(c)
        return numbers.distinct().mapNotNull { BlockPlan.block(now(c, it), usable) }
    }

    /** Blocks [numbers] (a [note] goes on any rule written). Numbers already blocked are left as they are. */
    suspend fun block(c: DataContainer, numbers: List<String>, note: String? = null): Done {
        val undo = ArrayList<suspend () -> Unit>()
        val done = ArrayList<String>()
        for (plan in plans(c, numbers)) {
            val n = plan.number
            plan.liftAllows.forEach { r ->
                c.blocks.deleteRule(r.id)
                undo += { c.blocks.saveRule(r) }
            }
            // The role can go between the check and the write: a rule then does the job.
            val onSystem = plan.where == BlockPlan.Where.SYSTEM_LIST && c.blocks.blockNumber(n)
            if (onSystem) {
                undo += { c.blocks.unblockNumber(n) }
            } else {
                val iso = PhoneEnv.countryIso(c.appContext)
                val id = c.blocks.saveRule(BlockRule(pattern = RuleTools.check(n, RuleType.EXACT, iso).pattern, type = RuleType.EXACT, note = note))
                undo += { c.blocks.deleteRule(id) }
            }
            done += n
        }
        return Done(done) { undo.asReversed().forEach { suspendRunCatching { it() } } }
    }

    /** Unblocks [numbers]: their entries on Android's list and their exact rules. */
    suspend fun unblock(c: DataContainer, numbers: List<String>): Done {
        val undo = ArrayList<suspend () -> Unit>()
        val done = ArrayList<String>()
        for (n in numbers.distinct()) {
            val plan = BlockPlan.unblock(now(c, n)) ?: continue
            if (plan.fromSystemList) {
                c.blocks.unblockNumber(n)
                undo += { c.blocks.blockNumber(n) }
            }
            plan.deleteRules.forEach { r ->
                c.blocks.deleteRule(r.id)
                undo += { c.blocks.saveRule(r) }
            }
            done += n
        }
        return Done(done) { undo.asReversed().forEach { suspendRunCatching { it() } } }
    }
}
