package app.parley.ui.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.spam.Reputation
import app.parley.telecom.ReputationText
import app.parley.ui.ParleyDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.parley.telecom.R as TR

/**
 * I2: what your own calls say about [number] (null: nothing, a contact, or "Learn from your calls" is off), read off the
 * main thread and again whenever the daily run learns something new.
 */
@Composable
fun rememberReputation(vm: AppViewModel, number: String, isContact: Boolean): Reputation? {
    val version by vm.c.reputation.version.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val learn = settings.screening.learnFromCalls
    val rep by produceState<Reputation?>(null, number, isContact, version, learn) {
        value = if (!learn || isContact || number.isBlank()) null
        else withContext(Dispatchers.IO) { runCatching { vm.c.reputation.lookup(number, vm.countryIso) }.getOrNull() }
    }
    return rep
}

/** Number history: the quiet "Looks like a sales line (your calls)" line, with Why?. Nothing for anyone else. */
@Composable
fun ReputationHistoryLine(vm: AppViewModel, number: String, isContact: Boolean) {
    if (rememberReputation(vm, number, isContact) == null) return
    // The reasons open in the same dialog as from Recents.
    ListItem(
        leadingContent = { Icon(Icons.Rounded.Storefront, null) },
        headlineContent = { Text(stringResource(TR.string.rep_tag)) },
        supportingContent = { Text(stringResource(TR.string.rep_why_footer)) },
        trailingContent = { TextButton({ BlockingDialogs.show(BlockingDialog.Reputation(number)) }) { Text(stringResource(TR.string.rep_why)) } },
    )
}

/** "Why it looks like a sales line": the reasons, from [number]'s own calls or its range's. */
@Composable
internal fun ReputationDialog(vm: AppViewModel, number: String, onDismiss: () -> Unit) {
    val rep = rememberReputation(vm, number, isContact = false)
    val res = LocalResources.current
    val reasons = remember(rep, res) { rep?.let { ReputationText.reasons(res, it) }.orEmpty() }
    ParleyDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_close)) } },
        icon = { Icon(Icons.Rounded.Storefront, null) },
        title = { Text(stringResource(TR.string.rep_why_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reasons.forEach { Text(stringResource(TR.string.rep_reason_bullet, it), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(TR.string.rep_why_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
