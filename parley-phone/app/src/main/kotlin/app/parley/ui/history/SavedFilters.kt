package app.parley.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import app.parley.common.ux.CompactChips
import app.parley.ui.ParleyShapes
import app.parley.ui.home.CompactFilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.history.FilterPeriod
import app.parley.common.history.HistoryFilter
import app.parley.common.history.TypeGroup
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleySheet
import app.parley.ui.ListSectionHeader
import app.parley.ui.Spacing
import app.parley.ui.LocalSnackbar
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Saved filter chips for the Recents filter row, plus a "Filter" chip that opens the editor
 * (SIM + type + period + duration). Put it inside the existing chip Row. [compact] (Recents in the Rich style) draws
 * them as icon chips: a saved filter shows the first letter of its name, the Filter chip its tune icon, and a
 * selected one also shows its name when [showLabels].
 */
@Composable
fun SavedFilterChips(vm: AppViewModel, compact: Boolean = false, showLabels: Boolean = false) {
    val active by vm.c.history.activeFilter.collectAsStateWithLifecycle()
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    val describe = rememberFilterDescriber(vm)

    prefs.savedFilters.forEach { f ->
        val on = f.sameCriteria(active) && f.name == active.name
        val label = f.name.ifBlank { describe(f) }
        val toggle = { vm.c.history.activeFilter.value = if (on) HistoryFilter() else f }
        if (compact) {
            CompactFilterChip(on, label, showLabel = showLabels && on, onClick = toggle) { SavedFilterMonogram(f.name) }
        } else {
            FilterChip(selected = on, onClick = toggle, label = { Text(label) })
        }
    }
    val unsaved = !active.isEmpty && prefs.savedFilters.none { it.sameCriteria(active) && it.name == active.name }
    val filterLabel = if (unsaved) describe(active) else stringResource(R.string.hist_filter)
    if (compact) {
        CompactFilterChip(unsaved, filterLabel, showLabel = showLabels && unsaved, onClick = { editing = true }) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(20.dp))
        }
    } else {
        FilterChip(
            selected = unsaved,
            onClick = { editing = true },
            leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
            label = { Text(filterLabel) },
        )
    }
    if (editing) FilterEditorSheet(vm, active) { editing = false }
}

/** How a filter without a name is described on its chip ("Missed · SIM 2 · This week"). */
@Composable
private fun rememberFilterDescriber(vm: AppViewModel): (HistoryFilter) -> String {
    val sims by vm.sims.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val simFallback = stringResource(R.string.blk_editor_sim)
    return { f -> HistoryText.describe(res, f, { id -> sims.firstOrNull { it.id == id }?.label ?: simFallback }) }
}

/**
 * The name the selected saved-filter or Filter chip shows, or null when no call-history filter is on. The Recents
 * row measures it to decide whether selected chips have room for their names.
 */
@Composable
fun activeFilterChipLabel(vm: AppViewModel): String? {
    val active by vm.c.history.activeFilter.collectAsStateWithLifecycle()
    val describe = rememberFilterDescriber(vm)
    return if (active.isEmpty) null else active.name.ifBlank { describe(active) }
}

/** Number of chips [SavedFilterChips] draws (the saved filters and the Filter chip). */
@Composable
fun savedFilterChipCount(vm: AppViewModel): Int {
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    return prefs.savedFilters.size + 1
}

/** A saved filter's icon: the first letter of its name on a small round tag, or a bookmark when it has none. */
@Composable
internal fun SavedFilterMonogram(name: String) {
    val letter = remember(name) { CompactChips.monogram(name) }
    if (letter.isEmpty()) {
        Icon(Icons.Rounded.BookmarkBorder, null, Modifier.size(20.dp))
        return
    }
    Box(
        Modifier.size(24.dp).clip(ParleyShapes.pill).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 1)
    }
}

