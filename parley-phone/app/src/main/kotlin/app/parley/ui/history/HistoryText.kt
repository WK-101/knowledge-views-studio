package app.parley.ui.history

import android.content.res.Resources
import androidx.annotation.StringRes
import app.parley.R
import app.parley.common.history.AnswerWindow
import app.parley.common.history.BillingIncrement
import app.parley.common.history.DeleteRange
import app.parley.common.history.FilterPeriod
import app.parley.common.history.HistoryFilter
import app.parley.common.history.ImportSource
import app.parley.common.history.NumberCategory
import app.parley.common.history.PlanUsage
import app.parley.common.history.RowProblem
import app.parley.common.history.RowProblemKind
import app.parley.common.history.TypeGroup
import app.parley.common.CallType
import app.parley.common.history.CallTotals
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Localised texts for the call-history enums and reports produced by core:common. */
object HistoryText {
    @StringRes fun answerWindow(w: AnswerWindow): Int = when (w) {
        AnswerWindow.MORNING -> R.string.hist_answers_morning
        AnswerWindow.AFTERNOON -> R.string.hist_answers_afternoon
        AnswerWindow.EVENING -> R.string.hist_answers_evening
    }

    @StringRes fun period(p: FilterPeriod): Int = when (p) {
        FilterPeriod.ANY -> R.string.hist_period_any
        FilterPeriod.TODAY -> R.string.label_today
        FilterPeriod.LAST_7_DAYS -> R.string.hist_period_7_days
        FilterPeriod.LAST_30_DAYS -> R.string.hist_period_30_days
        FilterPeriod.THIS_MONTH -> R.string.label_this_month
        FilterPeriod.LAST_90_DAYS -> R.string.hist_period_90_days
        FilterPeriod.THIS_YEAR -> R.string.label_this_year
    }

    @StringRes fun typeGroup(g: TypeGroup): Int = when (g) {
        TypeGroup.INCOMING -> R.string.label_incoming
        TypeGroup.OUTGOING -> R.string.label_outgoing
        TypeGroup.MISSED -> R.string.label_missed
        TypeGroup.REJECTED -> R.string.label_rejected
        TypeGroup.BLOCKED -> R.string.label_blocked
        TypeGroup.VOICEMAIL -> R.string.label_voicemail
    }

    @StringRes fun callType(t: CallType): Int = when (t) {
        CallType.INCOMING -> R.string.label_incoming
        CallType.OUTGOING -> R.string.label_outgoing
        CallType.MISSED -> R.string.label_missed
        CallType.REJECTED -> R.string.label_rejected
        CallType.BLOCKED -> R.string.label_blocked
        CallType.VOICEMAIL -> R.string.label_voicemail
        CallType.ANSWERED_EXTERNALLY -> R.string.hist_type_answered_elsewhere
        CallType.UNKNOWN -> R.string.hist_type_unknown
    }

    @StringRes fun deleteRange(r: DeleteRange): Int = when (r) {
        DeleteRange.ALL -> R.string.label_all_calls
        DeleteRange.LAST_YEAR -> R.string.hist_range_year
        DeleteRange.LAST_MONTH -> R.string.hist_range_month
        DeleteRange.LAST_WEEK -> R.string.hist_range_week
        DeleteRange.LAST_DAY -> R.string.hist_range_day
        DeleteRange.SINCE_DATE -> R.string.hist_range_since_date
    }

    @StringRes fun increment(b: BillingIncrement): Int = when (b) {
        BillingIncrement.PER_SECOND -> R.string.hist_increment_second
        BillingIncrement.HALF_MINUTE -> R.string.hist_increment_half_minute
        BillingIncrement.PER_MINUTE -> R.string.hist_increment_minute
    }

    @StringRes fun category(c: NumberCategory): Int = when (c) {
        NumberCategory.MOBILE -> R.string.hist_category_mobile
        NumberCategory.LANDLINE -> R.string.hist_category_landline
        NumberCategory.MOBILE_OR_LANDLINE -> R.string.hist_category_mobile_or_landline
        NumberCategory.INTERNATIONAL -> R.string.hist_category_international
        NumberCategory.TOLL_FREE -> R.string.hist_category_toll_free
        NumberCategory.OTHER -> R.string.hist_category_other
    }

