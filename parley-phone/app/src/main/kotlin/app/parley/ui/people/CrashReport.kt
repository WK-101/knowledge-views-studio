package app.parley.ui.people

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parley.AppViewModel
import app.parley.common.people.Reports
import app.parley.ui.common.Format
import app.parley.ui.settings.SwitchRow
import androidx.compose.ui.res.stringResource
import app.parley.R

/**
 * U10: after a crash (with "Keep crash reports" on), the next start offers the report: send it by e-mail or any
 * app, with numbers and e-mail addresses masked, or dismiss it. Parley sends nothing itself.
 */
@Composable
fun CrashReportHost(vm: AppViewModel) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val store = vm.c.people.crashes
    var crash by remember { mutableStateOf(if (store.enabled.value) store.last() else null) }
    val c = crash ?: return
    val text = remember(c) { Reports.crashText(c, mask = true, formattedTime = Format.fullDate(context, c.time)) }
    fun done() {
        store.clear()
        crash = null
    }
    AlertDialog(
        onDismissRequest = { crash = null },
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
        confirmButton = {
            TextButton({
                val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_SUBJECT, res.getString(R.string.ppl_crash_subject))
                    .putExtra(Intent.EXTRA_TEXT, text)
                val any = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, res.getString(R.string.ppl_crash_subject)).putExtra(Intent.EXTRA_TEXT, text)
                val chooser = Intent.createChooser(any, res.getString(R.string.ppl_crash_send_chooser)).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(mail))
                runCatching { context.startActivity(chooser) }
                done()
            }) { Text(stringResource(R.string.ppl_crash_send)) }
        },
        dismissButton = { TextButton(::done) { Text(stringResource(R.string.ppl_crash_delete)) } },
    )
}

/** Settings › About: "Keep crash reports" (off by default). */
@Composable
fun CrashReportsRow(vm: AppViewModel) {
    val store = vm.c.people.crashes
    var on by remember { mutableStateOf(store.enabled.value) }
    SwitchRow(
        app.parley.common.SettingsCatalog["crash_reports"].title,
        app.parley.common.SettingsCatalog["crash_reports"].summary,
        on,
    ) { v ->
        store.setEnabled(v)
        on = v
    }
}
