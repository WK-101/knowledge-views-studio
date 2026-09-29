package app.parley.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.RecentFilter
import app.parley.common.ux.CallClass
import app.parley.common.ux.CompactChips
import app.parley.ui.CallClassBadge
import app.parley.ui.ParleyMotion
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyTooltip
import app.parley.ui.Spacing
import app.parley.ui.history.SavedFilterChips
import app.parley.ui.history.activeFilterChipLabel
import app.parley.ui.history.savedFilterChipCount

/** An icon chip's touch target, and so its width in the row while it shows no name. */
private val ChipTarget = 48.dp
private val ChipGap = 2.dp

/** The visible pill of an icon chip (the rest of its [ChipTarget] is touch area around it). */
private val ChipPill = 40.dp

/** Row padding that puts the first and last pill's outer edge on the list's own inset, like the rows below. */
private val RowInset = Spacing.listInset - (ChipTarget - ChipPill) / 2

/** A selected chip's name is cut short beyond this, so one long saved-filter name can't push the rest away. */
private val MaxLabel = 120.dp

/**
 * The filter chips above the calls. Simple style: text chips. Rich style: icon chips that use the rows' own
 * shape-coded call badges (so the chip and the calls it keeps look alike); a selected chip also shows its name when
 * the whole row fits on one line, and every chip names itself on long-press and to TalkBack.
 */
@Composable
internal fun RecentsFilterRow(
    vm: AppViewModel,
    filter: RecentFilter,
    onFilter: (RecentFilter) -> Unit,
    /** The Voicemail chip shows while Parley can read voicemail (default phone app), or while it's selected. */
    voicemailChip: Boolean,
    unheardVoicemail: Int,
    /** People still to call back, counted on the Missed chip in the Rich style. */
    toReturn: Int,
    rich: Boolean,
) {
    val filters = RecentFilter.entries.filter { it != RecentFilter.VOICEMAIL || voicemailChip || filter == it }
    val counts = ChipCounts(unheardVoicemail, if (rich) toReturn else 0)
    if (rich) RichFilterRow(vm, filters, filter, onFilter, counts) else SimpleFilterRow(vm, filters, filter, onFilter, counts)
}

/** The numbers some chips carry: unheard voicemail, and (Rich style) people still to call back. */
private class ChipCounts(val voicemail: Int, val toReturn: Int) {
    fun of(f: RecentFilter): Int = when (f) {
        RecentFilter.VOICEMAIL -> voicemail
        RecentFilter.MISSED -> toReturn
        else -> 0
    }
}

/** What TalkBack reads for a chip with a count ("Missed · 2 to call back"), or null for its plain name. */
@Composable
private fun spokenChip(f: RecentFilter, counts: ChipCounts): String? {
    val n = counts.of(f)
    return when {
        n <= 0 -> null
        f == RecentFilter.VOICEMAIL -> pluralStringResource(R.plurals.recents_voicemail_new, n, n)
        else -> stringResource(f.labelRes) + stringResource(R.string.main_separator) + pluralStringResource(R.plurals.recents_to_call_back, n, n)
    }
}

@Composable
private fun SimpleFilterRow(vm: AppViewModel, filters: List<RecentFilter>, filter: RecentFilter, onFilter: (RecentFilter) -> Unit, counts: ChipCounts) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        filters.forEach { f ->
            val n = counts.of(f)
            val words = spokenChip(f, counts)
            FilterChip(
                selected = filter == f,
                onClick = { onFilter(f) },
                label = {
                    Text(stringResource(f.labelRes))
                    if (n > 0) {
                        Spacer(Modifier.width(6.dp))
                        Badge { Text(n.toString()) }
                    }
                },
                modifier = if (words != null) Modifier.semantics { contentDescription = words } else Modifier,
            )
        }
        SavedFilterChips(vm)
    }
}

