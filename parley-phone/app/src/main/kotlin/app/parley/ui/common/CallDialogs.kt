package app.parley.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.PendingCall
import app.parley.R
import app.parley.common.SimAccount
import app.parley.common.calls.SimChoice
import app.parley.ui.Bidi
import app.parley.ui.history.simPlanSummary
import app.parley.ui.ConfirmDialog

/** Confirm-before-call and the SIM chooser sheet. Shown from the root so every screen can place calls. */
@Composable
fun CallDialogs(vm: AppViewModel) {
    val pending by vm.pendingCall.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val p = pending ?: return
    CallQuestions(
        p, sims, vm.countryIso,
        planSummary = { simPlanSummary(vm, it) },
        onUpdate = { vm.pendingCall.value = it },
        onPlace = { number, simId, remember, confirmed -> vm.place(number, simId, remember, confirmed, subject = p.subject, clir = p.clir) },
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
    val shownNumber = Bidi.ltr(Format.number(p.number, countryIso))
    val who = p.name?.let { stringResource(R.string.format_bracket_pair, it, shownNumber) } ?: shownNumber

    // Abroad, the number with its country code and a local SIM come first: the warnings below are about the
    // number that will actually be dialled (taking the converted number swaps in its own warnings).
    if (p.abroad != null || p.localSim != null) {
        AbroadQuestions(p, onUpdate, onPlace)
        return
    }

    if (p.warnings.isNotEmpty()) {
        // The sheet is the confirmation: it also shows the allowance note, so "Call" means yes to both.
        DialGuardSheet(who, p.warnings, note = p.note, onCall = {
            if (p.chooseSim) onUpdate(p.copy(warnings = emptyList(), needConfirm = false)) else onPlace(p.number, p.simId, false, true)
        }, onCancel = { onUpdate(null) })
        return
    }

    if (p.chooseSim) {
        // The gate's cards (network, number, roaming, suggested); the plain SIM list if it had none to give.
        val cards = p.simCards.ifEmpty { SimChoice.cards(sims, emptyMap(), emptySet(), null) }
        SimChooserSheet(
            title = stringResource(R.string.call_who_with, who),
            cards = cards,
            rememberLabel = p.name?.let { stringResource(R.string.call_remember_sim_for, it) } ?: stringResource(R.string.call_remember_sim),
            remember = remember, onRemember = { remember = it },
            countryIso = countryIso,
            planSummary = planSummary,
            onPick = { simId -> onPlace(p.number, simId, remember, false) },
            onCancel = { onUpdate(null) },
        )
    } else {
        ConfirmDialog(
            title = stringResource(R.string.action_call_named_q, who),
            text = null,
            confirmLabel = stringResource(if (p.note != null) R.string.call_anyway else R.string.action_call),
            onConfirm = { onPlace(p.number, p.simId, false, true) },
            onDismiss = { onUpdate(null) },
            dismissLabel = stringResource(R.string.dc_cancel),
            content = p.note?.let { { Text(stringResource(R.string.call_note_anyway, it)) } },
        )
    }
}
