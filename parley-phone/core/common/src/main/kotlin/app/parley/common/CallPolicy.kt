package app.parley.common

import kotlinx.serialization.Serializable
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

    /** Contact belongs to the label whose id is the pattern (title in [BlockRule.label]). */
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
    val labelId: Long? = null,
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
    /** B14: numbers libphonenumber says can't exist or aren't assigned for their region. */
    val blockInvalid: Boolean = false,
    val invalidAction: BlockAction = BlockAction.SILENCE,
    // B17: per-toggle schedules (null = always).
    val hiddenSchedule: Schedule? = null,
    val nonContactsSchedule: Schedule? = null,
    val neighbourSchedule: Schedule? = null,
    val verificationSchedule: Schedule? = null,
    val invalidSchedule: Schedule? = null,
    val offHours: OffHours = OffHours(),
    // B19: repeat callers and "people you talked to".
    val repeatCallers: Boolean = true,
    val repeatWindowMinutes: Int = 3,
    /** Redials faster than this don't count as a repeat (bots redial instantly). */
    val repeatMinIntervalSeconds: Int = 20,
    val allowDialled: Boolean = false,
    val dialledDays: Int = 30,
    val allowAnswered: Boolean = false,
    val answeredMinSeconds: Int = 30,
    val answeredDays: Int = 30,
    /** B21: "Expecting a call": unknown callers ring until this time. */
    val snoozeUntil: Long = 0,
    /** B23: numbers that start the emergency window when you call them (a GP, a school). */
    val emergencyExtras: List<String> = emptyList(),
    // B6: default notification level per verdict.
    val notifyBlocked: NotifyLevel = NotifyLevel.QUIET,
    val notifyReported: NotifyLevel = NotifyLevel.QUIET,
    val notifyLikelySpam: NotifyLevel = NotifyLevel.NORMAL,
    // B27
    val busyReply: Boolean = false,
    val busyReplyText: String = "In a meeting. I'll call you back.",
    // B24
    val ringLoudFavourites: Boolean = false,
    val ringLoudRepeat: Boolean = false,
    val repeatRingtone: String? = null,
    val likelySpamRingtone: String? = null,
    // B12
    val reputationSuggestions: Boolean = true,
    // B8
    val webSearchUrl: String = "https://duckduckgo.com/?q=",
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
    val contactLabels: Set<Long> = emptySet(),
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
)

enum class BlockReason {
    SYSTEM_LIST, RULE, HIDDEN, NOT_A_CONTACT, NEIGHBOUR_SPOOF, VERIFICATION_FAILED,
    LIST, INVALID_NUMBER, OFF_HOURS,
    ;

    /** Soft reasons can be overridden by a repeat caller; explicit choices (rules, lists you block) never. */
    val soft: Boolean get() = this in setOf(NOT_A_CONTACT, NEIGHBOUR_SPOOF, VERIFICATION_FAILED, LIST, INVALID_NUMBER, OFF_HOURS)
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
) {
    val failedOpen: Boolean get() = trace.any { it.mark == TraceMark.FAILED_OPEN }
    val blocked: Boolean get() = decision is Decision.Block
}

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

    fun decide(facts: IncomingCallFacts, rules: List<BlockRule>, settings: ScreeningSettings, clock: PolicyClock): ScreeningResult =
        Evaluation(facts, rules, settings, clock).run()

    private class Evaluation(val f: IncomingCallFacts, val rules: List<BlockRule>, val s: ScreeningSettings, val clock: PolicyClock) {
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
            val snooze = s.snoozeActive(clock.millis)
            if (number == null) {
                step("Number", "hidden")
                if (snooze) {
                    step("Expecting a call", "on", TraceMark.MATCH)
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
                val inLabel = if (f.labelLookupFailed) emptyList() else labelRules.filter { it.pattern.toLongOrNull() in f.contactLabels }
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
                        OffHoursAllow.LABEL -> f.labelLookupFailed || (oh.labelId != null && oh.labelId in f.contactLabels)
                    }
                    if (!ok) {
                        step("Off hours", "only ${offHoursWho(oh)} ring now", TraceMark.MATCH)
                        return softBlock(oh.action, BlockReason.OFF_HOURS)
                    }
                }
                allowLabel?.let { step("Label rule", it.title, TraceMark.MATCH) }
                val loud = s.ringLoudFavourites && f.contactStarred
                return allow(AllowReason.CONTACT, allowLabel, allowLabel?.ringtone, loud)
            }
            step("Contact?", "no")

            // 3. Allow rules, snooze, numbers you called or talked to.
            val allowRule = rules.firstOrNull { it.kind == RuleKind.ALLOW && it.type != RuleType.LABEL && live(it) && factMatches(it, number) }
            if (allowRule != null) {
                step("Allow rule", allowRule.title + if (allowRule.expiresAt != null) " (temporary)" else "", TraceMark.MATCH)
                return allow(AllowReason.RULE, allowRule, allowRule.ringtone, verdict = Verdict(VerdictKind.ALLOWED, "Allowed by '${allowRule.title}'"))
            }
            if (snooze) {
                step("Expecting a call", "on", TraceMark.MATCH)
                return allow(AllowReason.SNOOZE, verdict = Verdict(VerdictKind.ALLOWED, "Let through: expecting a call"))
            }
            if (s.allowDialled) {
                val since = clock.millis - s.dialledDays * DAY
                if (f.history.any { it.outgoing && it.time >= since }) {
                    step("You called this number", "within ${s.dialledDays} days", TraceMark.MATCH)
                    return allow(AllowReason.DIALLED, verdict = Verdict(VerdictKind.ALLOWED, "You called this number recently"))
                }
            }
            if (s.allowAnswered) {
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
            if (!s.repeatCallers) return false.also { repeatChecked = it }
            val window = s.repeatWindowMinutes * 60_000L
            val min = s.repeatMinIntervalSeconds * 1000L
            val attempts = f.blockedAttempts + f.history.filter { !it.outgoing }.map { it.time }
            val gaps = attempts.map { clock.millis - it }.filter { it in 0..window }
            val counted = gaps.any { it >= min }
            if (counted) {
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
    }

    private fun offHoursWho(o: OffHours) = when (o.allow) {
        OffHoursAllow.CONTACTS -> "contacts"
        OffHoursAllow.FAVOURITES -> "favourites"
        OffHoursAllow.LABEL -> "'${o.labelTitle ?: "label"}'"
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
                val regex = wildcardRegex(RuleTools.canonicalWildcard(rule.pattern, countryIso)) ?: return false
                candidates.any { regex.matches(it) }
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