    @StringRes fun source(s: ImportSource): Int = when (s) {
        ImportSource.PARLEY -> R.string.hist_source_parley
        ImportSource.LOGGER -> R.string.hist_source_logger
        ImportSource.GENERIC -> R.string.hist_source_generic
    }

    fun problem(res: Resources, p: RowProblem): String = when (p.kind) {
        RowProblemKind.EMPTY_FILE -> res.getString(R.string.hist_problem_empty_file)
        RowProblemKind.NEED_COLUMNS -> res.getString(R.string.hist_problem_need_columns)
        RowProblemKind.UNREADABLE_ROW -> res.getString(R.string.hist_problem_unreadable_row)
        RowProblemKind.UNKNOWN_TYPE -> res.getString(R.string.hist_problem_unknown_type, p.value)
        RowProblemKind.UNREADABLE_DATE -> res.getString(R.string.hist_problem_unreadable_date, p.value)
        RowProblemKind.UNREADABLE_DURATION -> res.getString(R.string.hist_problem_unreadable_duration, p.value)
        RowProblemKind.NOT_A_NUMBER -> res.getString(R.string.hist_problem_not_a_number, p.value)
    }

    /** "212 of 300 min used · 9 days left". */
    fun planSummary(res: Resources, u: PlanUsage): String {
        val used = res.getString(R.string.hist_plan_used, u.usedMinutes, u.config.allowanceMinutes)
        val left = if (u.daysLeft == 1) res.getString(R.string.hist_plan_last_day)
        else res.getQuantityString(R.plurals.hist_plan_days_left, u.daysLeft, u.daysLeft)
        return res.getString(R.string.format_dot_pair, used, left)
    }

    /** A filter's own range of days: "3 Mar 2026 – 31 Mar 2026", "From 3 Mar 2026", "Until 31 Mar 2026". */
    fun days(res: Resources, fromDay: Long?, toDay: Long?): String {
        val fmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        fun day(d: Long) = LocalDate.ofEpochDay(d).format(fmt)
        return when {
            fromDay != null && toDay != null && fromDay == toDay -> day(fromDay)
            fromDay != null && toDay != null -> res.getString(R.string.hist_filter_days, day(fromDay), day(toDay))
            fromDay != null -> res.getString(R.string.hist_filter_from, day(fromDay))
            toDay != null -> res.getString(R.string.label_until, day(toDay))
            else -> res.getString(R.string.hist_period_any)
        }
    }

    /** "23 calls · 3 h 12 min · 4 missed" for the calls a filter shows; the missed part only when there are some. */
    fun totals(res: Resources, t: CallTotals): String {
        val calls = res.getQuantityString(R.plurals.hist_n_calls, t.total, t.total)
        val talk = res.getString(R.string.format_dot_pair, calls, HistoryFormat.talk(t.talkSec))
        if (t.missed == 0) return talk
        return res.getString(R.string.format_dot_pair, talk, res.getQuantityString(R.plurals.hist_totals_missed, t.missed, t.missed))
    }

    /** Short description for a chip without a name, e.g. "Missed · SIM 2 · Last 7 days". */
    fun describe(res: Resources, f: HistoryFilter, simLabel: (String) -> String = { it }): String {
        fun fmt(s: Long) = if (s % 60 == 0L && s > 0) res.getString(R.string.label_minutes_short, s / 60) else res.getString(R.string.label_seconds_short, s)
        val min = f.minDurationSec
        val max = f.maxDurationSec
        return buildList {
            if (f.types.isNotEmpty()) add(
                f.types.sortedBy { it.ordinal }.joinToString(res.getString(R.string.dc_list_separator)) { res.getString(typeGroup(it)) },
            )
            f.simId?.let { add(simLabel(it)) }
            if (f.hasRange) add(days(res, f.fromDay, f.toDay)) else if (f.period != FilterPeriod.ANY) add(res.getString(period(f.period)))
            when {
                min != null && max != null -> add(res.getString(R.string.hist_duration_between, fmt(min), fmt(max)))
                min != null -> add(res.getString(R.string.hist_duration_at_least, fmt(min)))
                max != null -> add(res.getString(R.string.hist_duration_at_most, fmt(max)))
            }
        }.joinToString(" · ").ifEmpty { res.getString(R.string.label_all_calls) }
    }
}