/** Deletes a saved filter at once; the snackbar's Undo puts the list back as it was. */
@Composable
private fun rememberFilterDelete(vm: AppViewModel): (List<HistoryFilter>, HistoryFilter) -> Unit {
    val snackbar = LocalSnackbar.current
    val res = LocalResources.current
    val undo = stringResource(R.string.dc_undo)
    return { before, f ->
        vm.viewModelScope.launch { vm.c.history.prefs.setSavedFilters(before - f) }
        snackbar?.show(res.getString(R.string.hist_filter_deleted, f.name), undo) {
            vm.viewModelScope.launch { vm.c.history.prefs.setSavedFilters(before) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterEditorSheet(vm: AppViewModel, active: HistoryFilter, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val delete = rememberFilterDelete(vm)
    val sims by vm.sims.collectAsStateWithLifecycle()
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(active) }
    var name by rememberSaveable { mutableStateOf(active.name) }

    ParleySheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.hist_filter_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })

            Label(stringResource(R.string.hist_pdf_col_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeGroup.entries.forEach { g ->
                    FilterChip(
                        selected = g in draft.types,
                        onClick = { draft = draft.copy(types = if (g in draft.types) draft.types - g else draft.types + g) },
                        label = { Text(stringResource(HistoryText.typeGroup(g))) },
                    )
                }
            }
            if (sims.size > 1) {
                Label(stringResource(R.string.blk_editor_sim))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(draft.simId == null, { draft = draft.copy(simId = null) }, { Text(stringResource(R.string.hist_filter_any)) })
                    sims.forEach { s -> FilterChip(draft.simId == s.id, { draft = draft.copy(simId = s.id) }, { Text(s.label) }) }
                }
            }
            Label(stringResource(R.string.blk_editor_when))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPeriod.entries.forEach { p -> FilterChip(draft.period == p, { draft = draft.copy(period = p) }, { Text(stringResource(HistoryText.period(p))) }) }
            }
            Label(stringResource(R.string.hist_talk_time_label))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                durations.forEach { (label, range) ->
                    FilterChip(
                        selected = draft.minDurationSec == range.first && draft.maxDurationSec == range.second,
                        onClick = { draft = draft.copy(minDurationSec = range.first, maxDurationSec = range.second) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it.take(30) }, label = { Text(stringResource(R.string.hist_filter_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ vm.c.history.activeFilter.value = HistoryFilter(); onDismiss() }, enabled = !active.isEmpty) { Text(stringResource(R.string.hist_filter_clear)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        val saved = draft.copy(name = name.trim())
                        scope.launch { vm.c.history.prefs.setSavedFilters(prefs.savedFilters.filter { it.name != saved.name } + saved) }
                        vm.c.history.activeFilter.value = saved
                        onDismiss()
                    },
                    enabled = name.isNotBlank() && !draft.isEmpty,
                ) { Text(stringResource(R.string.hist_filter_save)) }
                // Apply only when the filter actually changed.
                Button({ vm.c.history.activeFilter.value = draft.copy(name = ""); onDismiss() }, enabled = !draft.sameCriteria(active)) { Text(stringResource(R.string.hist_filter_apply)) }
            }

            if (prefs.savedFilters.isNotEmpty()) {
                Label(stringResource(R.string.hist_filter_saved))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    prefs.savedFilters.forEach { f ->
                        InputChip(
                            selected = false,
                            onClick = { draft = f; name = f.name },
                            label = { Text(f.name) },
                            trailingIcon = {
                                // A full-size touch target; the filter can be brought back from the snackbar.
                                IconButton({ delete(prefs.savedFilters, f) }) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.case_reference_delete, f.name), Modifier.size(16.dp))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    ListSectionHeader(text, inset = 0.dp, top = Spacing.l)
}

private val durations: List<Pair<Int, Pair<Long?, Long?>>> = listOf(
    R.string.hist_filter_any to (null to null),
    R.string.hist_dur_not_answered to (null to 0L),
    R.string.hist_dur_under_1 to (1L to 59L),
    R.string.hist_dur_1_plus to (60L to null),
    R.string.hist_dur_5_plus to (300L to null),
    R.string.hist_dur_30_plus to (1800L to null),
)
