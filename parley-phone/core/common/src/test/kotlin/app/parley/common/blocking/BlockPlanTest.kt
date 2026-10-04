package app.parley.common.blocking

import app.parley.common.BlockRule
import app.parley.common.RuleKind
import app.parley.common.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockPlanTest {
    private val n = "+44 20 7946 0000"
    private fun rule(id: Long, pattern: String, kind: RuleKind = RuleKind.BLOCK, type: RuleType = RuleType.EXACT, enabled: Boolean = true) =
        BlockRule(id = id, pattern = pattern, type = type, kind = kind, enabled = enabled)

    @Test fun nothing_in_place_is_not_blocked() {
        val now = BlockPlan.now(n, emptyList(), emptyList(), "GB")
        assertFalse(now.blocked)
        assertNull(BlockPlan.unblock(now))
    }

    @Test fun system_list_entry_in_another_form_counts() {
        val now = BlockPlan.now(n, listOf("020 7946 0000"), emptyList(), "GB")
        assertTrue(now.onSystemList)
        assertTrue(now.blocked)
        assertNull(BlockPlan.block(now, systemListUsable = true))
    }

    @Test fun exact_rule_counts_but_disabled_and_wider_rules_dont() {
        val rules = listOf(
            rule(1, "+442079460000", enabled = false),
            rule(2, "+44207946", type = RuleType.PREFIX),
            rule(3, "+4420794600*", type = RuleType.WILDCARD),
        )
        assertFalse(BlockPlan.now(n, emptyList(), rules, "GB").blocked)
        val withExact = BlockPlan.now(n, emptyList(), rules + rule(4, "02079460000"), "GB")
        assertTrue(withExact.blocked)
        assertEquals(listOf(4L), withExact.blockRules.map { it.id })
    }

    @Test fun block_goes_to_system_list_when_parley_may_write_there() {
        val plan = BlockPlan.block(BlockPlan.now(n, emptyList(), emptyList(), "GB"), systemListUsable = true)!!
        assertEquals(BlockPlan.Where.SYSTEM_LIST, plan.where)
        assertTrue(plan.liftAllows.isEmpty())
    }

    @Test fun block_writes_a_rule_without_the_phone_app_role() {
        val plan = BlockPlan.block(BlockPlan.now(n, emptyList(), emptyList(), "GB"), systemListUsable = false)!!
        assertEquals(BlockPlan.Where.PARLEY_RULE, plan.where)
    }

    @Test fun block_lifts_this_numbers_always_allow_only() {
        val rules = listOf(
            rule(7, "+442079460000", kind = RuleKind.ALLOW),
            rule(8, "+447700900123", kind = RuleKind.ALLOW),
            rule(9, "+44207946", kind = RuleKind.ALLOW, type = RuleType.PREFIX),
        )
        val plan = BlockPlan.block(BlockPlan.now(n, emptyList(), rules, "GB"), systemListUsable = true)!!
        assertEquals(listOf(7L), plan.liftAllows.map { it.id })
    }

    @Test fun unblock_takes_away_both_the_entry_and_the_exact_rules() {
        val rules = listOf(rule(4, "+442079460000"), rule(5, "02079460000"), rule(6, "+44207946", type = RuleType.PREFIX))
        val plan = BlockPlan.unblock(BlockPlan.now(n, listOf(n), rules, "GB"))!!
        assertTrue(plan.fromSystemList)
        assertEquals(listOf(4L, 5L), plan.deleteRules.map { it.id })
    }

    @Test fun blank_number_is_never_blocked() {
        assertNull(BlockPlan.block(BlockPlan.now("", emptyList(), emptyList(), "GB"), systemListUsable = true))
    }
}
