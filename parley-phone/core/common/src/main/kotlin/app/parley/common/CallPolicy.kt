package app.parley.common

import app.parley.common.spam.Reputation
import app.parley.common.calls.ExpectedCalls
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.ExpectedWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** How a number-type rule matches. Number rules: EXACT, PREFIX, WILDCARD. The rest match other facts. */
enum class RuleType {
    EXACT, PREFIX, WILDCARD,

    /** Caller name sent by the network (CNAP) contains the pattern (case-insensitive). */
    CALLER_NAME,

    /** Number is from one of the regions in the pattern ("GB, IE"). */
    REGION,

    /** Number is from any region other than the SIM's country. Pattern unused. */
    NOT_MY_REGION,

    /** Number is of a line type ([LineType] names, comma separated). */
    LINE_TYPE,

    /**
     * Contact has the label whose title is the pattern, in any account (the title is also kept in
     * [BlockRule.label]). Rules saved by older versions kept the device-local group id in the pattern: see
     * [BlockRule.labelKey].
     */
    LABEL,
    ;

    val isNumberRule: Boolean get() = this == EXACT || this == PREFIX || this == WILDCARD
}

/** Whether a rule lets a call through or stops it. Allow always wins over block (fixed precedence). */
enum class RuleKind { BLOCK, ALLOW }

enum class BlockAction { REJECT, SILENCE }

/** Per-rule notification override. DEFAULT = use the global setting of the verdict's channel. */
enum class NotifyLevel { DEFAULT, NONE, QUIET, NORMAL }

/** libphonenumber's number types, mirrored here so the policy stays pure. */
enum class LineType { MOBILE, FIXED_LINE, FIXED_LINE_OR_MOBILE, TOLL_FREE, PREMIUM_RATE, SHARED_COST, VOIP, PERSONAL_NUMBER, PAGER, UAN, VOICEMAIL, UNKNOWN }

enum class NumberValidity { VALID, INVALID, IMPOSSIBLE, UNKNOWN }

data class BlockRule(
    val id: Long = 0,
    val pattern: String,
    val type: RuleType,
    val action: BlockAction = BlockAction.REJECT,
    val enabled: Boolean = true,
    val note: String? = null,
    val kind: RuleKind = RuleKind.BLOCK,
    /** Phone-account id of the SIM this rule is limited to (null = any SIM). Needs Parley as the phone app. */
    val simId: String? = null,
    /** When the rule is active (null = always). */
    val schedule: Schedule? = null,
    val notify: NotifyLevel = NotifyLevel.DEFAULT,
    /** Ringtone Parley's own ringer plays when this allow rule (or label) lets a call through. */
    val ringtone: String? = null,
    /** Temporary rule ("Allow for 24 h"): ignored after this time and cleaned up later. */
    val expiresAt: Long? = null,
    val hitCount: Int = 0,
    val lastHitAt: Long? = null,
    /** Label title for [RuleType.LABEL] rules, shown in the UI. */
    val label: String? = null,
) {
    val title: String get() = note?.takeIf { it.isNotBlank() } ?: when (type) {
        RuleType.LABEL -> "Label ${label ?: pattern}"
        RuleType.NOT_MY_REGION -> "Not from my country"
        RuleType.CALLER_NAME -> "Name contains \"$pattern\""
        RuleType.REGION -> "From $pattern"
        RuleType.LINE_TYPE -> pattern.split(',').joinToString(", ") { lineTypeLabel(it.trim()) }
        else -> pattern
    }

    fun isLive(nowMillis: Long): Boolean = enabled && (expiresAt == null || expiresAt > nowMillis)

    /**
     * The label title a [RuleType.LABEL] rule applies to, or null for other rules. Older rules stored the group
     * row id in [pattern] and the title in [label]; those are read by their title.
     */
    val labelKey: String? get() = if (type != RuleType.LABEL) null else LabelRefs.ruleTitle(pattern, label)
}

fun lineTypeLabel(name: String): String = when (name) {
    "VOIP" -> "Internet calls (VoIP)"
    "PREMIUM_RATE" -> "Premium rate"
    "SHARED_COST" -> "Shared cost"
    "TOLL_FREE" -> "Toll free"
    "PERSONAL_NUMBER" -> "Personal numbers"
    "UAN" -> "Company numbers (UAN)"
    else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** Who may still ring during off hours. */
enum class OffHoursAllow { CONTACTS, FAVOURITES, LABEL }

/** Global "Off hours": outside these people, calls are silenced on a schedule ("Only contacts at night"). */
@Serializable
data class OffHours(
    val enabled: Boolean = false,
    val schedule: Schedule = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60),
    val allow: OffHoursAllow = OffHoursAllow.CONTACTS,
    /** Group row id stored by older versions; device-local, so only [labelTitle] is used now. */
    val labelId: Long? = null,
    /** Title of the label that may ring (all accounts). */
    val labelTitle: String? = null,
    val action: BlockAction = BlockAction.SILENCE,
)

