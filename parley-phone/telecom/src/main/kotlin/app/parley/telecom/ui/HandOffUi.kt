package app.parley.telecom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.parley.common.calls.CallHandOff
import app.parley.common.calls.VerifyCallBack
import app.parley.telecom.CallManager
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.TelecomGraph
import app.parley.ui.Bidi
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import app.parley.ui.rowColors
import app.parley.ui.systemMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Send to another number" for a ringing call: a field for a name or a number, the saved numbers that match (contacts
 * and private contacts outside discreet mode; none while Parley's app lock is locked) and the typed number itself.
 * A tap sends the call on at once; when the network doesn't do it, the call rings on and a message says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HandOffSheet(call: CallUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    var query by rememberSaveable { mutableStateOf("") }
    val saved by produceState<List<VerifyCallBack.Saved>?>(emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching { TelecomGraph.dependencies.handOffTargets() }.getOrDefault(emptyList()) }
    }
    val matches = remember(saved, query) { saved?.let { CallHandOff.matches(query, it) }.orEmpty() }
    val typed = CallHandOff.target(query)
    val notANumber = stringResource(R.string.handoff_not_a_number)
    val send = { number: String ->
        val problem = { text: String -> systemMessage(app, text, long = true) }
        if (CallManager.deflect(call.id, number, problem)) onDismiss() else systemMessage(app, notANumber)
    }
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.handoff_deflect_title)) {
        Text(
            stringResource(R.string.handoff_deflect_body),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.s),
        )
        OutlinedTextField(
            query, { query = it },
            label = { Text(stringResource(R.string.handoff_field)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        if (typed != null) {
            ParleyListItem(
                headlineContent = { Text(stringResource(R.string.handoff_use_number, Bidi.ltr(typed))) },
                leadingContent = { Icon(Icons.Rounded.Dialpad, null) },
                colors = rowColors(),
                modifier = Modifier.clickable { send(typed) },
            )
        }
        when {
            saved == null -> HandOffNote(stringResource(R.string.handoff_locked))
            matches.isEmpty() && query.isNotBlank() && typed == null -> HandOffNote(stringResource(R.string.handoff_no_matches))
            else -> matches.forEach { s -> HandOffRow(s) { send(s.number) } }
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun HandOffRow(s: VerifyCallBack.Saved, onClick: () -> Unit) {
    val sep = stringResource(R.string.tc_separator)
    ParleyListItem(
        headlineContent = { Text(s.name) },
        supportingContent = { Text(listOfNotNull(s.label?.takeIf { it.isNotBlank() }, Bidi.ltr(s.number)).joinToString(sep)) },
        leadingContent = { Icon(if (s.organisation) Icons.Rounded.Business else Icons.Rounded.Person, null) },
        colors = rowColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun HandOffNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.m),
    )
}
