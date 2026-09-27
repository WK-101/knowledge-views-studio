package app.parley.common.calltime

import app.parley.common.LabelRefs
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

/** What the policy needs to know about a call. Filled in by the app (contact lookup, labels, SIM). */
data class CallFacts(
    val incoming: Boolean,
    val isEmergency: Boolean,
    /** Lookup key of the matching contact, or null for numbers that aren't contacts. */
    val contactKey: String? = null,
    /** Titles of the labels the contact has, in any account. */
    val labelTitles: Set<String> = emptySet(),
    /** Phone-account (SIM) id the call uses, when known. */
    val accountId: String? = null,
    /**
     * Within the hour after an emergency call, or a call to a number the user listed as starting that window
     * : the emergency operator's call-back must never be limited or silenced.
     */
    val inEmergencyWindow: Boolean = false,
)

/** One connected call from the history, for allowances. */
data class UsageEntry(val dateMillis: Long, val durationSec: Long, val incoming: Boolean)

enum class QuotaPeriod { DAY, WEEK }

data class QuotaStatus(val period: QuotaPeriod, val allowanceSec: Long, val usedSec: Long) {
    val remainingSec: Long get() = (allowanceSec - usedSec).coerceAtLeast(0)
    val exhausted: Boolean get() = usedSec >= allowanceSec
}

/** Everything the call path needs to time one call. Empty for calls with nothing to time. */
data class CallTimePlan(
    /** Hard limit counted from the moment the call connected. */
    val limitMs: Long? = null,
    val warnBeforeMs: Long = 60_000,
    /** Talk-time reminder interval. */
    val reminderEveryMs: Long? = null,
    val reminderBeep: Boolean = true,
    val reminderVibrate: Boolean = true,
    /** Allowance left when the call started. Reaching it only warns; it never ends the call. */
    val quotaLeftMs: Long? = null,
    /** Where the limit comes from, shown in the call ("Limit for Family"). */
    val source: String? = null,
    /** False in supervised mode: no "+5 min" or "Don't end" during the call. */
    val canExtend: Boolean = true,
) {
    val isEmpty: Boolean get() = limitMs == null && reminderEveryMs == null && quotaLeftMs == null

    companion object {
        val NONE = CallTimePlan()
    }
}

/**
 * Call-time policy. Pure and deterministic: the clock and time zone are passed in.
 *
 * Safety rules that no setting can change:
 * - emergency calls are never limited, timed or silenced;
 * - contacts marked "never limit" are exempt from limits and allowances;
 * - allowances are computed from call dates, so they reset only when the calendar says so (never on a
 *   reboot or a clock change).
 */
object CallLimits {
    private val specificity = listOf(LimitScope.CONTACT, LimitScope.LABEL, LimitScope.SIM, LimitScope.GLOBAL)

    fun isExempt(config: CallingConfig, facts: CallFacts): Boolean =
        facts.isEmergency || facts.inEmergencyWindow || (facts.contactKey != null && facts.contactKey in config.neverLimit)

    /** The single rule that governs this call, or null. The most specific scope wins; within a scope, the strictest. */
    fun ruleFor(config: CallingConfig, facts: CallFacts): LimitRule? {
        if (isExempt(config, facts)) return null
        val candidates = config.rules.filter { !it.isEmpty && it.appliesTo(facts.incoming) && matches(it, facts) }
        for (scope in specificity) {
            val inScope = candidates.filter { it.scope == scope }
            if (inScope.isNotEmpty()) return inScope.minWith(strictest)
        }
        return null
    }

    private fun matches(rule: LimitRule, facts: CallFacts): Boolean = when (rule.scope) {
        LimitScope.CONTACT -> facts.contactKey != null && rule.key == facts.contactKey
        LimitScope.LABEL -> facts.labelTitles.any { LabelRefs.key(it) == LabelRefs.limitTitle(rule) }
        LimitScope.SIM -> facts.accountId != null && rule.key == facts.accountId
        LimitScope.GLOBAL -> true
    }

    /** Smaller limits first; "none" (0) counts as unlimited. */
    private val strictest = compareBy<LimitRule>(
        { if (it.perCallMinutes > 0) it.perCallMinutes else Int.MAX_VALUE },
        { if (it.dailyMinutes > 0) it.dailyMinutes else Int.MAX_VALUE },
        { if (it.weeklyMinutes > 0) it.weeklyMinutes else Int.MAX_VALUE },
    )

    /** Reminder interval in minutes for this call (0 = none). Emergency calls get none. */
    fun reminderMinutes(config: CallingConfig, facts: CallFacts): Int {
        if (facts.isEmergency || facts.inEmergencyWindow) return 0
        val own = facts.contactKey?.let { config.reminders.perContact[it] }
        return (own ?: config.reminders.everyMinutes).coerceAtLeast(0)
    }

    /** Builds the plan for a call from the rule, reminders and the allowance statuses of that rule. */
    fun plan(config: CallingConfig, facts: CallFacts, quotas: List<QuotaStatus> = emptyList()): CallTimePlan {
        val rule = ruleFor(config, facts)
        val reminder = reminderMinutes(config, facts)
        val quotaLeft = if (rule == null) null else quotas.minOfOrNull { it.remainingSec }
        return CallTimePlan(
            limitMs = rule?.perCallMinutes?.takeIf { it > 0 }?.let { it * 60_000L },
            warnBeforeMs = config.warnSeconds.coerceIn(10, 600) * 1000L,
            reminderEveryMs = reminder.takeIf { it > 0 }?.let { it * 60_000L },
            reminderBeep = config.reminders.beep,
            reminderVibrate = config.reminders.vibrate,
            quotaLeftMs = quotaLeft?.let { it * 1000 },
            source = rule?.let { describe(it) },
            canExtend = !config.supervised,
        )
    }