@Serializable
data class ScreeningSettings(
    val blockHidden: Boolean = false,
    val blockNonContacts: Boolean = false,
    val blockNeighbourSpoofing: Boolean = false,
    val blockFailedVerification: Boolean = false,
    val defaultAction: BlockAction = BlockAction.REJECT,
    /** Numbers libphonenumber says can't exist or aren't assigned for their region. */
    val blockInvalid: Boolean = false,
    val invalidAction: BlockAction = BlockAction.SILENCE,
    // Per-toggle schedules (null = always).
    val hiddenSchedule: Schedule? = null,
    val nonContactsSchedule: Schedule? = null,
    val neighbourSchedule: Schedule? = null,
    val verificationSchedule: Schedule? = null,
    val invalidSchedule: Schedule? = null,
    val offHours: OffHours = OffHours(),
    // Repeat callers and "people you talked to".
    val repeatCallers: Boolean = true,
    val repeatWindowMinutes: Int = 3,
    /** Redials faster than this don't count as a repeat (bots redial instantly). */
    val repeatMinIntervalSeconds: Int = 20,
    val allowDialled: Boolean = false,
    val dialledDays: Int = 30,
    val allowAnswered: Boolean = false,
    val answeredMinSeconds: Int = 30,
    val answeredDays: Int = 30,
    /** "Expecting a call": unknown callers ring until this time. */
    val snoozeUntil: Long = 0,
    /** Numbers that start the emergency window when you call them (a GP, a school). */
    val emergencyExtras: List<String> = emptyList(),
    // Default notification level per verdict.
    val notifyBlocked: NotifyLevel = NotifyLevel.QUIET,
    val notifyReported: NotifyLevel = NotifyLevel.QUIET,
    val notifyLikelySpam: NotifyLevel = NotifyLevel.NORMAL,
    val busyReply: Boolean = false,
    val busyReplyText: String = "In a meeting. I'll call you back.",
    val ringLoudFavourites: Boolean = false,
    val ringLoudRepeat: Boolean = false,
    val repeatRingtone: String? = null,
    val likelySpamRingtone: String? = null,
    val reputationSuggestions: Boolean = true,
    /** I2 "Learn from your calls": tag numbers that look like sales lines from your own history (tags only). */
    val learnFromCalls: Boolean = true,
    /** I2: silence numbers that look like sales lines (your calls). Off by default; soft, below lists. */
    val silenceSalesLines: Boolean = false,
    val webSearchUrl: String = "https://duckduckgo.com/?q=",
    /**
     * I7: "Expecting a call" windows from notes, the To call list and delivery QR codes. Never stored here: they are
     * kept sealed in their own store and handed to the policy for each call.
     */
    @Transient
    val expected: List<ExpectedWindow> = emptyList(),
) {
    fun snoozeActive(nowMillis: Long) = snoozeUntil > nowMillis

    fun encode(): String = CODEC.encodeToString(serializer(), this)

    companion object {
        private val CODEC = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Reads settings stored by [encode]; unknown or broken input gives the defaults. */
        fun decode(json: String?): ScreeningSettings =
            if (json.isNullOrBlank()) ScreeningSettings() else runCatching { CODEC.decodeFromString(serializer(), json) }.getOrDefault(ScreeningSettings())
    }
}

enum class Verification { PASSED, FAILED, NOT_VERIFIED }

enum class ListMode { WARN, BLOCK }

/** A spam-list pack entry that matched the caller. */
data class ListHit(
    val packId: String,
    val packName: String,
    val category: String?,
    /** 0–100. */
    val score: Int,
    val mode: ListMode = ListMode.WARN,
    /** Block only at or above this score (when [mode] is BLOCK). */
    val threshold: Int = 50,
    val action: BlockAction = BlockAction.REJECT,
    val notify: NotifyLevel = NotifyLevel.DEFAULT,
    /** Matched a range (prefix), not an exact number. */
    val range: Boolean = false,
    /** Pack hasn't been updated within its time-to-live. */
    val stale: Boolean = false,
) {
    val blocks: Boolean get() = mode == ListMode.BLOCK && score >= threshold
}

/** A past call with this number, from the call log (for "numbers you called / talked to" and repeats). */
data class PastCall(val time: Long, val outgoing: Boolean, val durationSec: Long)

