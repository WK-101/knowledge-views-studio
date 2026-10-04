package app.parley.telecom.ui

import app.parley.common.catching
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
 * and Parley are unlocked, and a run of digits that looks like a reference, not a PIN or a card number, was typed
 * ([CaseFiles.typedReference]). Nothing is kept unless tapped, and the digits are only offered, never filled in.
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
        // Empty and password-style: the typed digits are offered behind a tap, never filled in, shown or learnt by the keyboard.
        var value by remember { mutableStateOf("") }
        var shown by remember { mutableStateOf(false) }
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
                Column {
                    OutlinedTextField(
                        value = value, onValueChange = { value = it.take(CaseFiles.MAX_REFERENCE) }, singleLine = true,
                        label = { Text(stringResource(R.string.incall_case_reference_field)) },
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
                        trailingIcon = {
                            IconButton(onClick = { shown = !shown }) {
                                Icon(
                                    if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    stringResource(if (shown) R.string.incall_case_reference_hide else R.string.incall_case_reference_show),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    )
                    if (suggestion != null && value.isEmpty()) {
                        TextButton(onClick = { value = suggestion }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.incall_case_reference_use_typed, CaseFiles.masked(suggestion)))
                        }
                    }
                }
            },
        )
    }
}