    fun describe(rule: LimitRule): String = when (rule.scope) {
        LimitScope.CONTACT -> "Limit for ${rule.title.ifBlank { "this contact" }}"
        LimitScope.LABEL -> "Limit for label ${rule.title.ifBlank { "" }}".trim()
        LimitScope.SIM -> "Limit for ${rule.title.ifBlank { "this SIM" }}"
        LimitScope.GLOBAL -> "Limit for all calls"
    }

    /** Whether an incoming call should ring silently because its allowance is used up. */
    fun silenceIncoming(config: CallingConfig, facts: CallFacts, quotas: List<QuotaStatus>): Boolean =
        facts.incoming && config.silenceIncomingOverQuota && !isExempt(config, facts) && quotas.any { it.exhausted }

    /** The used-up allowance an outgoing call should ask about, or null to call straight away. */
    fun outgoingBlocker(config: CallingConfig, facts: CallFacts, quotas: List<QuotaStatus>): QuotaStatus? =
        if (facts.incoming || isExempt(config, facts)) null else quotas.firstOrNull { it.exhausted }
}

/** Daily and weekly allowances computed from the call history. */
object Quotas {
    /**
     * Start of the current period in [zone]. "Lazily by date": this only depends on today's date, so a reboot
     * changes nothing and there is no stored counter that could be reset.
     */
    fun periodStart(now: Long, zone: ZoneId, period: QuotaPeriod, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = when (period) {
            QuotaPeriod.DAY -> today
            QuotaPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        }
        return day.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * Seconds used in the period that contains [now]. Calls dated *after* now still count: if the clock is set
     * back, calls already made today must not be forgotten.
     */
    fun usedSec(entries: List<UsageEntry>, rule: LimitRule, now: Long, zone: ZoneId, period: QuotaPeriod, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): Long {
        val start = periodStart(now, zone, period, firstDayOfWeek)
        return entries.asSequence()
            .filter { it.dateMillis >= start && it.durationSec > 0 && rule.appliesTo(it.incoming) }
            .sumOf { it.durationSec }
    }

    /** Status of each allowance the rule sets (day and/or week). [liveSec] adds a call in progress. */
    fun status(
        rule: LimitRule?,
        entries: List<UsageEntry>,
        now: Long,
        zone: ZoneId,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        liveSec: Long = 0,
    ): List<QuotaStatus> {
        if (rule == null) return emptyList()
        val out = ArrayList<QuotaStatus>(2)
        if (rule.dailyMinutes > 0) out += QuotaStatus(QuotaPeriod.DAY, rule.dailyMinutes * 60L, usedSec(entries, rule, now, zone, QuotaPeriod.DAY, firstDayOfWeek) + liveSec)
        if (rule.weeklyMinutes > 0) out += QuotaStatus(QuotaPeriod.WEEK, rule.weeklyMinutes * 60L, usedSec(entries, rule, now, zone, QuotaPeriod.WEEK, firstDayOfWeek) + liveSec)
        return out
    }

    /**
     * The calls an allowance counts: the call path's own [ledger] plus the call-history rows it doesn't have (calls
     * from before the ledger existed, or made while Parley wasn't the phone app). A history row is the same call as a
     * ledger row when it started up to [SAME_CALL_WINDOW_MS] before it (the log dates a call from when it started
     * ringing or dialling, the ledger from when it connected) and lasted about as long.
     */
    fun mergeUsage(ledger: List<UsageEntry>, history: List<UsageEntry>): List<UsageEntry> {
        if (ledger.isEmpty()) return history
        val sorted = ledger.sortedBy { it.dateMillis }
        val used = BooleanArray(sorted.size)
        val extra = history.filter { h ->
            val i = sorted.indices.firstOrNull { i ->
                val l = sorted[i]
                !used[i] && l.incoming == h.incoming && l.dateMillis - h.dateMillis in -SAME_CALL_SLACK_MS..SAME_CALL_WINDOW_MS &&
                    abs(l.durationSec - h.durationSec) <= SAME_CALL_DURATION_SLACK_SEC
            }
            if (i != null) used[i] = true
            i == null
        }
        return sorted + extra
    }

    /**
     * Supervised allowances fail closed: when the calls can't be read at all, every allowance of [rule] reads as used
     * up (the dialler then asks before calling, and over-quota calls ring silently if that is set), instead of
     * quietly allowing everything.
     */
    fun unknownUsage(rule: LimitRule?): List<QuotaStatus> = buildList {
        if (rule == null) return@buildList
        if (rule.dailyMinutes > 0) add(QuotaStatus(QuotaPeriod.DAY, rule.dailyMinutes * 60L, rule.dailyMinutes * 60L))
        if (rule.weeklyMinutes > 0) add(QuotaStatus(QuotaPeriod.WEEK, rule.weeklyMinutes * 60L, rule.weeklyMinutes * 60L))
    }

    const val SAME_CALL_WINDOW_MS = 120_000L
    const val SAME_CALL_SLACK_MS = 5_000L
    const val SAME_CALL_DURATION_SLACK_SEC = 5L

    fun describe(q: QuotaStatus): String {
        val what = if (q.period == QuotaPeriod.DAY) "today" else "this week"
        return "${q.usedSec / 60} of ${q.allowanceSec / 60} min used $what"
    }
}
