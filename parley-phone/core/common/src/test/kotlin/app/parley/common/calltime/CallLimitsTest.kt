package app.parley.common.calltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLimitsTest {
    private val global = LimitRule(LimitScope.GLOBAL, perCallMinutes = 60)
    private val sim = LimitRule(LimitScope.SIM, key = "sim2", title = "Work SIM", perCallMinutes = 30)
    private val family = LimitRule(LimitScope.LABEL, key = "7", title = "Family", perCallMinutes = 20)
    private val ana = LimitRule(LimitScope.CONTACT, key = "ana", title = "Ana", perCallMinutes = 10)

    private fun facts(incoming: Boolean = false, key: String? = "ana", labels: Set<Long> = setOf(7), account: String? = "sim2", emergency: Boolean = false) =
        CallFacts(incoming = incoming, isEmergency = emergency, contactKey = key, labelIds = labels, accountId = account)

    @Test fun most_specific_scope_wins() {
        val config = CallingConfig(rules = listOf(global, sim, family, ana))
        assertEquals(ana, CallLimits.ruleFor(config, facts()))
        assertEquals(family, CallLimits.ruleFor(config, facts(key = "bob")))
        assertEquals(sim, CallLimits.ruleFor(config, facts(key = "bob", labels = emptySet())))
        assertEquals(global, CallLimits.ruleFor(config, facts(key = null, labels = emptySet(), account = "sim1")))
    }

    @Test fun strictest_rule_within_a_scope() {
        val work = LimitRule(LimitScope.LABEL, key = "8", perCallMinutes = 5)
        val config = CallingConfig(rules = listOf(family, work))
        assertEquals(work, CallLimits.ruleFor(config, facts(key = null, labels = setOf(7, 8))))
    }

    @Test fun direction_filters_rules() {
        val outgoingOnly = ana.copy(incoming = false)
        val config = CallingConfig(rules = listOf(outgoingOnly, global))
        assertEquals(outgoingOnly, CallLimits.ruleFor(config, facts(incoming = false)))
        // An incoming call from Ana falls through to the next scope that applies.
        assertEquals(global, CallLimits.ruleFor(config, facts(incoming = true)))
    }

    @Test fun emergency_is_never_limited_timed_or_silenced() {
        val config = CallingConfig(
            rules = listOf(global.copy(dailyMinutes = 1)),
            reminders = ReminderSettings(everyMinutes = 5),
            silenceIncomingOverQuota = true,
        )
        val f = facts(key = null, emergency = true)
        assertNull(CallLimits.ruleFor(config, f))
        assertTrue(CallLimits.plan(config, f).isEmpty)
        val used = listOf(QuotaStatus(QuotaPeriod.DAY, 60, 600))
        assertFalse(CallLimits.silenceIncoming(config, f.copy(incoming = true), used))
        assertNull(CallLimits.outgoingBlocker(config, f, used))
    }

    @Test fun never_limit_contacts_are_exempt() {
        val config = CallingConfig(rules = listOf(global), neverLimit = setOf("ana"))
        assertNull(CallLimits.ruleFor(config, facts()))
        assertNotNull(CallLimits.ruleFor(config, facts(key = "bob")))
    }

    @Test fun empty_rules_are_not_stored() {
        val config = CallingConfig().withRule(ana).withRule(ana.copy(perCallMinutes = 0))
        assertTrue(config.rules.isEmpty())
    }

    @Test fun plan_carries_limit_reminder_and_supervision() {
        val config = CallingConfig(rules = listOf(ana), warnSeconds = 30, supervised = true, reminders = ReminderSettings(everyMinutes = 15, perContact = mapOf("ana" to 5)))
        val p = CallLimits.plan(config, facts(), listOf(QuotaStatus(QuotaPeriod.DAY, 1800, 600)))
        assertEquals(600_000L, p.limitMs)
        assertEquals(30_000L, p.warnBeforeMs)
        assertEquals(300_000L, p.reminderEveryMs)
        assertEquals(1_200_000L, p.quotaLeftMs)
        assertFalse(p.canExtend)
        assertEquals("Limit for Ana", p.source)
    }

    @Test fun per_contact_reminder_can_switch_reminders_off() {
        val config = CallingConfig(reminders = ReminderSettings(everyMinutes = 15, perContact = mapOf("ana" to 0)))
        assertEquals(0, CallLimits.reminderMinutes(config, facts()))
        assertEquals(15, CallLimits.reminderMinutes(config, facts(key = "bob")))
    }

    @Test fun used_allowance_asks_before_outgoing_and_can_silence_incoming() {
        val config = CallingConfig(rules = listOf(ana.copy(dailyMinutes = 30)), silenceIncomingOverQuota = true)
        val used = listOf(QuotaStatus(QuotaPeriod.DAY, 1800, 1800))
        assertNotNull(CallLimits.outgoingBlocker(config, facts(), used))
        assertTrue(CallLimits.silenceIncoming(config, facts(incoming = true), used))
        assertFalse(CallLimits.silenceIncoming(config.copy(silenceIncomingOverQuota = false), facts(incoming = true), used))
        assertNull(CallLimits.outgoingBlocker(config, facts(), listOf(QuotaStatus(QuotaPeriod.DAY, 1800, 1799))))
    }

    @Test fun config_json_round_trip_and_damaged_file() {
        val config = CallingConfig(rules = listOf(ana, family), neverLimit = setOf("x"), reminders = ReminderSettings(7, perContact = mapOf("a" to 3)))
        assertEquals(config, CallingJson.decode(CallingJson.encode(config)))
        assertEquals(CallingConfig(), CallingJson.decode("{not json"))
        assertEquals(CallingConfig(), CallingJson.decode(null))
    }
}