data class IncomingCallFacts(
    val number: String?,
    val hidden: Boolean,
    val isContact: Boolean,
    val verification: Verification = Verification.NOT_VERIFIED,
    val countryIso: String? = null,
    val ownNumbers: List<String> = emptyList(),
    val inSystemBlockList: Boolean = false,
    val isEmergency: Boolean = false,
    /** Contacts (or the vault) couldn't be checked; [isContact] is true because screening fails open. */
    val contactLookupFailed: Boolean = false,
    val contactStarred: Boolean = false,
    /** Titles of the contact's labels, in every account (compared with [LabelRefs.key]). */
    val contactLabels: Set<String> = emptySet(),
    /** Label lookup failed: label block rules are skipped (fail open). */
    val labelLookupFailed: Boolean = false,
    /** Caller name sent by the network (CNAP). */
    val callerName: String? = null,
    /** Phone-account id of the SIM the call came in on; null when unknown (screening service). */
    val simId: String? = null,
    val region: String? = null,
    val lineType: LineType = LineType.UNKNOWN,
    val validity: NumberValidity = NumberValidity.UNKNOWN,
    val listHits: List<ListHit> = emptyList(),
    val listLookupFailed: Boolean = false,
    /** Earlier calls with this number (both directions), newest first. */
    val history: List<PastCall> = emptyList(),
    /** Earlier incoming attempts from this number that Parley blocked (ms). */
    val blockedAttempts: List<Long> = emptyList(),
    val inEmergencyWindow: Boolean = false,
    /** I2: what your own calls say about this number or its range (looked up, never worked out on the call path). */
    val reputation: Reputation? = null,
)

enum class BlockReason {
    SYSTEM_LIST, RULE, HIDDEN, NOT_A_CONTACT, NEIGHBOUR_SPOOF, VERIFICATION_FAILED,
    LIST, INVALID_NUMBER, OFF_HOURS,

    /** I2: looks like a sales line from your own calls, with "Silence numbers that look like sales lines" on. */
    PERSONAL_REPUTATION,

    /**
     * A private contact's "Send to voicemail": declined by Parley's screening (Android can't do it, it never sees
     * private contacts). Not a block: never logged or notified as one.
     */
    SEND_TO_VOICEMAIL,
    ;

    /** Soft reasons can be overridden by a repeat caller; explicit choices (rules, lists you block) never. */
    val soft: Boolean get() = this in setOf(NOT_A_CONTACT, NEIGHBOUR_SPOOF, VERIFICATION_FAILED, LIST, INVALID_NUMBER, OFF_HOURS, PERSONAL_REPUTATION)
}

sealed interface Decision {
    data object Allow : Decision
    data class Block(val action: BlockAction, val reason: BlockReason, val rule: BlockRule? = null) : Decision
}

/** Why an unknown caller was let through. */
enum class AllowReason { EMERGENCY, CONTACT, RULE, SNOOZE, DIALLED, ANSWERED, REPEAT, DEFAULT }

enum class TraceMark { PASS, MATCH, FAILED_OPEN, SKIPPED }

/** One evaluated step: "Contact? → no". */
data class TraceStep(val check: String, val result: String, val mark: TraceMark = TraceMark.PASS)

enum class VerdictKind { BLOCKED, REPORTED, LIKELY_SPAM, ALLOWED }

data class Verdict(val kind: VerdictKind, val text: String)

data class ScreeningResult(
    val decision: Decision,
    val trace: List<TraceStep>,
    val verdict: Verdict? = null,
    val allowedBy: AllowReason? = null,
    /** Rule that decided (allow or block), for hit counters. */
    val rule: BlockRule? = null,
    val listHit: ListHit? = null,
    val ringtone: String? = null,
    val ringLoud: Boolean = false,
    val notify: NotifyLevel = NotifyLevel.DEFAULT,
    /** [ringtone] is the caller's own (a private contact's, which Parley's ringer plays since Android can't). */
    val contactTone: Boolean = false,
    /**
     * Let through only because an allow rule limited to one SIM may apply and the SIM isn't known here (the
     * screening service). The InCallService, which knows the SIM, must screen the call again.
     */
    val deferredToSim: Boolean = false,
    /** Why the call rings although screening would otherwise have blocked or silenced it (null when nothing would have). */
    val rangThrough: RangThrough? = null,
    /** I2: the caller looks like a sales line from your own calls (the quiet tag and its "Why?"); null otherwise. */
    val reputation: Reputation? = null,
) {
    val failedOpen: Boolean get() = trace.any { it.mark == TraceMark.FAILED_OPEN }
    val blocked: Boolean get() = decision is Decision.Block
}

/** What let a call ring that screening would otherwise have blocked or silenced. */
enum class RangThroughKind {
    /** A repeat caller overrode a soft reason; [RangThrough.calls] calls within [RangThrough.minutes] minutes. */
    REPEAT_CALLER,

    /** "Expecting a call" is on. */
    EXPECTING,

    /** An allow rule ([RangThrough.name]); temporary ones end at [RangThrough.until]. */
    ALLOW_RULE,

    /** An allow rule for the contact's label [RangThrough.name], during off hours. */
    LABEL,

    /** You called this number recently. */
    DIALLED,

    /** You talked to this number recently. */
    ANSWERED,
}

