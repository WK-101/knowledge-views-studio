package app.parley.blocking

import android.content.Context
import app.parley.R
import app.parley.common.BlockReason
import app.parley.common.BlockRule
import app.parley.common.CallPolicy
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.TraceMark
import app.parley.common.TraceStep
import app.parley.common.blocking.PersonalReputation
import app.parley.common.blocking.ReplayReport
import app.parley.common.lineTypeLabel
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Screening texts in the app's language. [CallPolicy] (pure JVM, core:common) writes its decision trace and
 * verdicts in English, and they are stored that way in the screened-call log; this maps the known English
 * forms to string resources when they are shown. Anything unknown (older logs, rule and list names) is shown
 * as it is.
 */
object BlockingText {
    /** [CallPolicy.reasonLabel] in the app's language. */
    fun reason(context: Context, r: BlockReason): String = context.getString(
        when (r) {
            BlockReason.HIDDEN -> R.string.blk_reason_hidden
            BlockReason.NOT_A_CONTACT -> R.string.blk_reason_not_contact
            BlockReason.NEIGHBOUR_SPOOF -> R.string.blk_reason_neighbour
            BlockReason.VERIFICATION_FAILED -> R.string.blk_reason_verification
            BlockReason.SYSTEM_LIST -> R.string.blk_reason_system_list
            BlockReason.RULE -> R.string.blk_reason_rule
            BlockReason.LIST -> R.string.blk_reason_list
            BlockReason.INVALID_NUMBER -> R.string.blk_reason_invalid
            BlockReason.OFF_HOURS -> R.string.blk_reason_off_hours
        },
    )

    /** An English reason label, as written into the trace and stored verdicts, in the app's language. */
    private fun reasonFromLabel(context: Context, label: String): String =
        BlockReason.entries.firstOrNull { CallPolicy.reasonLabel(it) == label }?.let { reason(context, it) } ?: label

    private val reportedBy = Regex("^Reported by (.+?)(?: · (.+))?$")
    private val blockedByRule = Regex("^Blocked by rule '(.*)'(?: · (\\d+) calls)?$")
    private val blockedReason = Regex("^Blocked: (.+)$")
    private val allowedBy = Regex("^Allowed by '(.*)'$")
    private val likelySpam = Regex("^Likely spam · (.+?)(?: · (.+))?$")

    /** A verdict ("Blocked by rule 'X' · 3 calls", "Likely spam · List") in the app's language. */
    fun verdict(context: Context, text: String?): String? {
        if (text == null) return null
        fun join(a: String, b: String?) = if (b == null) a else context.getString(R.string.blk_joined, a, b)
        reportedBy.matchEntire(text)?.let { m -> return join(context.getString(R.string.blk_verdict_reported_by, m.groupValues[1]), m.groups[2]?.value) }
        blockedByRule.matchEntire(text)?.let { m ->
            val base = context.getString(R.string.blk_verdict_blocked_by_rule, m.groupValues[1])
            val n = m.groups[2]?.value?.toIntOrNull() ?: return base
            return join(base, context.resources.getQuantityString(R.plurals.blk_calls, n, n))
        }
        blockedReason.matchEntire(text)?.let { m -> return context.getString(R.string.blk_verdict_blocked_reason, reasonFromLabel(context, m.groupValues[1])) }
        allowedBy.matchEntire(text)?.let { m -> return context.getString(R.string.blk_verdict_allowed_by, m.groupValues[1]) }
        likelySpam.matchEntire(text)?.let { m -> return join(context.getString(R.string.blk_verdict_likely_spam, m.groupValues[1]), m.groups[2]?.value) }
        return when (text) {
            "Let through: expecting a call" -> context.getString(R.string.blk_verdict_expecting)
            "You called this number recently" -> context.getString(R.string.blk_verdict_called_recently)
            "You talked to this number recently" -> context.getString(R.string.blk_verdict_talked_recently)
            else -> text
        }
    }

    private val checks = mapOf(
        "Emergency" to R.string.blk_check_emergency,
        "Number" to R.string.blk_check_number,
        "Expecting a call" to R.string.blk_check_expecting,
        "Block hidden numbers" to R.string.blk_check_block_hidden,
        "Off hours" to R.string.blk_check_off_hours,
        "Default" to R.string.blk_check_default,
        "Blocked numbers list" to R.string.blk_check_system_list,
        "Contact?" to R.string.blk_check_contact,
        "Labels" to R.string.blk_check_labels,
        "Label rule" to R.string.blk_check_label_rule,
        "Allow rule" to R.string.blk_check_allow_rule,
        "You called this number" to R.string.blk_check_you_called,
        "You talked to this number" to R.string.blk_check_you_talked,
        "Allow rules" to R.string.blk_check_allow_rules,
        "SIM rules" to R.string.blk_check_sim_rules,
        "Block rule" to R.string.blk_check_block_rule,
        "Block rules" to R.string.blk_check_block_rules,
        "Spam lists" to R.string.blk_check_spam_lists,
        "Spam list" to R.string.blk_check_spam_list,
        "Caller verification" to R.string.blk_check_verification,
        "Neighbour spoofing" to R.string.blk_check_neighbour,
        "Invalid number" to R.string.blk_check_invalid,
        "Block non-contacts" to R.string.blk_check_non_contacts,
        "Decision" to R.string.blk_check_decision,
        "SIM allow rule" to R.string.blk_check_sim_allow_rule,
        "Repeat caller" to R.string.blk_check_repeat,
    )

