package app.parley.ui.cases

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.cases.CaseFile
import app.parley.common.cases.CaseTimeline
import app.parley.common.history.ExportFormat
import app.parley.jobs.UserErrorText
import app.parley.jobs.UserJobs
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing
import app.parley.ui.SwitchRow
import app.parley.ui.history.ExportFiles
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "Export as PDF" for a case file: share it, or print it (where it can be saved as a file). Reference numbers are left
 * out unless "Include reference numbers" is switched on, which asks first. The PDF is made as an app job, like every
 * export, and handed over only when the person taps its snackbar or notification.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseExportSheet(vm: AppViewModel, case: CaseFile, timeline: CaseTimeline?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    // One build at a time: while it runs the rows disable and the bar shows, so a second tap can't start another.
    var building by remember { mutableStateOf(false) }
    var include by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val preparing = stringResource(R.string.case_export_preparing)

    fun run(print: Boolean) {
        if (building) return
        building = true
        val app = context.applicationContext
        val ready = res.getString(if (print) R.string.job_file_ready_print else R.string.job_file_ready_share)
        val withReferences = include
        val job = vm.jobs.prepare(UserJobs.Kind.SHARE, preparing, { e -> res.getString(R.string.case_export_failed, UserErrorText.of(app, e)) }) {
            // The screen's timeline when it is ready, else read here, so one tap always builds (no swallowed first tap).
            val lines = CaseExport.lines(
                vm.c, case, timeline, withReferences, { vm.cases.openReference(it) }, CasePdf.Words(app), vm.countryIso,
                ownerKey = { CaseData.ownerKey(vm, case.numbers) }, calls = { vm.cases.calls(case.private).filterNotNull().first() },
            )
            val file = CasePdf.write(app, case.name, lines)
            UserJobs.Ready(ready, ExportFiles.opener(file, ExportFormat.PDF, print = print))
        }
        // The sheet stays, showing progress, until the file is built; then it closes and the snackbar offers it.
        scope.launch { job.join(); onDismiss() }
    }

    ParleySheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.case_export_title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s).semantics { heading() },
        )
        Text(
            stringResource(R.string.case_export_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        if (building) LinearProgressIndicator(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s))
        if (case.references.isNotEmpty()) {
            SwitchRow(
                stringResource(R.string.case_export_include), stringResource(R.string.case_export_include_summary), include, Icons.Rounded.Bookmark,
                enabled = !building,
            ) { on -> if (on) asking = true else include = false }
        }
        @Composable
        fun row(label: String, sub: String, icon: ImageVector, onClick: () -> Unit) {
            ParleyListItem(
                headlineContent = { Text(label) }, supportingContent = { Text(sub) }, leadingContent = { Icon(icon, null) },
                modifier = Modifier.clickable(enabled = !building, role = Role.Button, onClick = onClick),
            )
        }
        row(stringResource(R.string.case_export_share), stringResource(R.string.case_export_share_summary), Icons.Rounded.PictureAsPdf) { run(print = false) }
        row(stringResource(R.string.case_export_print), stringResource(R.string.case_export_print_summary), Icons.Rounded.Print) { run(print = true) }
        Spacer(Modifier.height(Spacing.xl))
    }
    if (asking) {
        ConfirmDialog(
            title = stringResource(R.string.case_export_include_title), text = stringResource(R.string.case_export_include_body),
            confirmLabel = stringResource(R.string.case_export_include_confirm),
            onConfirm = { asking = false; include = true },
            onDismiss = { asking = false },
        )
    }
}
