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
                vm.toast("Export failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    suspend fun rows() = ExportFiles.rows(context, calls) { e -> ExportFiles.nameFor(e) { n -> vm.contactFor(n)?.displayName } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Export ${calls.size} call" + if (calls.size == 1) "" else "s",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            "Files are made on this phone and deleted from Parley's cache after sharing. Durations are the real talk time.",
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
        row("CSV", "For spreadsheets; notes included", Icons.Rounded.TableChart) {
            run { ExportFiles.share(context, ExportFiles.write(context, rows(), subject, ExportFormat.CSV), ExportFormat.CSV) }
        }
        row("JSON", "For your own scripts", Icons.Rounded.Code) {
            run { ExportFiles.share(context, ExportFiles.write(context, rows(), subject, ExportFormat.JSON), ExportFormat.JSON) }
        }
        row("Calendar (.ics)", "One event per call", Icons.Rounded.CalendarMonth) {
            run { ExportFiles.share(context, ExportFiles.write(context, rows(), subject, ExportFormat.ICS), ExportFormat.ICS) }
        }
        row("PDF", "A printable table to share", Icons.Rounded.PictureAsPdf) {
            run { ExportFiles.share(context, ExportFiles.write(context, rows(), subject, ExportFormat.PDF), ExportFormat.PDF) }
        }
        row("Print", "Or save as PDF with the system print dialog", Icons.Rounded.Print) {
            run { ExportFiles.print(context, rows(), subject) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
