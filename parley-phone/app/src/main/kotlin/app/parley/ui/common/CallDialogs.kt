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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.PendingCall
import app.parley.R
import app.parley.common.SimAccount
import app.parley.ui.Bidi

/** Confirm-before-call and SIM chooser. Shown from the root so every screen can place calls. */
@Composable
fun CallDialogs(vm: AppViewModel) {
    val pending by vm.pendingCall.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val p = pending ?: return
    CallQuestions(
        p, sims, vm.countryIso,
        planSummary = { app.parley.ui.history.simPlanSummary(vm, it) },
        onUpdate = { vm.pendingCall.value = it },
        onPlace = { number, simId, remember, confirmed -> vm.place(number, simId, remember, confirmed) },
    )
}

/**
 * The questions of one [PendingCall] (see [app.parley.CallGate]), at most one dialog at a time: the dial guard's
 * warnings with the allowance note, then the SIM choice, else "Call Ana?". Answering one never asks it again.
 * [onUpdate] with null cancels; [onPlace] places the call.
 */
@Composable
fun CallQuestions(
    p: PendingCall,
    sims: List<SimAccount>,
    countryIso: String,
    planSummary: @Composable (simId: String) -> String? = { null },
    onUpdate: (PendingCall?) -> Unit,
    onPlace: (number: String, simId: String?, remember: Boolean, confirmed: Boolean) -> Unit,
) {
    // Kept for the SIM dialog even when the guard sheet was answered first.
    var remember by remember(p.number) { mutableStateOf(false) }
    val shownNumber = app.parley.ui.Bidi.ltr(Format.number(p.number, countryIso))
    val who = p.name?.let { stringResource(R.string.call_who_with_number, it, shownNumber) } ?: shownNumber

    if (p.warnings.isNotEmpty()) {
        // The sheet is the confirmation: it also shows the allowance note, so "Call" means yes to both.
        DialGuardSheet(who, p.warnings, note = p.note, onCall = {
            if (p.chooseSim) onUpdate(p.copy(warnings = emptyList(), needConfirm = false)) else onPlace(p.number, p.simId, false, true)
        }, onCancel = { onUpdate(null) })
        return
    }

    if (p.chooseSim) {
        AlertDialog(
            onDismissRequest = { onUpdate(null) },
            title = { Text(stringResource(R.string.call_who_with, who)) },
            text = {
                Column {
                    sims.forEach { sim ->
                        val plan = planSummary(sim.id)
                        ListItem(
                            headlineContent = { Text(sim.label) },
                            supportingContent = listOfNotNull(sim.subtitle, plan).joinToString("\n").ifEmpty { null }?.let { { Text(it) } },
                            leadingContent = { Icon(Icons.Rounded.SimCard, null, tint = if (sim.color != 0) Color(sim.color) else Color.Unspecified) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPlace(p.number, sim.id, remember, false) },
                        )
                    }
                    Row(Modifier.fillMaxWidth().clickable { remember = !remember }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(remember, { remember = it })
                        Text(stringResource(R.string.call_remember_sim))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ onUpdate(null) }) { Text(stringResource(R.string.main_cancel)) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = { onUpdate(null) },
            title = { Text(stringResource(R.string.call_who_question, who)) },
            text = p.note?.let { { Text(stringResource(R.string.call_note_anyway, it)) } },
            confirmButton = { TextButton({ onPlace(p.number, p.simId, false, true) }) { Text(stringResource(if (p.note != null) R.string.call_anyway else R.string.main_call)) } },
            dismissButton = { TextButton({ onUpdate(null) }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
}
