package app.parley.calltime

import android.content.Context
import app.parley.R
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
import app.parley.telecom.ScreeningGuard
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
        // Label titles in every account (label limits are keyed by title).
        val labels = if (info == null || labelRules.isEmpty()) emptySet() else runCatching { c.people.labelsOf(info.contactId) }.getOrDefault(emptySet())
        val contact = key?.let { k -> c.contacts.contacts.value?.firstOrNull { it.lookupKey == k } }
        val keys = contact?.phones?.map { PhoneNumbers.matchKey(it.number) }?.toSet()
            ?: listOfNotNull(number?.takeIf { it.isNotBlank() }?.let { PhoneNumbers.matchKey(it) }).toSet()
        // The hour after an emergency call, and numbers listed as starting it (B23): never limited or silenced.
        val window = runCatching { ScreeningGuard.inEmergencyWindow(c.appContext) }.getOrDefault(false) ||
            (!number.isNullOrBlank() && c.settings.current().screening.emergencyExtras.any { PhoneNumbers.same(it, number, PhoneEnv.countryIso(c.appContext)) })
        Subject(CallFacts(incoming, emergency, key, labels, accountId, inEmergencyWindow = window), keys, info?.name)
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
        val plan = CallLimits.plan(config, s.facts, quotas(s))
        // The in-call screen shows where the limit comes from: in the app's language instead of CallLimits' English.
        return if (plan.source == null) plan else plan.copy(source = CallLimits.ruleFor(config, s.facts)?.let { describe(c.appContext, it) } ?: plan.source)
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
        val res = c.appContext.resources
        val minutes = (used.allowanceSec / 60).toInt()
        val day = used.period == QuotaPeriod.DAY
        return when {
            s.name != null && day -> res.getQuantityString(R.plurals.ct_used_with_today, minutes, minutes, s.name)
            s.name != null -> res.getQuantityString(R.plurals.ct_used_with_week, minutes, minutes, s.name)
            day -> res.getQuantityString(R.plurals.ct_used_today, minutes, minutes)
            else -> res.getQuantityString(R.plurals.ct_used_week, minutes, minutes)
        }
    }

    companion object {
        private const val LOG_WAIT_MS = 1500L

        /** Label for a rule's allowance, e.g. "30 min a day · 2 h a week". */
        fun allowanceText(context: Context, rule: LimitRule): String = listOfNotNull(
            rule.perCallMinutes.takeIf { it > 0 }?.let { context.getString(R.string.ct_per_call, minutes(context, it)) },
            rule.dailyMinutes.takeIf { it > 0 }?.let { context.getString(R.string.ct_a_day, minutes(context, it)) },
            rule.weeklyMinutes.takeIf { it > 0 }?.let { context.getString(R.string.ct_a_week, minutes(context, it)) },
            when {
                rule.incoming && !rule.outgoing -> context.getString(R.string.ct_incoming_only)
                !rule.incoming && rule.outgoing -> context.getString(R.string.ct_outgoing_only)
                else -> null
            },
        ).joinToString(" · ")

        fun minutes(context: Context, m: Int): String = when {
            m % 60 == 0 -> context.getString(R.string.ct_hours_short, m / 60)
            m > 60 -> context.getString(R.string.ct_hours_minutes_short, m / 60, m % 60)
            else -> context.getString(R.string.ct_minutes_short, m)
        }

        /** [CallLimits.describe] in the app's language: "Limit for Ana", shown during the call. */
        fun describe(context: Context, rule: LimitRule): String = when (rule.scope) {
            LimitScope.CONTACT -> context.getString(R.string.ct_limit_for, rule.title.ifBlank { context.getString(R.string.ct_this_contact) })
            LimitScope.LABEL -> if (rule.title.isBlank()) context.getString(R.string.ct_limit_for_a_label) else context.getString(R.string.ct_limit_for_label, rule.title)
            LimitScope.SIM -> context.getString(R.string.ct_limit_for, rule.title.ifBlank { context.getString(R.string.ct_this_sim) })
            LimitScope.GLOBAL -> context.getString(R.string.ct_limit_for_all)
        }
    }
}
