package app.parley.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem

// Private contacts have no page of their own any more: they open the same contact page as everyone else
// (ContactDetailScreen, docs/CONTACT_MODEL.md). What is left here is the expiry choice both kinds share.

/** "Delete after…" choice used for temporary contacts and vault entries. */
@Composable
fun ExpiryDialog(onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_expiry_title)) },
        text = {
            Column {
                listOf(
                    1 to R.string.vault_expiry_1_day, 7 to R.string.vault_expiry_1_week, 30 to R.string.vault_expiry_30_days,
                    90 to R.string.vault_expiry_3_months, 365 to R.string.vault_expiry_1_year,
                ).forEach { (d, label) ->
                    ParleyListItem(headlineContent = { Text(stringResource(label)) }, modifier = Modifier.clickable { onPick(d) })
                }
                ParleyListItem(headlineContent = { Text(stringResource(R.string.vault_expiry_never)) }, modifier = Modifier.clickable { onPick(null) })
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}
