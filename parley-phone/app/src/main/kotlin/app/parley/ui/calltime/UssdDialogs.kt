package app.parley.ui.calltime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.calltime.UssdState
import app.parley.data.PlaceResult
import app.parley.ui.common.Format
import app.parley.ui.common.Intents
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** The carrier's reply to a USSD code (A13). Shown from the root, like the call dialogs. */
@Composable
fun UssdDialog(vm: AppViewModel) {
    val state by vm.ussd.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    when (val s = state) {
        null -> Unit
        is UssdState.ChooseSim -> AlertDialog(
            onDismissRequest = vm.ussd::dismiss,
            title = { Text(stringResource(R.string.ct_ussd_send_with, bidiLtr(s.code))) },
            text = {
                Column {
                    s.sims.forEach { sim ->
                        ListItem(
                            headlineContent = { Text(sim.label) },
                            leadingContent = { Icon(Icons.Rounded.SimCard, null, tint = if (sim.color != 0) Color(sim.color) else Color.Unspecified) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { vm.ussd.send(s.code, sim.id, s.sims) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(vm.ussd::dismiss) { Text(stringResource(R.string.set_cancel)) } },
        )
        is UssdState.Sending -> AlertDialog(
            onDismissRequest = vm.ussd::dismiss,
            title = { Text(bidiLtr(s.code)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(s.simLabel?.let { stringResource(R.string.ct_ussd_asking_sim, it) } ?: stringResource(R.string.ct_ussd_asking))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(vm.ussd::dismiss) { Text(stringResource(R.string.set_cancel)) } },
        )
        is UssdState.Reply -> AlertDialog(
            onDismissRequest = vm.ussd::dismiss,
            title = { Text(listOfNotNull(bidiLtr(s.code), s.simLabel).joinToString(" · ")) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodyLarge) }
                    if (!s.ok) {
                        Text(
                            stringResource(R.string.ct_ussd_dial_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(vm.ussd::dismiss) { Text(stringResource(R.string.ct_close)) } },
            dismissButton = {
                if (s.ok) {
                    TextButton({ Intents.copy(context, s.text) }) { Text(stringResource(R.string.ct_copy)) }
                } else {
                    TextButton({
                        vm.ussd.dismiss()
                        // Placed through Telecom directly, so it isn't caught as USSD again.
                        scope.launch { (vm.c.placer.call(s.code, s.simId) as? PlaceResult.Failed)?.let { vm.toast(it.reason) } }
                    }) { Text(stringResource(R.string.ct_ussd_dial_as_call)) }
                }
            },
        )
    }
}

/** Earlier replies (balance checks and the like), newest first. Stored on this phone only. */
@Composable
fun UssdHistoryDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val history by vm.c.calling.ussdHistory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settingTitle("ussd")) },
        text = {
            if (history.isEmpty()) {
                Text(stringResource(R.string.ct_ussd_history_empty))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(history, key = { it.at.toString() + it.code }) { e ->
                        ListItem(
                            overlineContent = { Text(listOfNotNull(bidiLtr(e.code), e.simLabel, Format.shortWhen(context, e.at)).joinToString(" · ")) },
                            headlineContent = { Text(e.reply, style = MaterialTheme.typography.bodyMedium) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { vm.requestCall(e.code); onDismiss() },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ct_close)) } },
        dismissButton = { if (history.isNotEmpty()) TextButton({ vm.c.calling.clearUssd() }) { Text(stringResource(R.string.set_clear)) } },
    )
}