/** Why a call rang through (the incoming screen words it: "Rang through: called twice in 3 min"). */
data class RangThrough(
    val kind: RangThroughKind,
    val calls: Int = 0,
    val minutes: Int = 0,
    val name: String? = null,
    val until: Long? = null,
    /** I7: for [RangThroughKind.EXPECTING], the hint that turned it on ([name] then names where it came from). */
    val expected: ExpectedSource? = null,
)

/** "Now" for the policy: injected so schedules are testable and replays use the call's own time. */
data class PolicyClock(val millis: Long, val day: DayOfWeek, val minuteOfDay: Int) {
    companion object {
        fun of(millis: Long, zone: ZoneId = ZoneId.systemDefault()): PolicyClock {
            val t = Instant.ofEpochMilli(millis).atZone(zone)
            return PolicyClock(millis, t.dayOfWeek, t.hour * 60 + t.minute)
        }
    }
}

/**
 * Pure, offline call-screening decision. No I/O: callers gather the facts first.
 *
 * Fixed precedence (never numeric priorities):
 * emergency › contacts & vault › allow rules (incl. snooze, dialled/answered) › block rules › lists › default toggles.
 * A repeat caller overrides soft reasons only (lists, toggles, off hours), never an explicit rule, and
 * redials faster than the minimum interval don't count.
 */
object CallPolicy {

    fun evaluate(facts: IncomingCallFacts, rules: List<BlockRule>, settings: ScreeningSettings, clock: PolicyClock = PolicyClock.of(System.currentTimeMillis())): Decision =
        decide(facts, rules, settings, clock).decision

    fun decide(facts: IncomingCallFacts, rules: List<BlockRule>, settings: ScreeningSettings, clock: PolicyClock): ScreeningResult {
        val e = Evaluation(facts, rules, settings, clock)
        val r = e.run().withReputation(facts, settings)
        val why = rangThrough(r, e) { Evaluation(facts, rules, settings, clock, withoutExceptions = true).run() } ?: return r
        return r.copy(rangThrough = why)
    }

    /**
     * I2: the quiet tag rides along with the decision for unknown callers only (never contacts, emergency calls or a
     * call an allow rule or "Expecting a call" let through), and only while "Learn from your calls" is on.
     */
    private fun ScreeningResult.withReputation(f: IncomingCallFacts, s: ScreeningSettings): ScreeningResult {
        val rep = f.reputation?.takeIf { it.looksLikeSales } ?: return this
        if (!s.learnFromCalls || f.isContact || f.hidden) return this
        if (f.isEmergency || f.inEmergencyWindow) return this
        if (allowedBy == AllowReason.RULE || allowedBy == AllowReason.SNOOZE || allowedBy == AllowReason.EMERGENCY) return this
        return copy(reputation = rep)
    }

    /**
     * P1: why an allowed call rang although screening would otherwise have kept it quiet. A repeat caller always
     * overrode a block; for the other exceptions (allow rules, "Expecting a call", numbers you called or talked to,
     * a label allowed in off hours) the call is screened again [without] them, and only a block there counts.
     */
    private fun rangThrough(r: ScreeningResult, e: Evaluation, without: () -> ScreeningResult): RangThrough? {
        if (r.decision != Decision.Allow || r.deferredToSim) return null
        if (r.allowedBy == AllowReason.REPEAT) return RangThrough(RangThroughKind.REPEAT_CALLER, calls = e.repeatCalls, minutes = e.repeatMinutes)
        val kind = exceptionKind(r) ?: return null
        if (without().decision !is Decision.Block) return null
        val rule = r.rule
        return when (kind) {
            RangThroughKind.ALLOW_RULE -> RangThrough(kind, name = rule?.title, until = rule?.expiresAt)
            RangThroughKind.LABEL -> RangThrough(kind, name = rule?.label?.takeIf { it.isNotBlank() } ?: rule?.title)
            // Turned on by hand wins over a hint: the hint is named only when it alone let the call through.
            RangThroughKind.EXPECTING -> e.expectedHit?.takeIf { !e.s.snoozeActive(e.clock.millis) }
                ?.let { RangThrough(kind, name = it.label, expected = it.source) } ?: RangThrough(kind)
            else -> RangThrough(kind)
        }
    }

    /** The exception that let an allowed call through, or null when it rang for no special reason. */
    private fun exceptionKind(r: ScreeningResult): RangThroughKind? = when (r.allowedBy) {
        AllowReason.SNOOZE -> RangThroughKind.EXPECTING
        AllowReason.RULE -> RangThroughKind.ALLOW_RULE
        AllowReason.DIALLED -> RangThroughKind.DIALLED
        AllowReason.ANSWERED -> RangThroughKind.ANSWERED
        AllowReason.CONTACT -> if (r.rule != null) RangThroughKind.LABEL else null
        else -> null
    }

