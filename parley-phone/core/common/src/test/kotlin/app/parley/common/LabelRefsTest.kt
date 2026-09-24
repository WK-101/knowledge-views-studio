package app.parley.common

import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LabelRefsTest {
    private val legacyBlock = BlockRule(id = 1, pattern = "12", type = RuleType.LABEL, label = "Family")
    private val groups = mapOf(12L to "Family & friends")

    @Test fun old_id_rules_migrate_to_the_current_title() {
        val m = LabelRefs.migrateRule(legacyBlock, groups)
        assertEquals("Family & friends", m.pattern)
        assertEquals("Family & friends", m.label)
        // Group gone: the title saved with the rule is used.
        assertEquals("Family", LabelRefs.migrateRule(legacyBlock, emptyMap()).pattern)
        // Title rules and other rules are left alone.
        val t = BlockRule(pattern = "Work", type = RuleType.LABEL, label = "Work")
        assertEquals(t, LabelRefs.migrateRule(t, groups))
        val n = BlockRule(pattern = "12", type = RuleType.EXACT)
        assertEquals(n, LabelRefs.migrateRule(n, groups))
    }

    @Test fun off_hours_and_limits_migrate() {
        val oh = OffHours(enabled = true, allow = OffHoursAllow.LABEL, labelId = 12, labelTitle = "Family")
        assertEquals(OffHours(enabled = true, allow = OffHoursAllow.LABEL, labelTitle = "Family & friends"), LabelRefs.migrateOffHours(oh, groups))
        val config = CallingConfig(rules = listOf(LimitRule(LimitScope.LABEL, "12", "Family", perCallMinutes = 5)))
        assertEquals(LimitRule(LimitScope.LABEL, "Family & friends", "Family & friends", perCallMinutes = 5), LabelRefs.migrateConfig(config, groups).rules.single())
    }

    @Test fun restore_remaps_by_title_and_drops_missing_labels() {
        // Regression: a backup's group id pointed at whatever group had that id on the new phone.
        val here = setOf("Family", "Work")
        assertEquals("Family", LabelRefs.restoreRule(legacyBlock, here)?.pattern)
        assertNull(LabelRefs.restoreRule(legacyBlock, setOf("Work")))
        assertNull(LabelRefs.restoreRule(BlockRule(pattern = "12", type = RuleType.LABEL), here))
        assertEquals("Work", LabelRefs.restoreRule(BlockRule(pattern = "Work", type = RuleType.LABEL, label = "Work"), here)?.pattern)
        val exact = BlockRule(pattern = "+331", type = RuleType.PREFIX)
        assertEquals(exact, LabelRefs.restoreRule(exact, emptySet()))
        val oh = OffHours(enabled = true, allow = OffHoursAllow.LABEL, labelId = 99, labelTitle = "Gone")
        val restored = LabelRefs.restoreOffHours(oh, here)
        assertFalse(restored.enabled)
        assertEquals(OffHoursAllow.CONTACTS, restored.allow)
        assertEquals("Family", LabelRefs.restoreOffHours(oh.copy(labelTitle = "Family"), here).labelTitle)
    }

    @Test fun rename_and_merge_move_every_reference() {
        val rules = listOf(
            BlockRule(id = 1, pattern = "A", type = RuleType.LABEL, label = "A"),
            legacyBlock.copy(id = 2, label = "B"),
            BlockRule(id = 3, pattern = "C", type = RuleType.LABEL, label = "C"),
        )
        val renamed = LabelRefs.renameRules(rules, mapOf("A" to "T", "B" to "T"))
        assertEquals(listOf("T", "T", "C"), renamed.map { it.pattern })
        val oh = OffHours(allow = OffHoursAllow.LABEL, labelTitle = "B")
        assertEquals("T", LabelRefs.renameOffHours(oh, mapOf("B" to "T")).labelTitle)
        val config = CallingConfig(
            rules = listOf(
                LimitRule(LimitScope.LABEL, "A", "A", perCallMinutes = 10),
                LimitRule(LimitScope.LABEL, "T", "T", perCallMinutes = 30, dailyMinutes = 60),
            ),
        )
        val merged = LabelRefs.renameConfig(config, mapOf("A" to "T")).rules.single()
        assertEquals("T", merged.key)
        assertEquals(10, merged.perCallMinutes)
        assertEquals(60, merged.dailyMinutes)
        // The merge target keeps its own ringtone.
        assertEquals(mapOf("T" to "t", "C" to "c"), LabelRefs.renameRingtones(mapOf("A" to "a", "T" to "t", "C" to "c"), mapOf("A" to "T")))
        assertEquals(mapOf("T" to "a"), LabelRefs.renameRingtones(mapOf("A" to "a"), mapOf("A" to "T")))
    }

    @Test fun label_ringtones_have_one_store() {
        assertEquals("b", LabelRefs.ringtoneFor(setOf("Work", "Family "), mapOf("Family" to "b", "Work" to "c")))
        assertNull(LabelRefs.ringtoneFor(setOf("Other"), mapOf("Family" to "b")))
        // Regression: "Ringtone for label" stored an allow rule, which also exempted the label from off hours.
        val dialogRule = BlockRule(id = 4, pattern = "Family", type = RuleType.LABEL, label = "Family", kind = RuleKind.ALLOW, ringtone = "tone")
        val userRule = dialogRule.copy(id = 5, pattern = "Work", label = "Work", note = "Colleagues")
        val (add, drop) = LabelRefs.liftRuleRingtones(listOf(dialogRule, userRule), mapOf("Work" to "mine"))
        assertEquals(mapOf("Family" to "tone"), add)
        assertEquals(listOf(4L), drop)
    }

    @Test fun delete_removes_limits_and_switches_label_only_off_hours_off() {
        val config = CallingConfig(rules = listOf(LimitRule(LimitScope.LABEL, "A", "A", perCallMinutes = 10), LimitRule(LimitScope.GLOBAL, perCallMinutes = 60)))
        assertEquals(LimitScope.GLOBAL, LabelRefs.deleteFromConfig(config, setOf("A")).rules.single().scope)
        val off = LabelRefs.labelGone(OffHours(enabled = true, allow = OffHoursAllow.LABEL, labelTitle = "A"))
        assertFalse(off.enabled)
    }
}
