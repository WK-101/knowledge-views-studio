package app.parley.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.common.CallEntry
import app.parley.common.history.ExportFormat
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

/**
 * "Export…" for the current Recents view or one person: CSV, JSON, calendar (.ics) or PDF to share, or print.
 * [subject] names the file ("Anna"); null for Recents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: AppViewModel, calls: List<CallEntry>, subject: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                onDismiss()
            } catch (e: Exception) {
                vm.toast(context.getString(R.string.hist_export_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    suspend fun rows() = ExportFiles.rows(context, calls) { e -> ExportFiles.nameFor(e) { n -> vm.contactFor(n)?.displayName } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            pluralStringResource(R.plurals.hist_export_count, calls.size, calls.size),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.hist_export_explain),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (busy) LinearProgressIndicator(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        @Composable
        fun row(label: String, sub: String, icon: ImageVector, onClick: () -> Unit) {
            ListItem(
                headlineContent = { Text(label) }, supportingContent = { Text(sub) }, leadingContent = { Icon(icon, null) },
                modifier = Modifier.clickable(enabled = !busy && calls.isNotEmpty(), onClick = onClick),
            )
        }
        row(stringResource(R.string.hist_export_csv), stringResource(R.string.hist_export_csv_summary), Icons.Rounded.TableChart) {
            run { ExportFiles.share(context, ExportFiles.write(context, rows(), subject, ExportFormat.CSV), ExportFormat.CSV) }
        }
        row(stringResource(R.string.hist_export_json), stringResource(R.string.hist_export_json_summary), Icons.Rounded.Code) {
            run { ExportFiles.share(context, ExportFiles.write(context, rows(), subject, ExportFormat.JSON), ExportFormat.JSON) }
        }
        row(stringResource(R.string.hist_export_ics), stringResource(R.string.hist_export_ics_summary), Icons.Rounded.CalendarMonth) {
            run { ExportFiles.share(context, ExportFiles.write(context, rows(), subject, ExportFormat.ICS), ExportFormat.ICS) }
        }
        row(stringResource(R.string.hist_export_pdf), stringResource(R.string.hist_export_pdf_summary), Icons.Rounded.PictureAsPdf) {
            run { ExportFiles.share(context, ExportFiles.write(context, rows(), subject, ExportFormat.PDF), ExportFormat.PDF) }
        }
        row(stringResource(R.string.hist_export_print), stringResource(R.string.hist_export_print_summary), Icons.Rounded.Print) {
            run { ExportFiles.print(context, rows(), subject) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
