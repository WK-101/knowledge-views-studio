package app.parley.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import app.parley.common.CallType
import app.parley.ui.ParleyShapes
import app.parley.ui.history.HistoryText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.R
import app.parley.RecentFilter
import app.parley.common.ux.CallClass
import app.parley.common.ux.CallGlance
import app.parley.ui.CallClassBadge
import app.parley.ui.CallDurationBar
import app.parley.ui.CallSequenceDots
import app.parley.ui.CallTypeColors
import app.parley.ui.ParleyDialog
import app.parley.ui.history.SavedFilterMonogram
import kotlinx.coroutines.flow.MutableStateFlow

/** What a Recents row shows, as far as its marks go: [RecentRow] fills it in and draws [RecentsMark.onRow]. */
internal data class RecentRowFacts(
    val calls: Int,
    val cls: CallClass,
    /** The latest call was missed or turned down. */
    val missed: Boolean,
    /** The row has sequence dots to show (several calls, Rich style). */
    val sequence: Boolean,
    val unreturned: Boolean,
    val hidden: Boolean,
    val video: Boolean,
    val private: Boolean,
    val screening: Boolean,
    /** The trailing button calls (rather than opening the details). */
    val callButton: Boolean,
)

/**
 * Marks on Recents other than the call icons and the filter chips: the Filter and saved-filter chips, and what a row
 * can show next to its name and time, in the style ([rich] or [simple]) that shows it. Each one has a line in the
 * legend of that style, and [onRow] is the one place that decides which a row draws.
 */
internal enum class RecentsMark(val section: RecentsLegend.Section, val rich: Boolean, val simple: Boolean) {
    FILTER(RecentsLegend.Section.FILTERS, rich = true, simple = true),
    SAVED_FILTER(RecentsLegend.Section.FILTERS, rich = true, simple = true),
    ACCENT(RecentsLegend.Section.ROWS, rich = true, simple = false),
    NOT_RETURNED(RecentsLegend.Section.ROWS, rich = true, simple = false),
    COUNT(RecentsLegend.Section.ROWS, rich = true, simple = false),
    COUNT_TEXT(RecentsLegend.Section.ROWS, rich = false, simple = true),
    MISSED_NAME(RecentsLegend.Section.ROWS, rich = false, simple = true),
    SEQUENCE(RecentsLegend.Section.ROWS, rich = true, simple = false),
    DURATION(RecentsLegend.Section.ROWS, rich = true, simple = false),
    CALL_BACK(RecentsLegend.Section.ROWS, rich = true, simple = false),
    VIDEO(RecentsLegend.Section.ROWS, rich = true, simple = true),
    PRIVATE(RecentsLegend.Section.ROWS, rich = true, simple = true),
    SCREENING(RecentsLegend.Section.ROWS, rich = true, simple = true),
    ;

    fun shownIn(richStyle: Boolean): Boolean = if (richStyle) rich else simple

    companion object {
        /** The marks [RecentRow] draws for a row in the Rich ([richStyle]) or Simple style: these, and no others. */
        fun onRow(f: RecentRowFacts, richStyle: Boolean): Set<RecentsMark> = buildSet {
            val attention = f.unreturned && !f.hidden
            if (richStyle) {
                add(ACCENT)
                if (attention) add(NOT_RETURNED)
                if (f.calls > 1) add(COUNT)
                if (f.sequence) add(SEQUENCE)
                if (f.cls.answered) add(DURATION)
                if (attention && f.callButton) add(CALL_BACK)
            } else {
                if (f.calls > 1) add(COUNT_TEXT)
                if (f.missed) add(MISSED_NAME)
            }
            if (f.video) add(VIDEO)
            if (f.private) add(PRIVATE)
            if (f.screening) add(SCREENING)
        }
    }
}

/**
 * Recents ⋮ › "What do the colours mean?": everything Recents draws in the style in use, in three groups. It's built
 * from the enums themselves ([CallClass] or [CallType], [RecentFilter], [RecentsMark]) with exhaustive `when`s, and
 * the rows draw their marks from [RecentsMark.onRow], so a new badge, chip or mark can't reach Recents without a line
 * here (RecentsLegendTest checks both styles).
 */
