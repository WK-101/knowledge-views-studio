package app.parley.ui.people

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parley.AppViewModel
import app.parley.common.people.Reports
import app.parley.jobs.UserJobs
import app.parley.ui.common.Format
import app.parley.ui.SwitchRow
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.settings.settingSummary
import app.parley.ui.settings.settingTitle
import app.parley.ui.ParleyDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * At start, one card when Parley stopped unexpectedly since the last run: Android's own record of a crash or ANR
 * (Android 11 and later, nothing stored beforehand), or a crash kept with "Keep crash reports" on. "Save a report"
 * writes it with Save as, from where it can go anywhere; it holds the stack, versions and device model only.
 * Parley sends nothing itself.
 */
@Composable
fun CrashReportHost(vm: AppViewModel) {
    val context = LocalContext.current
    val res = LocalResources.current
    val store = vm.c.people.crashes
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            store.stopSinceLastRun { Format.fullDate(context, it) }
                ?: store.last()?.takeIf { store.enabled.value }?.let { Reports.crashText(it, mask = true, formattedTime = Format.fullDate(context, it.time)) }
        }
    }
    fun done() {
        store.clear()
        report = null
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = report
        if (uri != null && text != null) {
            val app = context.applicationContext
            vm.jobs.start(UserJobs.Kind.EXPORT, res.getString(R.string.set_exporting), { res.getString(R.string.diag_save_failed) }, output = uri.toString()) {
                withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
                res.getString(R.string.ppl_crash_saved)
            }
            done()
        }
    }
    val text = report ?: return
    ParleyDialog(
        onDismissRequest = ::done,
        title = { Text(stringResource(R.string.ppl_crash_title)) },
        text = {
            Column {
                Text(stringResource(R.string.ppl_crash_text))
                Text(
                    text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton({ saver.launch("parley-report.txt") }) { Text(stringResource(R.string.ppl_crash_save)) } },
        dismissButton = { TextButton(::done) { Text(stringResource(R.string.circle_not_now)) } },
    )
}

/** Settings › About: "Keep crash reports" (off by default). */
@Composable
fun CrashReportsRow(vm: AppViewModel) {
    val store = vm.c.people.crashes
    var on by remember { mutableStateOf(store.enabled.value) }
    SwitchRow(
        settingTitle("crash_reports"),
        settingSummary("crash_reports"),
        on,
    ) { v ->
        store.setEnabled(v)
        on = v
    }
}
