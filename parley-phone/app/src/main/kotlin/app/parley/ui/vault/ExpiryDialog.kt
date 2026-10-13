package app.parley.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.parley.R
import app.parley.common.people.TemporaryClock
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem

// Private contacts have no page of their own any more: they open the same contact page as everyone else
// (ContactDetailScreen, docs/CONTACT_MODEL.md). What is left here is the expiry choice both kinds share.

/**
 * "Delete after…" choice used for temporary contacts and vault entries: [onPick] gets the days (null: keep) and what
 * they count from. [lastCall] offers "Count from your last call" for the day choices; [case] offers "When the case
 * file is resolved" (a contact whose case file is open).
 */
@Composable
fun ExpiryDialog(onDismiss: () -> Unit, lastCall: Boolean = false, case: Boolean = false, onPick: (Int?, TemporaryClock) -> Unit) {
    var fromLastCall by rememberSaveable { mutableStateOf(false) }
    val clock = if (lastCall && fromLastCall) TemporaryClock.LAST_CALL else TemporaryClock.FIXED
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_expiry_title)) },
        text = {
            Column {
                if (lastCall) {
                    ParleyListItem(
                        headlineContent = { Text(stringResource(R.string.temp_from_last_call)) },
                        supportingContent = { Text(stringResource(R.string.temp_from_last_call_sub)) },
                        trailingContent = { Checkbox(fromLastCall, onCheckedChange = null) },
                        modifier = Modifier.toggleable(fromLastCall, role = Role.Checkbox) { fromLastCall = it },
                    )
                }
                listOf(
                    1 to R.string.vault_expiry_1_day, 7 to R.string.vault_expiry_1_week, 30 to R.string.label_30_days,
                    90 to R.string.vault_expiry_3_months, 365 to R.string.vault_expiry_1_year,
                ).forEach { (d, label) ->
                    ParleyListItem(headlineContent = { Text(stringResource(label)) }, modifier = Modifier.clickable { onPick(d, clock) })
                }
                if (case) {
                    ParleyListItem(
                        headlineContent = { Text(stringResource(R.string.temp_until_case)) },
                        modifier = Modifier.clickable { onPick(0, TemporaryClock.CASE_CLOSED) },
                    )
                }
                ParleyListItem(
                    headlineContent = { Text(stringResource(R.string.vault_expiry_never)) },
                    modifier = Modifier.clickable { onPick(null, TemporaryClock.FIXED) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}
