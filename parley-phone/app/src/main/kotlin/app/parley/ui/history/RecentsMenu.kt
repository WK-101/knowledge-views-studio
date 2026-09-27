package app.parley.ui.history

import app.parley.ui.activityViewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.RecentsLayout
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Call-list layout

/** The names of the three layouts, in [RecentsLayout] order. */
@Composable
fun recentsLayoutLabels(): List<String> = listOf(
    stringResource(R.string.recents_layout_grouped),
    stringResource(R.string.recents_layout_chronological),
    stringResource(R.string.recents_layout_by_day),
)

private val layoutRequested = MutableStateFlow(false)
private val clearRequested = MutableStateFlow(false)

/** Recents ⋮ › "Call list layout" (the quick toggle; the same setting is in Settings › Recents & history). */
@Composable
fun RecentsLayoutMenuItem(vm: AppViewModel, closeMenu: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    DropdownMenuItem(
        { Text(stringResource(R.string.recents_layout_menu, recentsLayoutLabels()[s.recentsLayout.ordinal])) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ViewList, null) },
        onClick = {
            closeMenu()
            layoutRequested.value = true
        },
    )
}

/** Recents ⋮ › "Clear call history…". */
@Composable
fun ClearHistoryMenuItem(closeMenu: () -> Unit) {
    DropdownMenuItem({ Text(stringResource(R.string.clear_history_menu)) }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = {
        closeMenu()
        clearRequested.value = true
    })
}

/** Shows the layout picker and "Clear call history" when asked from the Recents ⋮ menu. */
@Composable
fun RecentsMenuDialogs(vm: AppViewModel, open: (String) -> Unit) {
    val layout by layoutRequested.collectAsStateWithLifecycle()
    val clear by clearRequested.collectAsStateWithLifecycle()
    if (layout) RecentsLayoutDialog(vm) { layoutRequested.value = false }
    if (clear) {
        val groups by activityViewModel<app.parley.ui.home.RecentsViewModel>().groups.collectAsStateWithLifecycle()
        val shown = remember(groups) { groups.orEmpty().flatMap { it.calls } }
        ClearHistoryDialog(vm, shown, open) { clearRequested.value = false }
    }
}

@Composable
private fun RecentsLayoutDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val labels = recentsLayoutLabels()
    val hints = listOf(
        stringResource(R.string.recents_layout_grouped_hint),
        stringResource(R.string.recents_layout_chronological_hint),
        stringResource(R.string.recents_layout_by_day_hint),
    )
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settingTitle("recents_layout")) },
        text = {
            Column(Modifier.selectableGroup()) {
                RecentsLayout.entries.forEachIndexed { i, l ->
                    ChoiceItem(labels[i], hints[i], s.recentsLayout == l) {
                        scope.launch { vm.c.settings.update { it.copy(recentsLayout = l) } }
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.clear_history_close)) } },
    )
}

@Composable
internal fun ChoiceItem(title: String, sub: String?, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = { RadioButton(selected, onClick = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.alpha(0.6f)),
    )
}