internal object RecentsLegend {
    enum class Section(@StringRes val title: Int) {
        CALLS(R.string.recents_legend_section_calls),
        FILTERS(R.string.recents_legend_section_filters),
        ROWS(R.string.recents_legend_section_rows),
    }

    /** One line of the legend: what it is, and what it means. */
    sealed interface Entry {
        @get:StringRes val label: Int

        @get:StringRes val meaning: Int

        /** The Rich style's shape-coded badge. */
        data class Badge(val cls: CallClass) : Entry {
            override val label get() = callClassLabel(cls)
            override val meaning get() = badgeMeaning(cls)
        }

        /** The Simple style's arrow icon (a call taken elsewhere shares the incoming arrow). */
        data class TypeIcon(val type: CallType) : Entry {
            override val label get() = HistoryText.callType(type)
            override val meaning get() = typeMeaning(type)
        }

        data class Filter(val filter: RecentFilter, val rich: Boolean) : Entry {
            override val label get() = filter.labelRes
            override val meaning get() = filterMeaning(filter, rich)
        }

        data class Mark(val mark: RecentsMark, val rich: Boolean) : Entry {
            override val label get() = markLabel(mark)
            override val meaning get() = markMeaning(mark, rich)
        }
    }

    /** The Simple style's icon for a call of [type]. */
    fun simpleIcon(type: CallType): CallType = if (type == CallType.ANSWERED_EXTERNALLY) CallType.INCOMING else type

    /** The legend for the Rich or Simple style, section by section, in the order Recents shows things. */
    fun sections(rich: Boolean): List<Pair<Section, List<Entry>>> {
        val marks = RecentsMark.entries.filter { it.shownIn(rich) }.map { Entry.Mark(it, rich) }
        val calls: List<Entry> =
            if (rich) CallClass.entries.map { Entry.Badge(it) } else CallType.entries.map(::simpleIcon).distinct().map { Entry.TypeIcon(it) }
        return listOf(
            Section.CALLS to calls,
            Section.FILTERS to RecentFilter.entries.map { Entry.Filter(it, rich) } + marks.filter { it.mark.section == Section.FILTERS },
            Section.ROWS to marks.filter { it.mark.section == Section.ROWS },
        )
    }

    @StringRes
    fun footer(rich: Boolean): Int = if (rich) R.string.recents_legend_footer else R.string.recents_legend_footer_simple

    @StringRes
    private fun badgeMeaning(cls: CallClass): Int = when (cls) {
        CallClass.MISSED -> R.string.recents_legend_missed
        CallClass.DECLINED -> R.string.recents_legend_declined
        CallClass.INCOMING -> R.string.recents_legend_incoming
        CallClass.ANSWERED_ELSEWHERE -> R.string.recents_legend_elsewhere
        CallClass.VOICEMAIL -> R.string.recents_legend_voicemail
        CallClass.OUTGOING -> R.string.recents_legend_outgoing
        CallClass.NO_ANSWER -> R.string.recents_legend_no_answer
        CallClass.BLOCKED -> R.string.recents_legend_blocked
        CallClass.UNKNOWN -> R.string.recents_legend_unknown
    }

    @StringRes
    private fun typeMeaning(type: CallType): Int = when (type) {
        CallType.INCOMING, CallType.ANSWERED_EXTERNALLY -> R.string.recents_legend_simple_incoming
        CallType.OUTGOING -> R.string.recents_legend_simple_outgoing
        CallType.MISSED -> R.string.recents_legend_simple_missed
        CallType.REJECTED -> R.string.recents_legend_simple_declined
        CallType.BLOCKED -> R.string.recents_legend_simple_blocked
        CallType.VOICEMAIL -> R.string.recents_legend_simple_voicemail
        CallType.UNKNOWN -> R.string.recents_legend_simple_unknown
    }

