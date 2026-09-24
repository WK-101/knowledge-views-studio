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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.history.FilterPeriod
import app.parley.common.history.HistoryFilter
import app.parley.common.history.TypeGroup
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.parley.R

/**
 * H4: saved filter chips for the Recents filter row, plus a "Filter" chip that opens the editor
 * (SIM + type + period + duration). Put it inside the existing chip Row.
 */
@Composable
fun SavedFilterChips(vm: AppViewModel) {
    val active by vm.c.history.activeFilter.collectAsStateWithLifecycle()
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    val res = androidx.compose.ui.platform.LocalResources.current
    val simFallback = stringResource(R.string.hist_filter_sim)
    val simLabel = { id: String -> sims.firstOrNull { it.id == id }?.label ?: simFallback }

    prefs.savedFilters.forEach { f ->
        val on = f.sameCriteria(active) && f.name == active.name
        FilterChip(
            selected = on,
            onClick = { vm.c.history.activeFilter.value = if (on) HistoryFilter() else f },
            label = { Text(f.name.ifBlank { HistoryText.describe(res, f, simLabel) }) },
        )
    }
    val unsaved = !active.isEmpty && prefs.savedFilters.none { it.sameCriteria(active) && it.name == active.name }
    FilterChip(
        selected = unsaved,
        onClick = { editing = true },
        leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp)) },
        label = { Text(if (unsaved) HistoryText.describe(res, active, simLabel) else stringResource(R.string.hist_filter)) },
    )
    if (editing) FilterEditorSheet(vm, active) { editing = false }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterEditorSheet(vm: AppViewModel, active: HistoryFilter, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val prefs by vm.c.history.prefs.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(active) }
    var name by remember { mutableStateOf(active.name) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.hist_filter_title), style = MaterialTheme.typography.titleLarge)

            Label(stringResource(R.string.hist_filter_type))
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
                Label(stringResource(R.string.hist_filter_sim))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(draft.simId == null, { draft = draft.copy(simId = null) }, { Text(stringResource(R.string.hist_filter_any)) })
                    sims.forEach { s -> FilterChip(draft.simId == s.id, { draft = draft.copy(simId = s.id) }, { Text(s.label) }) }
                }
            }
            Label(stringResource(R.string.hist_filter_when))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPeriod.entries.forEach { p -> FilterChip(draft.period == p, { draft = draft.copy(period = p) }, { Text(stringResource(HistoryText.period(p))) }) }
            }
            Label(stringResource(R.string.hist_filter_talk_time))
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
                                IconButton({ scope.launch { vm.c.history.prefs.setSavedFilters(prefs.savedFilters - f) } }, Modifier.size(24.dp)) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.hist_filter_delete, f.name), Modifier.size(16.dp))
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
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

private val durations: List<Pair<Int, Pair<Long?, Long?>>> = listOf(
    R.string.hist_filter_any to (null to null),
    R.string.hist_dur_not_answered to (null to 0L),
    R.string.hist_dur_under_1 to (1L to 59L),
    R.string.hist_dur_1_plus to (60L to null),
    R.string.hist_dur_5_plus to (300L to null),
    R.string.hist_dur_30_plus to (1800L to null),
)
