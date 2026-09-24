package app.parley.calltime

import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import app.parley.common.calltime.CallFacts
import app.parley.common.calltime.CallLimits
import app.parley.common.calltime.CallTimePlan
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope
import app.parley.common.calltime.QuotaPeriod
import app.parley.common.calltime.QuotaStatus
import app.parley.common.calltime.Quotas
import app.parley.common.calltime.UsageEntry
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Looks up what [CallLimits] needs about a call (contact, labels, SIM, history) and asks it. Used by the call
 * path through [app.parley.AppTelecomDependencies] and by the dialler before an outgoing call.
 */
class CallTimePlanner(private val c: DataContainer) {

    /** The call as the policy sees it, plus the numbers that count towards the same person's allowance. */
    private data class Subject(val facts: CallFacts, val numberKeys: Set<String>, val name: String?)

    private suspend fun subject(number: String?, accountId: String?, incoming: Boolean): Subject = withContext(Dispatchers.IO) {
        val config = c.calling.config.value
        val emergency = PhoneEnv.isEmergency(c.appContext, number)
        val info = number?.takeIf { it.isNotBlank() }?.let { runCatching { c.contacts.lookup(it) }.getOrNull() }
        val key = info?.lookupKey?.takeIf { it.isNotBlank() }
        val labelRules = config.rules.filter { it.scope == LimitScope.LABEL }
        val labels = if (info == null || labelRules.isEmpty()) emptySet() else labelRules.mapNotNull { r ->
            r.key.toLongOrNull()?.takeIf { gid -> info.contactId in runCatching { c.contacts.contactIdsInGroup(gid) }.getOrDefault(emptySet()) }
        }.toSet()
        val contact = key?.let { k -> c.contacts.contacts.value?.firstOrNull { it.lookupKey == k } }
        val keys = contact?.phones?.map { PhoneNumbers.matchKey(it.number) }?.toSet()
            ?: listOfNotNull(number?.takeIf { it.isNotBlank() }?.let { PhoneNumbers.matchKey(it) }).toSet()
        Subject(CallFacts(incoming, emergency, key, labels, accountId), keys, info?.name)
    }

    /** Allowance status of the rule that governs [s], counted from the call history. */
    private suspend fun quotas(s: Subject): List<QuotaStatus> {
        val rule = CallLimits.ruleFor(c.calling.config.value, s.facts)?.takeIf { it.hasQuota } ?: return emptyList()
        // The process may have just started for this call: give the call log a moment to load.
        val calls = c.callLog.calls.value ?: withTimeoutOrNull(LOG_WAIT_MS) { c.callLog.calls.filterNotNull().first() } ?: return emptyList()
        val usage = calls.asSequence()
            .filter { it.type == CallType.INCOMING || it.type == CallType.OUTGOING }
            .filter { e ->
                when (rule.scope) {
                    LimitScope.CONTACT, LimitScope.LABEL -> e.number.isNotBlank() && PhoneNumbers.matchKey(e.number) in s.numberKeys
                    LimitScope.SIM -> e.accountId == rule.key
                    LimitScope.GLOBAL -> true
                }
            }
            .map { UsageEntry(it.date, it.durationSec, it.type == CallType.INCOMING) }
            .toList()
        return Quotas.status(rule, usage, System.currentTimeMillis(), ZoneId.systemDefault(), WeekFields.of(Locale.getDefault()).firstDayOfWeek)
    }

    suspend fun plan(number: String?, accountId: String?, incoming: Boolean): CallTimePlan {
        val config = c.calling.config.value
        if (config.rules.isEmpty() && config.reminders.everyMinutes <= 0 && config.reminders.perContact.isEmpty()) return CallTimePlan.NONE
        val s = subject(number, accountId, incoming)
        return CallLimits.plan(config, s.facts, quotas(s))
    }

    suspend fun silenceIncoming(number: String, accountId: String?): Boolean {
        val config = c.calling.config.value
        if (!config.silenceIncomingOverQuota || config.rules.none { it.hasQuota }) return false
        val s = subject(number, accountId, incoming = true)
        return CallLimits.silenceIncoming(config, s.facts, quotas(s))
    }

    /** A sentence for the confirmation dialog when an outgoing call's allowance is used up, else null (T6). */
    suspend fun outgoingWarning(number: String, accountId: String?): String? {
        val config = c.calling.config.value
        if (config.rules.none { it.hasQuota }) return null
        val s = subject(number, accountId, incoming = false)
        val used = CallLimits.outgoingBlocker(config, s.facts, quotas(s)) ?: return null
        val period = if (used.period == QuotaPeriod.DAY) "today" else "this week"
        val who = s.name?.let { " with $it" }.orEmpty()
        return "You've used your ${used.allowanceSec / 60} minutes$who $period."
    }

    companion object {
        private const val LOG_WAIT_MS = 1500L

        /** Label for a rule's allowance, e.g. "30 min a day · 2 h a week". */
        fun allowanceText(rule: LimitRule): String = listOfNotNull(
            rule.perCallMinutes.takeIf { it > 0 }?.let { "${minutes(it)} per call" },
            rule.dailyMinutes.takeIf { it > 0 }?.let { "${minutes(it)} a day" },
            rule.weeklyMinutes.takeIf { it > 0 }?.let { "${minutes(it)} a week" },
        ).joinToString(" · ") + when {
            rule.incoming && !rule.outgoing -> " · incoming only"
            !rule.incoming && rule.outgoing -> " · outgoing only"
            else -> ""
        }

        fun minutes(m: Int): String = when {
            m % 60 == 0 -> "${m / 60} h"
            m > 60 -> "${m / 60} h ${m % 60} min"
            else -> "$m min"
        }
    }
}
