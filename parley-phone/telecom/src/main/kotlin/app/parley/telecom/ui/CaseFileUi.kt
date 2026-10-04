package app.parley.telecom.ui

import app.parley.common.catching
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.parley.common.cases.CaseFiles
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.ConfirmDialog
import app.parley.ui.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeping a reference outlives the dialog that started it. */
private val caseScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** A connected call with a number, not an emergency call or a conference. */
private fun caseApplies(call: CallUi): Boolean =
    !call.hidden && !call.isEmergency && !call.isConference && !call.number.isNullOrBlank() &&
        (call.state == CallState.ACTIVE || call.state == CallState.HOLDING)

/**
 * Case files, under the keys typed in a call: "Keep as a reference" for the digits just typed (a claim or account
 * reference the menu asked for), into the organisation's case file. Offered only when the number has one, the phone
 * and Parley are unlocked, and a run of digits long enough to be a reference was typed. Nothing is kept unless tapped.
 */
@Composable
internal fun CaseReferenceRow(call: CallUi, typed: String) {
    val applies = caseApplies(call)
    var hasCase by remember(call.id) { mutableStateOf(false) }
    LaunchedEffect(call.id, applies) {
        if (!applies) return@LaunchedEffect
        val number = call.number.orEmpty()
        hasCase = catching { withContext(Dispatchers.IO) { TelecomGraph.dependencies.hasCaseFile(number, call.accountId) } }.getOrDefault(false)
    }
    val locked = rememberKeyguardLocked()
    val suggestion = remember(typed) { CaseFiles.typedReference(typed) }
    var asking by remember(call.id) { mutableStateOf(false) }
    var result by remember(call.id) { mutableStateOf<Boolean?>(null) }
    if (!applies || !hasCase || locked) return
    if (suggestion != null) {
        TextButton(
            onClick = { asking = true },
            modifier = Modifier.widthIn(max = CallButtonSize.panelMaxWidth).heightIn(min = 48.dp).padding(horizontal = Spacing.l),
        ) {
            Icon(Icons.Rounded.Bookmark, null, Modifier.size(18.dp).padding(end = Spacing.xs))
            Text(stringResource(R.string.incall_case_keep_reference))
        }
    }
    result?.let { ok ->
        Text(
            stringResource(if (ok) R.string.incall_case_reference_kept else R.string.incall_case_reference_not_kept),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.l).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (asking) {
        var value by remember { mutableStateOf(suggestion.orEmpty()) }
        ConfirmDialog(
            title = stringResource(R.string.incall_case_keep_reference_title),
            text = stringResource(R.string.incall_case_keep_reference_body),
            confirmLabel = stringResource(R.string.incall_case_keep),
            confirmEnabled = CaseFiles.cleanReference(value) != null,
            onConfirm = {
                asking = false
                val number = call.number.orEmpty()
                val chosen = value
                caseScope.launch {
                    result = catching { TelecomGraph.dependencies.keepCaseReference(number, call.accountId, chosen) }.getOrDefault(false)
                }
            },
            onDismiss = { asking = false },
            content = {
                OutlinedTextField(
                    value = value, onValueChange = { value = it.take(CaseFiles.MAX_REFERENCE) }, singleLine = true,
                    label = { Text(stringResource(R.string.incall_case_reference_field)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                )
            },
        )
    }
}