    private val results = mapOf(
        "emergency number" to R.string.blk_res_emergency_number,
        "within an hour of an emergency call" to R.string.blk_res_emergency_window,
        "hidden" to R.string.blk_res_hidden,
        "on" to R.string.blk_res_on,
        "hidden caller" to R.string.blk_res_hidden_caller,
        "ring" to R.string.blk_res_ring,
        "listed" to R.string.blk_res_listed,
        "couldn't check, treated as a contact" to R.string.blk_res_contact_failed,
        "yes" to R.string.blk_res_yes,
        "no" to R.string.blk_res_no,
        "couldn't check, label rules skipped" to R.string.blk_res_labels_failed,
        "none" to R.string.blk_res_none,
        "SIM unknown here, skipped" to R.string.blk_res_sim_skipped,
        "couldn't be read" to R.string.blk_res_lists_failed,
        "not listed" to R.string.blk_res_not_listed,
        "failed" to R.string.blk_res_failed,
        "looks like your own number" to R.string.blk_res_own_number,
        "can't exist" to R.string.blk_res_cant_exist,
        "not assigned" to R.string.blk_res_not_assigned,
        "Reject" to R.string.blk_action_reject,
        "Silence" to R.string.blk_action_silence,
    )

    private val onlyRing = Regex("^only (.+) ring now$")
    private val withinDays = Regex("^within (\\d+) days$")
    private val talkedWithin = Regex("^≥ (\\d+) s within (\\d+) days$")
    private val listHit = Regex("^(.+): (.+), score (\\d+)$")
    private val listWarn = Regex("^(.+): (.+), warn only( \\(list out of date\\))?$")
    private val repeatOverrides = Regex("^ring \\(repeat caller overrides (.+)\\)$")
    private val simPending = Regex("^(.*): SIM unknown here, checked again when the call rings$")
    private val calledAgain = Regex("^called again within (\\d+) min$")
    private val tooFast = Regex("^redialled too fast \\(under (\\d+) s\\), doesn't count$")

    /** Steps whose result is the name of your rule, shown as it is. */
    private val ruleNameChecks = setOf("Label rule", "Allow rule", "Block rule")

    fun check(context: Context, check: String): String = checks[check]?.let { context.getString(it) } ?: check

    fun result(context: Context, check: String, result: String): String {
        if (check in ruleNameChecks) {
            return if (check == "Allow rule" && result.endsWith(" (temporary)")) {
                context.getString(R.string.blk_res_temporary, result.removeSuffix(" (temporary)"))
            } else {
                result
            }
        }
        results[result]?.let { return context.getString(it) }
        onlyRing.matchEntire(result)?.let { m ->
            val who = when (val w = m.groupValues[1]) {
                "contacts" -> context.getString(R.string.blk_who_contacts)
                "favourites" -> context.getString(R.string.blk_who_favourites)
                else -> w
            }
            return context.getString(R.string.blk_res_only_ring, who)
        }
        withinDays.matchEntire(result)?.let { m ->
            val d = m.groupValues[1].toInt()
            return context.resources.getQuantityString(R.plurals.blk_res_within_days, d, d)
        }
        talkedWithin.matchEntire(result)?.let { m ->
            val d = m.groupValues[2].toInt()
            return context.resources.getQuantityString(R.plurals.blk_res_talked_within, d, m.groupValues[1].toInt(), d)
        }
        listWarn.matchEntire(result)?.let { m ->
            val cat = listedOr(context, m.groupValues[2])
            val base = context.getString(R.string.blk_res_list_warn, m.groupValues[1], cat)
            return if (m.groupValues[3].isNotEmpty()) context.getString(R.string.blk_res_list_stale, base) else base
        }
        listHit.matchEntire(result)?.let { m ->
            return context.getString(R.string.blk_res_list_hit, m.groupValues[1], listedOr(context, m.groupValues[2]), m.groupValues[3].toInt())
        }
        repeatOverrides.matchEntire(result)?.let { m -> return context.getString(R.string.blk_res_repeat_overrides, reasonFromLabel(context, m.groupValues[1])) }
        simPending.matchEntire(result)?.let { m -> return context.getString(R.string.blk_res_sim_pending, m.groupValues[1]) }
        calledAgain.matchEntire(result)?.let { m ->
            val n = m.groupValues[1].toInt()
            return context.resources.getQuantityString(R.plurals.blk_res_called_again, n, n)
        }
        tooFast.matchEntire(result)?.let { m -> return context.getString(R.string.blk_res_too_fast, m.groupValues[1].toInt()) }
        return result
    }

