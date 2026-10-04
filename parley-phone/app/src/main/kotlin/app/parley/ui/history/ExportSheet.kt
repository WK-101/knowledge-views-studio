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
import app.parley.common.history.ExportFormat
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.jobs.UserErrorText
import app.parley.jobs.UserJobs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun ExportSheet(vm: AppViewModel, calls: List<CallEntry>, subject: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val running by vm.jobs.running.collectAsStateWithLifecycle()
    val busy = running.any { it.kind == UserJobs.Kind.SHARE }
    val preparing = stringResource(R.string.hist_export_preparing)

    // The file is prepared as an app job: closing the sheet or leaving Recents doesn't stop it half way. It is handed
    // to the share sheet (or printed) only when the person taps the job's snackbar or notification: a job running
    // after they left never opens another app over whatever they are doing.
    fun run(print: Boolean = false, write: suspend () -> UserJobs.Opener) {
        if (busy) return
        val ready = res.getString(if (print) R.string.job_file_ready_print else R.string.job_file_ready_share)
        val app = context.applicationContext
        vm.jobs.prepare(UserJobs.Kind.SHARE, preparing, { e -> res.getString(R.string.hist_export_failed, UserErrorText.of(app, e)) }) {
            UserJobs.Ready(ready, write())
        }
        onDismiss()
    }

    suspend fun rows() = ExportFiles.rows(context.applicationContext, calls) { e -> ExportFiles.nameFor(e) { n -> vm.contactFor(n)?.displayName } }

    suspend fun file(format: ExportFormat) = ExportFiles.write(context.applicationContext, rows(), subject, format)

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
        if (busy) LinearProgressIndicator(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        @Composable
        fun row(label: String, sub: String, icon: ImageVector, onClick: () -> Unit) {
            ParleyListItem(
                headlineContent = { Text(label) }, supportingContent = { Text(sub) }, leadingContent = { Icon(icon, null) },
                modifier = Modifier.clickable(enabled = !busy && calls.isNotEmpty(), onClick = onClick),
            )
        }
        row(stringResource(R.string.hist_export_csv), stringResource(R.string.hist_export_csv_summary), Icons.Rounded.TableChart) {
            run { ExportFiles.opener(file(ExportFormat.CSV), ExportFormat.CSV) }
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
        row(stringResource(R.string.hist_export_print), stringResource(R.string.hist_export_print_summary), Icons.Rounded.Print) {
            run(print = true) { ExportFiles.opener(file(ExportFormat.PDF), ExportFormat.PDF, print = true) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