    @StringRes
    private fun filterMeaning(f: RecentFilter, rich: Boolean): Int = when (f) {
        RecentFilter.ALL -> R.string.recents_legend_filter_all
        // Only the Rich chip counts the people still to call back.
        RecentFilter.MISSED -> if (rich) R.string.recents_legend_filter_missed else R.string.recents_legend_filter_missed_simple
        RecentFilter.INCOMING -> R.string.recents_legend_filter_incoming
        RecentFilter.OUTGOING -> R.string.recents_legend_filter_outgoing
        RecentFilter.UNKNOWN -> R.string.recents_legend_filter_unknown
        RecentFilter.CONTACTS -> R.string.recents_legend_filter_contacts
        RecentFilter.BLOCKED -> R.string.recents_legend_filter_blocked
        RecentFilter.VOICEMAIL -> R.string.recents_legend_filter_voicemail
    }

    @StringRes
    private fun markLabel(m: RecentsMark): Int = when (m) {
        RecentsMark.FILTER -> R.string.hist_filter
        RecentsMark.SAVED_FILTER -> R.string.recents_legend_saved_filter
        RecentsMark.ACCENT -> R.string.recents_legend_accent
        RecentsMark.NOT_RETURNED -> R.string.recents_legend_not_returned
        RecentsMark.COUNT -> R.string.recents_legend_count
        RecentsMark.COUNT_TEXT -> R.string.recents_legend_count_text
        RecentsMark.MISSED_NAME -> R.string.recents_legend_missed_name
        RecentsMark.SEQUENCE -> R.string.recents_legend_sequence
        RecentsMark.DURATION -> R.string.recents_legend_duration
        RecentsMark.CALL_BACK -> R.string.recents_call_back
        RecentsMark.VIDEO -> R.string.recents_video_call
        RecentsMark.PRIVATE -> R.string.recents_legend_private
        RecentsMark.SCREENING -> R.string.recents_legend_screening
    }

    /** Rich chips show a saved filter by its first letter, Simple ones by its name. */
    @StringRes
    private fun savedFilterMeaning(rich: Boolean): Int =
        if (rich) R.string.recents_legend_saved_filter_meaning else R.string.recents_legend_saved_filter_meaning_simple

    @StringRes
    private fun markMeaning(m: RecentsMark, rich: Boolean): Int = when (m) {
        RecentsMark.FILTER -> R.string.recents_legend_filter_meaning
        RecentsMark.SAVED_FILTER -> savedFilterMeaning(rich)
        RecentsMark.ACCENT -> R.string.recents_legend_accent_meaning
        RecentsMark.NOT_RETURNED -> R.string.recents_legend_not_returned_meaning
        RecentsMark.COUNT -> R.string.recents_legend_count_meaning
        RecentsMark.COUNT_TEXT -> R.string.recents_legend_count_text_meaning
        RecentsMark.MISSED_NAME -> R.string.recents_legend_missed_name_meaning
        RecentsMark.SEQUENCE -> R.string.recents_legend_sequence_meaning
        RecentsMark.DURATION -> R.string.recents_legend_duration_meaning
        RecentsMark.CALL_BACK -> R.string.recents_legend_call_back_meaning
        RecentsMark.VIDEO -> R.string.recents_legend_video_meaning
        RecentsMark.PRIVATE -> R.string.recents_legend_private_meaning
        RecentsMark.SCREENING -> R.string.recents_legend_screening_meaning
    }
}

/** The look of a legend line: the same badge, icon, chip or mark Recents draws in the style in use. */
@Composable
private fun LegendGlyph(entry: RecentsLegend.Entry) {
    Box(Modifier.widthIn(min = 40.dp), contentAlignment = Alignment.Center) {
        when (entry) {
            is RecentsLegend.Entry.Badge -> CallClassBadge(entry.cls, size = 32.dp)
            is RecentsLegend.Entry.TypeIcon -> CallTypeIcon(entry.type, size = 32.dp, describe = false)
            is RecentsLegend.Entry.Filter -> if (entry.rich) FilterIcon(entry.filter) else TextChipGlyph(stringResource(entry.filter.labelRes))
            is RecentsLegend.Entry.Mark -> MarkGlyph(entry.mark, entry.rich)
        }
    }
}

