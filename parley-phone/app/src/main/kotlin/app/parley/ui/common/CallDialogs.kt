package app.parley.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel

/** Confirm-before-call and SIM chooser. Shown from the root so every screen can place calls. */
@Composable
fun CallDialogs(vm: AppViewModel) {
    val pending by vm.pendingCall.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val p = pending ?: return
    var remember by remember(p) { mutableStateOf(false) }
    val who = p.name?.let { "$it (${Format.number(p.number, vm.countryIso)})" } ?: Format.number(p.number, vm.countryIso)

    if (p.chooseSim) {
        AlertDialog(
            onDismissRequest = { vm.pendingCall.value = null },
            title = { Text("Call $who with") },
            text = {
                Column {
                    sims.forEach { sim ->
                        val plan = app.parley.ui.history.simPlanSummary(vm, sim.id)
                        ListItem(
                            headlineContent = { Text(sim.label) },
                            supportingContent = listOfNotNull(sim.subtitle, plan).joinToString("\n").ifEmpty { null }?.let { { Text(it) } },
                            leadingContent = { Icon(Icons.Rounded.SimCard, null, tint = if (sim.color != 0) Color(sim.color) else Color.Unspecified) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { vm.place(p.number, sim.id, remember) },
                        )
                    }
                    Row(Modifier.fillMaxWidth().clickable { remember = !remember }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(remember, { remember = it })
                        Text("Remember for this number")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ vm.pendingCall.value = null }) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = { vm.pendingCall.value = null },
            title = { Text("Call $who?") },
            confirmButton = { TextButton({ vm.place(p.number, null) }) { Text("Call") } },
            dismissButton = { TextButton({ vm.pendingCall.value = null }) { Text("Cancel") } },
        )
    }
}
