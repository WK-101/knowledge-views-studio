package app.parley.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.vcard.CsvFormat
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet

/** The suggested file name for an export in [format]. */
internal fun csvFileName(format: CsvFormat): String = when (format) {
    CsvFormat.PARLEY -> "contacts.csv"
    CsvFormat.GOOGLE -> "contacts-google.csv"
    CsvFormat.OUTLOOK -> "contacts-outlook.csv"
}

/** "Export as .csv": Parley's own columns, or Google Contacts' or Outlook's, so the file imports there unchanged. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CsvExportSheet(onDismiss: () -> Unit, onPick: (CsvFormat) -> Unit) {
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.set_csv_format_title)) {
        listOf(
            Triple(CsvFormat.PARLEY, R.string.set_csv_format_parley, R.string.set_csv_format_parley_summary),
            Triple(CsvFormat.GOOGLE, R.string.set_csv_format_google, R.string.set_csv_format_google_summary),
            Triple(CsvFormat.OUTLOOK, R.string.set_csv_format_outlook, R.string.set_csv_format_outlook_summary),
        ).forEach { (format, title, summary) ->
            ParleyListItem(
                headlineContent = { Text(stringResource(title)) },
                supportingContent = { Text(stringResource(summary)) },
                leadingContent = { Icon(Icons.Rounded.Description, null) },
                modifier = Modifier.clickable { onPick(format) },
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}