    private fun listedOr(context: Context, category: String) = if (category == "listed") context.getString(R.string.blk_res_listed) else category

    /** "Contact?: no → Allow rules: none → …" (with "!" on failed-open steps), like [app.parley.common.TraceCodec.oneLine]. */
    fun oneLine(context: Context, steps: List<TraceStep>): String = steps.joinToString(" → ") {
        (if (it.mark == TraceMark.FAILED_OPEN) "! " else "") + context.getString(R.string.blk_trace_step, check(context, it.check), result(context, it.check, it.result))
    }

    /** [Schedule.describe]: "Every day 22:00–07:00", "Mon–Fri, all day"; day names from the locale. */
    fun schedule(context: Context, s: Schedule): String {
        val d = scheduleDays(context, s)
        return if (s.startMinute == s.endMinute) context.getString(R.string.blk_sched_all_day, d)
        else context.getString(R.string.blk_sched_range, d, Schedule.hm(s.startMinute), Schedule.hm(s.endMinute))
    }

    fun scheduleDays(context: Context, s: Schedule): String = when (s.days and Schedule.ALL_DAYS) {
        Schedule.ALL_DAYS -> context.getString(R.string.blk_sched_every_day)
        Schedule.WEEKDAYS -> context.getString(R.string.blk_sched_weekdays)
        Schedule.WEEKEND -> context.getString(R.string.blk_sched_weekends)
        0 -> context.getString(R.string.blk_sched_never)
        else -> DayOfWeek.entries.filter { s.hasDay(it) }.joinToString(", ") { dayShort(context, it) }
    }

    /** "Mon", "Di.", "lun." … in the app's language. */
    fun dayShort(context: Context, d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, locale(context))

    private fun locale(context: Context): Locale = context.resources.configuration.locales.get(0) ?: Locale.getDefault()

    /** [lineTypeLabel] in the app's language. */
    fun lineType(context: Context, name: String): String = when (name.trim()) {
        "VOIP" -> context.getString(R.string.blk_line_voip)
        "PREMIUM_RATE" -> context.getString(R.string.blk_line_premium)
        "SHARED_COST" -> context.getString(R.string.blk_line_shared_cost)
        "TOLL_FREE" -> context.getString(R.string.blk_line_toll_free)
        "PERSONAL_NUMBER" -> context.getString(R.string.blk_line_personal)
        "UAN" -> context.getString(R.string.blk_line_uan)
        "MOBILE" -> context.getString(R.string.blk_line_mobile)
        "FIXED_LINE" -> context.getString(R.string.blk_line_fixed)
        "FIXED_LINE_OR_MOBILE" -> context.getString(R.string.blk_line_fixed_or_mobile)
        "PAGER" -> context.getString(R.string.blk_line_pager)
        "VOICEMAIL" -> context.getString(R.string.blk_line_voicemail)
        else -> lineTypeLabel(name.trim())
    }

    /** [BlockRule.title]: the note, or a description of the pattern in the app's language. */
    fun ruleTitle(context: Context, r: BlockRule): String = r.note?.takeIf { it.isNotBlank() } ?: when (r.type) {
        RuleType.LABEL -> context.getString(R.string.blk_title_label, r.label ?: r.pattern)
        RuleType.NOT_MY_REGION -> context.getString(R.string.blk_title_not_my_country)
        RuleType.CALLER_NAME -> context.getString(R.string.blk_title_name_contains, r.pattern)
        RuleType.REGION -> context.getString(R.string.blk_title_from, r.pattern)
        RuleType.LINE_TYPE -> r.pattern.split(',').joinToString(", ") { lineType(context, it) }
        else -> r.pattern
    }

    /** [RuleTools.describe] in the app's language. */
    fun ruleDescribe(context: Context, r: BlockRule): String = when (r.type) {
        RuleType.EXACT -> context.getString(R.string.blk_desc_exact)
        RuleType.PREFIX -> context.getString(R.string.blk_desc_prefix, r.pattern)
        RuleType.WILDCARD -> context.getString(R.string.blk_desc_wildcard, r.pattern)
        RuleType.CALLER_NAME -> context.getString(R.string.blk_desc_caller_name, r.pattern)
        RuleType.REGION -> context.getString(R.string.blk_desc_region, r.pattern.replace(",", ", "))
        RuleType.NOT_MY_REGION -> context.getString(R.string.blk_desc_not_my_region)
        RuleType.LINE_TYPE -> r.pattern.split(',').joinToString(", ") { lineType(context, it) }
        RuleType.LABEL -> context.getString(R.string.blk_desc_label, r.label ?: r.pattern)
    }