    /**
     * One screening run. [withoutExceptions] leaves out what lets a call through that would otherwise be blocked (allow
     * rules, label allow rules, "Expecting a call", numbers you called or talked to, repeat callers), to tell whether
     * one of them made the difference.
     */
    private class Evaluation(
        val f: IncomingCallFacts,
        val rules: List<BlockRule>,
        val s: ScreeningSettings,
        val clock: PolicyClock,
        val withoutExceptions: Boolean = false,
    ) {
        /** I7: the expected-call window that let the call ring, when one did. */
        var expectedHit: ExpectedWindow? = null

        /** The repeat caller's calls within the window (this one included) and the minutes they span. */
        var repeatCalls = 0
        var repeatMinutes = 0

        val steps = ArrayList<TraceStep>()

        fun step(check: String, result: String, mark: TraceMark = TraceMark.PASS) {
            steps += TraceStep(check, result, mark)
        }

        fun active(schedule: Schedule?) = schedule == null || schedule.isActive(clock.day, clock.minuteOfDay)

        fun live(r: BlockRule) = r.isLive(clock.millis) && active(r.schedule) && simMatches(r)

        fun simMatches(r: BlockRule): Boolean = r.simId == null || (f.simId != null && f.simId == r.simId)

        fun allow(by: AllowReason, rule: BlockRule? = null, ringtone: String? = null, loud: Boolean = false, verdict: Verdict? = null) =
            ScreeningResult(Decision.Allow, steps.toList(), verdict, by, rule, null, ringtone, loud)

        fun block(action: BlockAction, reason: BlockReason, rule: BlockRule? = null, hit: ListHit? = null, notify: NotifyLevel = NotifyLevel.DEFAULT): ScreeningResult {
            val verdict = when {
                reason == BlockReason.LIST && hit != null -> Verdict(VerdictKind.REPORTED, "Reported by ${hit.packName}" + (hit.category?.let { " · $it" } ?: ""))
                rule != null -> Verdict(VerdictKind.BLOCKED, "Blocked by rule '${rule.title}'" + if (rule.hitCount > 0) " · ${rule.hitCount + 1} calls" else "")
                reason == BlockReason.PERSONAL_REPUTATION -> Verdict(VerdictKind.BLOCKED, SALES_LINE_SILENCED)
                else -> Verdict(VerdictKind.BLOCKED, "Blocked: ${reasonLabel(reason)}")
            }
            step("Decision", (if (action == BlockAction.REJECT) "Reject" else "Silence"), TraceMark.MATCH)
            return ScreeningResult(Decision.Block(action, reason, rule), steps.toList(), verdict, null, rule, hit, notify = notify)
        }

        fun run(): ScreeningResult {
            // 1. Emergency window: nothing is ever blocked.
            if (f.isEmergency || f.inEmergencyWindow) {
                step("Emergency", if (f.isEmergency) "emergency number" else "within an hour of an emergency call", TraceMark.MATCH)
                return allow(AllowReason.EMERGENCY)
            }
            val number = f.number?.takeIf { it.isNotBlank() && !f.hidden }
            // "Expecting a call" by hand, or a window from a note, the To call list or a delivery QR code (I7).
            val window = ExpectedCalls.covering(s.expected, number, f.countryIso ?: f.region, clock.millis)
            val snooze = s.snoozeActive(clock.millis) || window != null
            val snoozeWhy = if (s.snoozeActive(clock.millis)) "on" else "from " + window?.source?.name?.lowercase()?.replace('_', ' ')
            if (number == null) {
                step("Number", "hidden")
                if (snooze && !withoutExceptions) {
                    expectedHit = window
                    step("Expecting a call", snoozeWhy, TraceMark.MATCH)
                    return allow(AllowReason.SNOOZE)
                }
                if (s.blockHidden && active(s.hiddenSchedule)) {
                    step("Block hidden numbers", "on", TraceMark.MATCH)
                    return block(s.defaultAction, BlockReason.HIDDEN)
                }
                if (s.offHours.enabled && active(s.offHours.schedule)) {
                    step("Off hours", "hidden caller", TraceMark.MATCH)
                    return block(s.offHours.action, BlockReason.OFF_HOURS)
                }
                step("Default", "ring")
                return allow(AllowReason.DEFAULT)
            }

            // The system block list is the user's explicit choice, even for contacts.
            if (f.inSystemBlockList) {
                step("Blocked numbers list", "listed", TraceMark.MATCH)
                return block(BlockAction.REJECT, BlockReason.SYSTEM_LIST)
            }

            // 2. Contacts and vault.
            if (f.isContact) {
                if (f.contactLookupFailed) {
                    step("Contact?", "couldn't check, treated as a contact", TraceMark.FAILED_OPEN)
                    return allow(AllowReason.CONTACT)
                }
                step("Contact?", "yes", TraceMark.MATCH)
                val labelRules = rules.filter { it.type == RuleType.LABEL && live(it) }
                if (labelRules.isNotEmpty() && f.labelLookupFailed) step("Labels", "couldn't check, label rules skipped", TraceMark.FAILED_OPEN)
                val titles = f.contactLabels.map { LabelRefs.key(it) }.toSet()
                val inLabel = if (f.labelLookupFailed) emptyList()
                else labelRules.filter { it.labelKey in titles && !(withoutExceptions && it.kind == RuleKind.ALLOW) }
                inLabel.firstOrNull { it.kind == RuleKind.BLOCK }?.let { r ->
                    step("Label rule", r.title, TraceMark.MATCH)
                    return block(r.action, BlockReason.RULE, r, notify = r.notify)
                }
                val allowLabel = inLabel.firstOrNull { it.kind == RuleKind.ALLOW }
                val oh = s.offHours
                if (oh.enabled && active(oh.schedule) && allowLabel == null) {
                    val ok = when (oh.allow) {
                        OffHoursAllow.CONTACTS -> true
                        OffHoursAllow.FAVOURITES -> f.contactStarred
                        // Fail open when labels couldn't be read.
                        OffHoursAllow.LABEL -> f.labelLookupFailed || (oh.labelTitle != null && LabelRefs.key(oh.labelTitle) in titles)
                    }
                    // An allow label rule limited to one SIM may still let this contact ring once the SIM is known.
                    if (!ok && !f.labelLookupFailed) simPending { it.type == RuleType.LABEL && it.labelKey in titles }?.let { return deferToSim(it) }
                    if (!ok) {
                        step("Off hours", "only ${offHoursWho(oh)} ring now", TraceMark.MATCH)
                        return softBlock(oh.action, BlockReason.OFF_HOURS)
                    }
                }
                allowLabel?.let { step("Label rule", it.title, TraceMark.MATCH) }
                val loud = s.ringLoudFavourites && f.contactStarred
                // Label ringtones come from the label's page (one store), added by the caller of the policy.
                return allow(AllowReason.CONTACT, allowLabel, null, loud)
            }
            step("Contact?", "no")

            // 3. Allow rules, snooze, numbers you called or talked to.
            val allowRule = if (withoutExceptions) null
            else rules.firstOrNull { it.kind == RuleKind.ALLOW && it.type != RuleType.LABEL && live(it) && factMatches(it, number) }
            if (allowRule != null) {
                step("Allow rule", allowRule.title + if (allowRule.expiresAt != null) " (temporary)" else "", TraceMark.MATCH)
                return allow(AllowReason.RULE, allowRule, allowRule.ringtone, verdict = Verdict(VerdictKind.ALLOWED, "Allowed by '${allowRule.title}'"))
            }
            // The screening service never knows the SIM: an allow rule limited to one SIM is decided when the call rings.
            simPending { it.type != RuleType.LABEL && factMatches(it, number) }?.let { return deferToSim(it) }
            if (snooze && !withoutExceptions) {
                expectedHit = window
                step("Expecting a call", snoozeWhy, TraceMark.MATCH)
                return allow(AllowReason.SNOOZE, verdict = Verdict(VerdictKind.ALLOWED, "Let through: expecting a call"))
            }
            if (s.allowDialled && !withoutExceptions) {
                val since = clock.millis - s.dialledDays * DAY
                if (f.history.any { it.outgoing && it.time >= since }) {
                    step("You called this number", "within ${s.dialledDays} days", TraceMark.MATCH)
                    return allow(AllowReason.DIALLED, verdict = Verdict(VerdictKind.ALLOWED, "You called this number recently"))
                }
            }
            if (s.allowAnswered && !withoutExceptions) {
                val since = clock.millis - s.answeredDays * DAY
                if (f.history.any { !it.outgoing && it.time >= since && it.durationSec >= s.answeredMinSeconds }) {
                    step("You talked to this number", "≥ ${s.answeredMinSeconds} s within ${s.answeredDays} days", TraceMark.MATCH)
                    return allow(AllowReason.ANSWERED, verdict = Verdict(VerdictKind.ALLOWED, "You talked to this number recently"))
                }
            }
            step("Allow rules", "none")

            // 4. Block rules (explicit: repeat callers never override them).
            val simSkipped = rules.any { it.kind == RuleKind.BLOCK && it.simId != null && f.simId == null && it.isLive(clock.millis) }
            if (simSkipped) step("SIM rules", "SIM unknown here, skipped", TraceMark.SKIPPED)
            rules.firstOrNull { it.kind == RuleKind.BLOCK && it.type != RuleType.LABEL && live(it) && factMatches(it, number) }?.let { r ->
                step("Block rule", r.title, TraceMark.MATCH)
                return block(r.action, BlockReason.RULE, r, notify = r.notify)
            }
            step("Block rules", "none")

            // 5. Spam lists.
            if (f.listLookupFailed) step("Spam lists", "couldn't be read", TraceMark.FAILED_OPEN)
            val hits = f.listHits.sortedByDescending { it.score }
            val blocking = hits.firstOrNull { it.blocks }
            var warn: Verdict? = null
            if (blocking != null) {
                step("Spam list", "${blocking.packName}: ${blocking.category ?: "listed"}, score ${blocking.score}", TraceMark.MATCH)
                return softBlock(blocking.action, BlockReason.LIST, blocking)
            } else if (hits.isNotEmpty()) {
                val h = hits.first()
                step("Spam list", "${h.packName}: ${h.category ?: "listed"}, warn only" + if (h.stale) " (list out of date)" else "", TraceMark.MATCH)
                warn = Verdict(VerdictKind.LIKELY_SPAM, "Likely spam · ${h.packName}" + (h.category?.let { " · $it" } ?: ""))
            } else if (!f.listLookupFailed) {
                step("Spam lists", "not listed")
            }

            // 5b. I2 personal reputation, below lists (a soft reason: a repeat caller still rings).
            f.reputation?.takeIf { it.looksLikeSales && s.learnFromCalls }?.let { rep ->
                if (s.silenceSalesLines) {
                    step("Your calls", "looks like a sales line: ${rep.describe()}", TraceMark.MATCH)
                    return softBlock(BlockAction.SILENCE, BlockReason.PERSONAL_REPUTATION, warn = warn)
                }
                step("Your calls", "looks like a sales line, tag only")
            }

            // 6. Default toggles.
            if (s.blockFailedVerification && active(s.verificationSchedule) && f.verification == Verification.FAILED) {
                step("Caller verification", "failed", TraceMark.MATCH)
                return softBlock(s.defaultAction, BlockReason.VERIFICATION_FAILED, warn = warn)
            }
            if (s.blockNeighbourSpoofing && active(s.neighbourSchedule) && PhoneNumbers.looksLikeNeighbourSpoof(number, f.ownNumbers, f.countryIso)) {
                step("Neighbour spoofing", "looks like your own number", TraceMark.MATCH)
                return softBlock(s.defaultAction, BlockReason.NEIGHBOUR_SPOOF, warn = warn)
            }
            if (s.blockInvalid && active(s.invalidSchedule) && (f.validity == NumberValidity.INVALID || f.validity == NumberValidity.IMPOSSIBLE)) {
                step("Invalid number", if (f.validity == NumberValidity.IMPOSSIBLE) "can't exist" else "not assigned", TraceMark.MATCH)
                return softBlock(s.invalidAction, BlockReason.INVALID_NUMBER, warn = warn)
            }
            if (s.offHours.enabled && active(s.offHours.schedule)) {
                step("Off hours", "only ${offHoursWho(s.offHours)} ring now", TraceMark.MATCH)
                return softBlock(s.offHours.action, BlockReason.OFF_HOURS, warn = warn)
            }
            if (s.blockNonContacts && active(s.nonContactsSchedule)) {
                step("Block non-contacts", "on", TraceMark.MATCH)
                return softBlock(s.defaultAction, BlockReason.NOT_A_CONTACT, warn = warn)
            }
            step("Default", "ring")
            return allow(AllowReason.DEFAULT, ringtone = if (warn != null) s.likelySpamRingtone else null, verdict = warn)
        }

        /**
         * When this call's SIM is unknown: a live allow rule limited to one SIM that [matches] with the SIM
         * ignored. Null when the SIM is known or no such rule matches.
         */
        fun simPending(matches: (BlockRule) -> Boolean): BlockRule? {
            if (f.simId != null) return null
            return rules.firstOrNull { it.kind == RuleKind.ALLOW && it.simId != null && it.isLive(clock.millis) && active(it.schedule) && matches(it) }
        }

        fun deferToSim(r: BlockRule): ScreeningResult {
            step("SIM allow rule", "${r.title}: SIM unknown here, checked again when the call rings", TraceMark.SKIPPED)
            return ScreeningResult(Decision.Allow, steps.toList(), null, AllowReason.DEFAULT, deferredToSim = true)
        }

        /** Blocks for a soft reason unless this is a genuine repeat caller, who is let through instead. */
        fun softBlock(action: BlockAction, reason: BlockReason, hit: ListHit? = null, warn: Verdict? = null): ScreeningResult {
            if (repeatCaller()) {
                step("Decision", "ring (repeat caller overrides ${reasonLabel(reason)})", TraceMark.MATCH)
                return allow(AllowReason.REPEAT, ringtone = s.repeatRingtone, loud = s.ringLoudRepeat || (s.ringLoudFavourites && f.contactStarred), verdict = warn)
            }
            return block(action, reason, hit = hit, notify = hit?.notify ?: NotifyLevel.DEFAULT)
        }

        private var repeatChecked: Boolean? = null

        fun repeatCaller(): Boolean {
            repeatChecked?.let { return it }
            if (!s.repeatCallers || withoutExceptions) return false.also { repeatChecked = it }
            val window = s.repeatWindowMinutes * 60_000L
            val min = s.repeatMinIntervalSeconds * 1000L
            val attempts = f.blockedAttempts + f.history.filter { !it.outgoing }.map { it.time }
            val gaps = attempts.map { clock.millis - it }.filter { it in 0..window }
            val counted = gaps.any { it >= min }
            if (counted) {
                val spans = gaps.filter { it >= min }
                repeatCalls = spans.size + 1
                repeatMinutes = ((spans.max() + 59_999) / 60_000).toInt().coerceAtLeast(1)
                step("Repeat caller", "called again within ${s.repeatWindowMinutes} min", TraceMark.MATCH)
            } else if (gaps.isNotEmpty()) {
                step("Repeat caller", "redialled too fast (under ${s.repeatMinIntervalSeconds} s), doesn't count")
            }
            repeatChecked = counted
            return counted
        }

        fun factMatches(r: BlockRule, number: String): Boolean = when (r.type) {
            RuleType.EXACT, RuleType.PREFIX, RuleType.WILDCARD -> ruleMatches(r, number, f.countryIso)
            RuleType.CALLER_NAME -> r.pattern.isNotBlank() && f.callerName?.contains(r.pattern.trim(), ignoreCase = true) == true
            RuleType.REGION -> f.region != null && r.pattern.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.contains(f.region.uppercase())
            RuleType.NOT_MY_REGION -> f.region != null && f.countryIso != null && !f.region.equals(f.countryIso, ignoreCase = true)
            RuleType.LINE_TYPE -> f.lineType != LineType.UNKNOWN && r.pattern.split(',').map { it.trim() }.contains(f.lineType.name)
            RuleType.LABEL -> false
        }
    }

