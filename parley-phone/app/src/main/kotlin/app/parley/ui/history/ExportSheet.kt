package app.parley.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.common.CallEntry
import app.parley.common.history.CallTotals
import app.parley.common.history.ExportFormat
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.jobs.UserErrorText
import app.parley.jobs.UserJobs
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * "Export…" for the current Recents view or one person: CSV, JSON, calendar (.ics) or PDF to share, or print.
 * [subject] names the file ("Anna"); null for Recents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: AppViewModel, calls: List<CallEntry>, subject: String?, coverage: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    // One export at a time: while it builds the rows disable and the bar shows, so a second tap can't start another.
    var building by remember { mutableStateOf(false) }
    val preparing = stringResource(R.string.hist_export_preparing)

    // The file is prepared as an app job: closing the sheet or leaving Recents doesn't stop it half way. It is handed
    // to the share sheet (or printed) only when the person taps the job's snackbar or notification: a job running
    // after they left never opens another app over whatever they are doing. The sheet stays, showing progress, until
    // the file is built on this single tap; then it closes and the snackbar offers it.
    fun run(print: Boolean = false, write: suspend () -> UserJobs.Opener) {
        if (building) return
        building = true
        val ready = res.getString(if (print) R.string.job_file_ready_print else R.string.job_file_ready_share)
        val app = context.applicationContext
        val job = vm.jobs.prepare(UserJobs.Kind.SHARE, preparing, { e -> res.getString(R.string.hist_export_failed, UserErrorText.of(app, e)) }) {
            UserJobs.Ready(ready, write())
        }
        scope.launch { job.join(); onDismiss() }
    }

    suspend fun rows() = ExportFiles.rows(context.applicationContext, calls) { e -> ExportFiles.nameFor(e) { n -> vm.contactFor(n)?.displayName } }

    // "Excel-friendly CSV": a tick box here, not a setting. It starts as last chosen, and choosing remembers it.
    var bom by rememberSaveable { mutableStateOf(vm.c.history.prefs.state.value.csvBom) }

    // The totals line and (from Recents) the filter the export covers. Only the PDF draws them — the data files stay plain.
    val summary = remember(calls) { HistoryText.totals(res, CallTotals.of(calls)) }
    suspend fun file(format: ExportFormat) =
        ExportFiles.write(context.applicationContext, rows(), subject, format, excelBom = bom, summary = summary, coverage = coverage)

    ParleySheet(onDismissRequest = onDismiss) {
        Text(
            pluralStringResource(R.plurals.hist_export_count, calls.size, calls.size),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.hist_export_explain),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (building) LinearProgressIndicator(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        @Composable
        fun row(label: String, sub: String, icon: ImageVector, onClick: () -> Unit) {
            ParleyListItem(
                headlineContent = { Text(label) }, supportingContent = { Text(sub) }, leadingContent = { Icon(icon, null) },
                modifier = Modifier.clickable(enabled = !building && calls.isNotEmpty(), onClick = onClick),
            )
        }
        row(stringResource(R.string.hist_export_csv), stringResource(R.string.hist_export_csv_summary), Icons.Rounded.TableChart) {
            run { ExportFiles.opener(file(ExportFormat.CSV), ExportFormat.CSV) }
        }
        ExcelBomRow(bom) { v ->
            bom = v
            scope.launch { vm.c.history.prefs.setCsvBom(v) }
        }
        row(stringResource(R.string.hist_export_json), stringResource(R.string.hist_export_json_summary), Icons.Rounded.Code) {
            run { ExportFiles.opener(file(ExportFormat.JSON), ExportFormat.JSON) }
        }
        row(stringResource(R.string.hist_export_ics), stringResource(R.string.hist_export_ics_summary), Icons.Rounded.CalendarMonth) {
            run { ExportFiles.opener(file(ExportFormat.ICS), ExportFormat.ICS) }
        }
        row(stringResource(R.string.hist_export_pdf), stringResource(R.string.hist_export_pdf_summary), Icons.Rounded.PictureAsPdf) {
            run { ExportFiles.opener(file(ExportFormat.PDF), ExportFormat.PDF) }
        }
        row(stringResource(R.string.action_print), stringResource(R.string.hist_export_print_summary), Icons.Rounded.Print) {
            run(print = true) { ExportFiles.opener(file(ExportFormat.PDF), ExportFormat.PDF, print = true) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** "Excel-friendly CSV": a tick box under the CSV row (a byte-order mark so Excel reads accents right). */
@Composable
private fun ExcelBomRow(on: Boolean, onChange: (Boolean) -> Unit) {
    ParleyListItem(
        headlineContent = { Text(stringResource(R.string.hist_csv_bom)) },
        supportingContent = { Text(stringResource(R.string.hist_csv_bom_summary)) },
        leadingContent = { Checkbox(on, onCheckedChange = null) },
        modifier = Modifier.toggleable(on, role = Role.Checkbox, onValueChange = onChange),
    )
}