    /** [PersonalReputation.Suggestion.reason]: "You declined 2 calls, 1 call ended within 3 s". */
    fun suggestionReason(context: Context, s: PersonalReputation.Suggestion): String = listOfNotNull(
        s.rejected.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.blk_sugg_declined, it, it) },
        s.shortAnswered.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.blk_sugg_short, it, it) },
    ).joinToString(", ")

    /** [ReplayReport.summary]: "Would have blocked 14 of 22 calls from unknown numbers". */
    fun replaySummary(context: Context, r: ReplayReport): String =
        if (r.unknown.isEmpty()) context.getString(R.string.blk_replay_none)
        else context.resources.getQuantityString(R.plurals.blk_replay_summary, r.unknown.size, r.blocked, r.unknown.size)

    /** Name of a spam list: built-in ones in the app's language (keyed by id), others as they were published. */
    fun packName(context: Context, id: String, name: String): String = when (id) {
        "builtin.fr.arcep-telemarketing" -> context.getString(R.string.blk_pack_fr_arcep_name)
        else -> name
    }

    fun packDescription(context: Context, id: String, description: String): String = when (id) {
        "builtin.fr.arcep-telemarketing" -> context.getString(R.string.blk_pack_fr_arcep_desc)
        else -> description
    }

    private val checkMessages = mapOf(
        "Enter something to match" to R.string.blk_chk_empty,
        "Pick a label" to R.string.blk_chk_pick_label,
        "_ and % aren't wildcards here. Use * for any digits and ? for one digit (Pattern type)." to R.string.blk_chk_sql_wildcards,
        "Use the Pattern type for * and ?" to R.string.blk_chk_use_pattern,
        "Enter some digits" to R.string.blk_chk_digits,
        "Not a valid pattern" to R.string.blk_chk_bad_pattern,
        "A very short prefix matches a lot of numbers" to R.string.blk_chk_short_prefix,
        "This matches almost every number" to R.string.blk_chk_matches_all,
    )
    private val unknownCountry = Regex("^Unknown country code: (.*) \\(use two letters, e\\.g\\. GB\\)$")
    private val unknownLineType = Regex("^Unknown line type: (.*)$")
    private val badChar = Regex("^'(.)' can't be part of a phone number$")
    private val otherCountry = Regex("^This is a \\+(\\d+) number \\((.*)\\), not your country \\(\\+(\\d+)\\)$")

    /** An error or warning from [app.parley.common.RuleTools.check] in the app's language. */
    fun ruleCheck(context: Context, message: String): String {
        checkMessages[message]?.let { return context.getString(it) }
        unknownCountry.matchEntire(message)?.let { return context.getString(R.string.blk_chk_unknown_country, it.groupValues[1]) }
        unknownLineType.matchEntire(message)?.let { return context.getString(R.string.blk_chk_unknown_line_type, it.groupValues[1]) }
        badChar.matchEntire(message)?.let { return context.getString(R.string.blk_chk_bad_char, it.groupValues[1]) }
        otherCountry.matchEntire(message)?.let { m ->
            return context.getString(R.string.blk_chk_other_country, "+" + m.groupValues[1], m.groupValues[2], "+" + m.groupValues[3])
        }
        return message
    }

    private val differentKey =Regex("^This update is signed by a different key \\((.*)\\) than the installed list \\((.*)\\)$")

    /** A [app.parley.data.SpamListStore.InstallResult.Failed] reason in the app's language; unknown reasons as they are. */
    fun installFailure(context: Context, reason: String): String {
        differentKey.matchEntire(reason)?.let { m ->
            val new = m.groupValues[1].let { if (it == "unsigned") context.getString(R.string.blk_unsigned) else it }
            return context.getString(R.string.blk_fail_different_key, new, m.groupValues[2])
        }
        return when (reason) {
            "Couldn't read the file" -> context.getString(R.string.blk_fail_read_file)
            "Couldn't install the list" -> context.getString(R.string.blk_fail_install)
            else -> reason
        }
    }

    /** A stored action ("REJECT", "SILENCE", "ALLOW") for the log, lower case. */
    fun actionLower(context: Context, action: String): String = when (action) {
        "REJECT" -> context.getString(R.string.blk_action_reject_lower)
        "SILENCE" -> context.getString(R.string.blk_action_silence_lower)
        else -> action.lowercase()
    }
}