    private const val DAY = 86_400_000L

    /** I2's verdict ("Why it rang, or not" and Recents show it in the app's language). */
    const val SALES_LINE_SILENCED = "Silenced: looks like a sales line (your calls)"

    fun reasonLabel(r: BlockReason): String = when (r) {
        BlockReason.HIDDEN -> "hidden number"
        BlockReason.NOT_A_CONTACT -> "not a contact"
        BlockReason.NEIGHBOUR_SPOOF -> "neighbour spoofing"
        BlockReason.VERIFICATION_FAILED -> "failed caller verification"
        BlockReason.SYSTEM_LIST -> "blocked numbers list"
        BlockReason.RULE -> "your rule"
        BlockReason.LIST -> "spam list"
        BlockReason.INVALID_NUMBER -> "invalid number"
        BlockReason.OFF_HOURS -> "off hours"
        BlockReason.SEND_TO_VOICEMAIL -> "sent to voicemail"
        BlockReason.PERSONAL_REPUTATION -> "looks like a sales line (your calls)"
    }

    private fun offHoursWho(o: OffHours) = when (o.allow) {
        OffHoursAllow.CONTACTS -> "contacts"
        OffHoursAllow.FAVOURITES -> "favourites"
        OffHoursAllow.LABEL -> "'${o.labelTitle?.trim() ?: "label"}'"
    }