@Composable
private fun RichFilterRow(vm: AppViewModel, filters: List<RecentFilter>, filter: RecentFilter, onFilter: (RecentFilter) -> Unit, counts: ChipCounts) {
    // Selected chips show their names only when the whole row still fits without scrolling sideways.
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val selectedLabels = listOfNotNull(stringResource(filter.labelRes), activeFilterChipLabel(vm))
    val chips = filters.size + savedFilterChipCount(vm)
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth - with(density) { 2 * RowInset.roundToPx() }
        val iconsFit = with(density) { CompactChips.iconsFit(available, chips, ChipTarget.roundToPx(), ChipGap.roundToPx()) }
        val showLabels = with(density) {
            val labelWidths = selectedLabels.map { label ->
                // The name, capped, plus the room between it and the icon.
                minOf(measurer.measure(label, style, maxLines = 1).size.width, MaxLabel.roundToPx()) + LabelExtra.roundToPx()
            }
            CompactChips.labelsFit(available, chips, ChipTarget.roundToPx(), ChipGap.roundToPx(), labelWidths)
        }
        // When every chip fits, they spread over the whole row (first on the start inset, last on the end inset, the
        // rest evenly between, mirrored in RTL), and a selected chip's name takes its room from the gaps. Packing
        // them at the start left an empty stretch on the end side (on the left in RTL). Only a row too long for
        // the screen scrolls sideways, packed.
        Row(
            Modifier.fillMaxWidth().then(if (iconsFit) Modifier else Modifier.horizontalScroll(scroll))
                .padding(horizontal = RowInset, vertical = 4.dp),
            horizontalArrangement = if (iconsFit) Arrangement.SpaceBetween else Arrangement.spacedBy(ChipGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            filters.forEach { f ->
                val on = filter == f
                val n = counts.of(f)
                CompactFilterChip(on, stringResource(f.labelRes), showLabel = showLabels && on, onClick = { onFilter(f) }, spoken = spokenChip(f, counts)) {
                    if (n > 0) BadgedBox(badge = { Badge { Text(n.toString()) } }) { FilterIcon(f) } else FilterIcon(f)
                }
            }
            SavedFilterChips(vm, compact = true, showLabels = showLabels)
        }
    }
}

/** A filter's icon: the same call badge as the calls it keeps; All has a history clock. */
@Composable
private fun FilterIcon(f: RecentFilter) {
    val cls = f.callClass
    if (cls != null) CallClassBadge(cls, size = 24.dp) else Icon(Icons.Rounded.History, null, Modifier.size(22.dp))
}

/** Room a shown name adds to an icon chip (the space before the name and after it). */
private val LabelExtra = 12.dp

/**
 * One icon chip of the Rich Recents filter row: [icon] on a pill with a 48 dp target, filled when [selected] and
 * outlined otherwise. With [showLabel] the chip grows to show [label] too. Long-press shows [label] as a tooltip;
 * TalkBack reads [spoken] (or [label]) and the selected state.
 */
@Composable
fun CompactFilterChip(
    selected: Boolean,
    label: String,
    showLabel: Boolean,
    onClick: () -> Unit,
    spoken: String? = null,
    icon: @Composable () -> Unit,
) {
    val words = spoken ?: label
    ParleyTooltip(label) {
        Surface(
            selected = selected,
            onClick = onClick,
            shape = ParleyShapes.pill,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.semantics { contentDescription = words },
        ) {
            Row(Modifier.height(ChipPill).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                icon()
                AnimatedVisibility(
                    showLabel,
                    enter = expandHorizontally(ParleyMotion.spatial()) + fadeIn(ParleyMotion.effects()),
                    exit = shrinkHorizontally(ParleyMotion.fastSpatial()) + fadeOut(ParleyMotion.fastEffects()),
                ) {
                    Text(
                        label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp, end = 4.dp).widthIn(max = MaxLabel),
                    )
                }
            }
        }
    }
}

/** Chip text of a Recents filter. */
internal val RecentFilter.labelRes: Int
    get() = when (this) {
        RecentFilter.ALL -> R.string.recents_filter_all
        RecentFilter.MISSED -> R.string.recents_filter_missed
        RecentFilter.INCOMING -> R.string.recents_filter_incoming
        RecentFilter.OUTGOING -> R.string.recents_filter_outgoing
        RecentFilter.BLOCKED -> R.string.recents_filter_blocked
        RecentFilter.VOICEMAIL -> R.string.recents_filter_voicemail
    }

/** The call badge a filter's icon chip shows: the same one as the calls it keeps. All has none (a history icon). */
private val RecentFilter.callClass: CallClass?
    get() = when (this) {
        RecentFilter.ALL -> null
        RecentFilter.MISSED -> CallClass.MISSED
        RecentFilter.INCOMING -> CallClass.INCOMING
        RecentFilter.OUTGOING -> CallClass.OUTGOING
        RecentFilter.BLOCKED -> CallClass.BLOCKED
        RecentFilter.VOICEMAIL -> CallClass.VOICEMAIL
    }
