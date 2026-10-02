package app.parley.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.parley.PendingCall
import app.parley.R
import app.parley.common.calls.AssistedDial
import app.parley.ui.Bidi
import app.parley.ui.ParleyDialog
import app.parley.ui.Spacing
import java.util.Locale

/**
 * L6, the first questions of a call abroad (see [CallQuestions]): "Call +44 20 7946 0958?" with Dial as typed, then,
 * once per trip, "Use SIM 2?". Each answer moves the [PendingCall] on to its next question, or places the call when
 * none is left. Answering either also answers "confirm before calling": it already asked about this call.
 */
@Composable
internal fun AbroadQuestions(
    p: PendingCall,
    onUpdate: (PendingCall?) -> Unit,
    onPlace: (number: String, simId: String?, remember: Boolean, confirmed: Boolean) -> Unit,
) {
    fun next(q: PendingCall, confirmed: Boolean = true) {
        val rest = q.copy(needConfirm = q.note != null)
        val asked = listOf(rest.abroad != null, rest.localSim != null, rest.warnings.isNotEmpty(), rest.chooseSim, rest.needConfirm)
        if (asked.none { it }) {
            onPlace(rest.number, rest.simId, false, confirmed)
        } else {
            onUpdate(rest)
        }
    }
    val plan = p.abroad
    val hint = p.localSim
    if (plan != null) {
        AbroadDialog(
            typed = p.number, plan = plan,
            onCall = { next(p.dialling(plan)) },
            onAsTyped = { next(p.copy(abroad = null)) },
            onCancel = { onUpdate(null) },
        )
    } else if (hint != null) {
        // Another SIM: its own allowance and questions are checked again when the call is placed.
        LocalSimDialog(
            hint,
            onUse = { next(p.copy(simId = hint.local.id, localSim = null, chooseSim = false), confirmed = false) },
            onKeep = { next(p.copy(simId = p.simId ?: hint.roaming.id, localSim = null)) },
            onCancel = { onUpdate(null) },
        )
    }
}

/**
 * L5: [plan]'s number taken: the call now goes to it, so the dial guard's warnings are the ones checked for it
 * ([PendingCall.abroadWarnings]), not those of the number as typed.
 */
internal fun PendingCall.dialling(plan: AssistedDial.Plan): PendingCall =
    copy(number = plan.dial, abroad = null, warnings = abroadWarnings, abroadWarnings = emptyList())

@Composable
private fun AbroadDialog(typed: String, plan: AssistedDial.Plan, onCall: () -> Unit, onAsTyped: () -> Unit, onCancel: () -> Unit) {
    val asTypedDesc = stringResource(R.string.abroad_as_typed_desc, Bidi.ltr(typed))
    ParleyDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Public, null) },
        title = { Text(stringResource(R.string.abroad_title, Bidi.ltr(plan.shown))) },
        text = {
            Column {
                Text(stringResource(R.string.abroad_body, countryName(plan.home)), style = MaterialTheme.typography.bodyMedium)
                if (plan.alsoLocal) {
                    Text(
                        stringResource(R.string.abroad_also_local, Bidi.ltr(typed.trim()), countryName(plan.visited)),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.s),
                    )
                }
            }
        },
        confirmButton = { TextButton(onCall) { Text(stringResource(R.string.abroad_call)) } },
        dismissButton = {
            TextButton(onAsTyped, Modifier.semantics { contentDescription = asTypedDesc }) { Text(stringResource(R.string.abroad_as_typed)) }
        },
    )
}

@Composable
private fun LocalSimDialog(hint: AssistedDial.LocalSimHint, onUse: () -> Unit, onKeep: () -> Unit, onCancel: () -> Unit) {
    ParleyDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.SimCard, null) },
        title = { Text(stringResource(R.string.local_sim_title, hint.local.label)) },
        text = { Text(stringResource(R.string.local_sim_body, hint.roaming.label, hint.local.label), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onUse) { Text(stringResource(R.string.local_sim_use, hint.local.label)) } },
        dismissButton = { TextButton(onKeep) { Text(stringResource(R.string.local_sim_keep, hint.roaming.label)) } },
    )
}

/** "United Kingdom" for "GB", in the app's language. */
private fun countryName(iso: String): String =
    runCatching { Locale.Builder().setRegion(iso).build().displayCountry }.getOrNull()?.takeIf { it.isNotBlank() } ?: iso