    fun ruleMatches(rule: BlockRule, number: String, countryIso: String?): Boolean {
        val parts = PhoneNumbers.forwardedParts(number)
        return parts.any { part -> matchesOne(rule, part, countryIso) }
    }

    private fun matchesOne(rule: BlockRule, number: String, countryIso: String?): Boolean {
        // '_' and '%' are never wildcards: a pattern containing them can't match a phone number.
        if (rule.pattern.any { it == '_' || it == '%' }) return false
        val candidates = candidatesFor(number, countryIso)
        return when (rule.type) {
            RuleType.EXACT -> PhoneNumbers.same(rule.pattern, number, countryIso)
            RuleType.PREFIX -> {
                val p = RuleTools.canonicalPrefix(rule.pattern, countryIso)
                p.isNotEmpty() && candidates.any { it.startsWith(p) }
            }
            RuleType.WILDCARD -> {
                val wildcard = WildcardPattern.compile(RuleTools.canonicalWildcard(rule.pattern, countryIso)) ?: return false
                candidates.any { wildcard.matches(it) }
            }
            else -> false
        }
    }

    /** Forms of a number a pattern may be written against: E.164, raw digits, and national form. */
    fun candidatesFor(number: String, countryIso: String?): Set<String> {
        val out = linkedSetOf(PhoneNumbers.clean(number))
        PhoneNumbers.toE164(number, countryIso)?.let { e ->
            out += e
            val cc = countryIso?.let { CountryCodes.callingCode(it) }
            if (cc != null && e.startsWith("+$cc")) {
                val national = e.substring(cc.length + 1)
                out += national
                val trunk = CountryCodes.trunkPrefix(countryIso)
                if (trunk != null) out += trunk + national
            }
        }
        return out
    }
}
