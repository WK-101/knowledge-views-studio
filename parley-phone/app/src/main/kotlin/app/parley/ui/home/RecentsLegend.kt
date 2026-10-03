package app.parley.ui.home

import androidx.annotation.StringRes
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

/**
 * Marks on Recents other than the call badges and the filter chips: the Filter and saved-filter chips, and what a row
 * can show next to its name and time. Each one has a line in the legend.
 */
internal enum class RecentsMark(val section: RecentsLegend.Section) {
    FILTER(RecentsLegend.Section.FILTERS),
    SAVED_FILTER(RecentsLegend.Section.FILTERS),
    COUNT(RecentsLegend.Section.ROWS),
    SEQUENCE(RecentsLegend.Section.ROWS),
    DURATION(RecentsLegend.Section.ROWS),
    CALL_BACK(RecentsLegend.Section.ROWS),
    VIDEO(RecentsLegend.Section.ROWS),
    PRIVATE(RecentsLegend.Section.ROWS),
    SCREENING(RecentsLegend.Section.ROWS),
}

/**
 * Recents ⋮ › "What do the colours mean?": everything Recents draws, in three groups. It's built from the enums
 * themselves ([CallClass], [RecentFilter], [RecentsMark]) with exhaustive `when`s, so a new badge, chip or mark
 * can't reach Recents without a line here (RecentsLegendTest checks it too).
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

        data class Badge(val cls: CallClass) : Entry {
            override val label get() = callClassLabel(cls)
            override val meaning get() = badgeMeaning(cls)
        }

        data class Filter(val filter: RecentFilter) : Entry {
            override val label get() = filter.labelRes
            override val meaning get() = filterMeaning(filter)
        }

        data class Mark(val mark: RecentsMark) : Entry {
            override val label get() = markLabel(mark)
            override val meaning get() = markMeaning(mark)
        }
    }

    /** The legend, section by section, in the order Recents shows things: the chips' order, then the rows'. */
    val sections: List<Pair<Section, List<Entry>>> by lazy {
        val marks = RecentsMark.entries.map { Entry.Mark(it) }
        listOf(
            Section.CALLS to CallClass.entries.map { Entry.Badge(it) },
            Section.FILTERS to RecentFilter.entries.map { Entry.Filter(it) } + marks.filter { it.mark.section == Section.FILTERS },
            Section.ROWS to marks.filter { it.mark.section == Section.ROWS },
        )
    }

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
    private fun filterMeaning(f: RecentFilter): Int = when (f) {
        RecentFilter.ALL -> R.string.recents_legend_filter_all
        RecentFilter.MISSED -> R.string.recents_legend_filter_missed
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
        RecentsMark.COUNT -> R.string.recents_legend_count
        RecentsMark.SEQUENCE -> R.string.recents_legend_sequence
        RecentsMark.DURATION -> R.string.recents_legend_duration
        RecentsMark.CALL_BACK -> R.string.recents_call_back
        RecentsMark.VIDEO -> R.string.recents_video_call
        RecentsMark.PRIVATE -> R.string.recents_legend_private
        RecentsMark.SCREENING -> R.string.recents_legend_screening
    }

    @StringRes
    private fun markMeaning(m: RecentsMark): Int = when (m) {
        RecentsMark.FILTER -> R.string.recents_legend_filter_meaning
        RecentsMark.SAVED_FILTER -> R.string.recents_legend_saved_filter_meaning
        RecentsMark.COUNT -> R.string.recents_legend_count_meaning
        RecentsMark.SEQUENCE -> R.string.recents_legend_sequence_meaning
        RecentsMark.DURATION -> R.string.recents_legend_duration_meaning
        RecentsMark.CALL_BACK -> R.string.recents_legend_call_back_meaning
        RecentsMark.VIDEO -> R.string.recents_legend_video_meaning
        RecentsMark.PRIVATE -> R.string.recents_legend_private_meaning
        RecentsMark.SCREENING -> R.string.recents_legend_screening_meaning
    }
}

/** The look of a legend line: the same badge, chip icon or mark Recents draws. */
@Composable
private fun LegendGlyph(entry: RecentsLegend.Entry) {
    Box(Modifier.widthIn(min = 40.dp), contentAlignment = Alignment.Center) {
        when (entry) {
            is RecentsLegend.Entry.Badge -> CallClassBadge(entry.cls, size = 32.dp)
            is RecentsLegend.Entry.Filter -> FilterIcon(entry.filter)
            is RecentsLegend.Entry.Mark -> MarkGlyph(entry.mark)
        }
    }
}

@Composable
private fun MarkGlyph(m: RecentsMark) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    when (m) {
        RecentsMark.FILTER -> Icon(Icons.Rounded.Tune, null, Modifier.size(20.dp), tint = quiet)
        RecentsMark.SAVED_FILTER -> SavedFilterMonogram(stringResource(R.string.recents_legend_saved_filter))
        RecentsMark.COUNT -> CallCountChip(SAMPLE_COUNT, CallClass.MISSED)
        RecentsMark.SEQUENCE -> CallSequenceDots(listOf(CallClass.MISSED, CallClass.MISSED, CallClass.OUTGOING))
        RecentsMark.DURATION -> CallDurationBar(CallGlance.durationFraction(SAMPLE_TALK_SEC), CallClass.INCOMING)
        RecentsMark.CALL_BACK -> Icon(Icons.Rounded.Call, null, Modifier.size(20.dp), tint = CallTypeColors.of(CallClass.MISSED.hue))
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RecentsLegend.sections.forEachIndexed { i, (section, entries) ->
                    Text(
                        stringResource(section.title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (i == 0) 0.dp else 16.dp, bottom = 4.dp).semantics { heading() },
                    )
                    entries.forEach { LegendRow(it) }
                }
                Text(
                    stringResource(R.string.recents_legend_footer),
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
