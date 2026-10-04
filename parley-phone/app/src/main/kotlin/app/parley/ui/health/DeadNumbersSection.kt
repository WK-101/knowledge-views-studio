package app.parley.ui.health

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.calls.NumberSignals
import app.parley.common.suspendRunCatching
import app.parley.common.calls.DeadNumberRadar
import app.parley.common.people.ContactRef
import app.parley.ui.Bidi
import app.parley.ui.Destination
import app.parley.ui.ParleyListItem
import app.parley.ui.Routes
import app.parley.ui.Section
import app.parley.ui.Spacing
import app.parley.ui.common.Format
import kotlinx.coroutines.launch

/**
 * "Numbers that seem out of service": saved numbers whose calls kept failing on more than one day with nothing going
 * through since. Each row offers to try again, edit the number, move it to the contact's note (with Undo) or dismiss
 * it until it fails again; nothing changes by itself.
 */
internal fun LazyListScope.deadNumbersSection(vm: AppViewModel, dead: List<NumberSignals.DeadNumber>, open: (Destination) -> Unit) {
    if (dead.isEmpty()) return
    item(key = "dead_title") {
        Section(stringResource(R.string.health_group, stringResource(R.string.health_out_of_service), dead.size))
    }
    item(key = "dead_explain") {
        Text(
            stringResource(R.string.health_out_of_service_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.xs),
        )
    }
    dead.forEach { d -> item(key = "dead_${d.navId}_${d.lineKey}") { DeadNumberRow(vm, d, open) } }
}

@Composable
private fun DeadNumberRow(vm: AppViewModel, d: NumberSignals.DeadNumber, open: (Destination) -> Unit) {
    val shown = Bidi.ltr(Format.number(d.number, vm.countryIso))
    val why = when (d.finding.reason) {
        DeadNumberRadar.Reason.NOT_IN_SERVICE -> pluralStringResource(R.plurals.health_out_of_service_network, d.finding.failures, d.finding.failures)
    }
    ParleyListItem(
        modifier = Modifier.clickable { open(Routes.contact(d.navId)) },
        headlineContent = { Text(d.name) },
        supportingContent = { Text(stringResource(R.string.health_out_of_service_line, shown, why), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { DeadNumberMenu(vm, d, shown, open) },
    )
}

/** ⋮ on a row: try again, edit the number, move it to the note, dismiss. */
@Composable
private fun DeadNumberMenu(vm: AppViewModel, d: NumberSignals.DeadNumber, shown: String, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val editor = (ContactRef.ofNavId(d.navId) as? ContactRef.Private)?.let { Routes.edit(vault = it.vaultId) } ?: Routes.edit(id = d.navId)
    Box {
        IconButton({ expanded = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.health_out_of_service_more, d.name, shown)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.health_out_of_service_try)) },
                leadingIcon = { Icon(Icons.Rounded.Call, null) },
                onClick = { expanded = false; vm.requestCall(d.number, d.name) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.health_out_of_service_edit)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                onClick = { expanded = false; open(editor) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.health_out_of_service_to_note)) },
                leadingIcon = { Icon(Icons.Rounded.EditNote, null) },
                onClick = {
                    expanded = false
                    val since = DateUtils.formatDateTime(
                        context, d.finding.firstFailureAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR,
                    )
                    val line = res.getString(R.string.health_out_of_service_note, d.number, since)
                    scope.launch {
                        val undo = suspendRunCatching { NumberSignals.moveToNote(vm.c, d, line) }.getOrNull()
                        if (undo == null) {
                            // Kept by an account Parley can't change this way: the editor shows what can be done.
                            vm.toast(res.getString(R.string.health_out_of_service_not_moved))
                            open(editor)
                        } else {
                            vm.offerUndo(res.getString(R.string.health_out_of_service_moved, d.name), undo)
                        }
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.health_out_of_service_dismiss)) },
                leadingIcon = { Icon(Icons.Rounded.Close, null) },
                onClick = {
                    expanded = false
                    NumberSignals.dismissDead(vm.c, d.lineKey)
                    vm.offerUndo(res.getString(R.string.health_out_of_service_dismissed)) { vm.c.numberAdvice.undismissDead(d.lineKey) }
                },
            )
        }
    }
}