/** A Simple-style text chip, small. */
@Composable
private fun TextChipGlyph(text: String) {
    Surface(shape = ParleyShapes.tag, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** A saved filter's chip: its first letter (Rich) or its name (Simple). */
@Composable
private fun SavedFilterGlyph(rich: Boolean, sample: String) {
    if (rich) SavedFilterMonogram(stringResource(R.string.recents_legend_saved_filter)) else TextChipGlyph(sample)
}

@Composable
private fun MarkGlyph(m: RecentsMark, rich: Boolean) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val sample = stringResource(R.string.recents_legend_sample_name)
    val missed = CallTypeColors.of(CallClass.MISSED.hue)
    when (m) {
        RecentsMark.FILTER -> Icon(Icons.Rounded.Tune, null, Modifier.size(20.dp), tint = quiet)
        RecentsMark.SAVED_FILTER -> SavedFilterGlyph(rich, sample)
        RecentsMark.ACCENT -> Box(Modifier.size(width = 24.dp, height = 40.dp).callAccent(missed))
        RecentsMark.NOT_RETURNED -> Surface(color = missed.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface), shape = ParleyShapes.tag) {
            Text(sample, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        RecentsMark.COUNT -> CallCountChip(SAMPLE_COUNT, CallClass.MISSED)
        RecentsMark.COUNT_TEXT -> Text(stringResource(R.string.missed_name_count, sample, SAMPLE_COUNT), maxLines = 1)
        RecentsMark.MISSED_NAME -> Text(sample, color = MaterialTheme.colorScheme.error)
        RecentsMark.SEQUENCE -> CallSequenceDots(listOf(CallClass.MISSED, CallClass.MISSED, CallClass.OUTGOING))
        RecentsMark.DURATION -> CallDurationBar(CallGlance.durationFraction(SAMPLE_TALK_SEC), CallClass.INCOMING)
        RecentsMark.CALL_BACK -> Icon(Icons.Rounded.Call, null, Modifier.size(20.dp), tint = missed)
        RecentsMark.VIDEO -> VideoCallMark(size = 20.dp)
        RecentsMark.PRIVATE -> Text(PRIVATE_MARK, style = MaterialTheme.typography.titleMedium)
        RecentsMark.SCREENING -> Icon(Icons.Rounded.Shield, null, Modifier.size(20.dp), tint = quiet)
    }
}

/** A badge, chip or mark with its name and meaning. */
@Composable
private fun LegendRow(entry: RecentsLegend.Entry) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendGlyph(entry)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(entry.label), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(entry.meaning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val legendRequested = MutableStateFlow(false)

/** Recents ⋮ › "What do the colours mean?". */
@Composable
fun RecentsLegendMenuItem(closeMenu: () -> Unit) {
    DropdownMenuItem({ Text(stringResource(R.string.recents_legend_menu)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.HelpOutline, null) }, onClick = {
        closeMenu()
        legendRequested.value = true
    })
}

/** Shows the legend when asked from the Recents ⋮ menu. */
@Composable
fun RecentsLegendHost() {
    val shown by legendRequested.collectAsStateWithLifecycle()
    if (!shown) return
    ParleyDialog(
        onDismissRequest = { legendRequested.value = false },
        title = { Text(stringResource(R.string.recents_legend_title)) },
        text = {
            val rich = richCalls()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RecentsLegend.sections(rich).forEachIndexed { i, (section, entries) ->
                    Text(
                        stringResource(section.title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 16.dp, bottom = 4.dp).semantics { heading() },
                    )
                    entries.forEach { LegendRow(it) }
                }
                Text(
                    stringResource(RecentsLegend.footer(rich)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton({ legendRequested.value = false }) { Text(stringResource(R.string.main_close)) } },
    )
}

/** The small camera beside a call Android logged as a video call (Recents and number history). */
@Composable
fun VideoCallMark(modifier: Modifier = Modifier, size: Dp = 16.dp, contentDescription: String? = null) {
    Icon(Icons.Rounded.Videocam, contentDescription, modifier.size(size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The lock Recents puts before the name of a private contact. */
internal const val PRIVATE_MARK = "🔒"

private const val SAMPLE_COUNT = 3
private const val SAMPLE_TALK_SEC = 240L
