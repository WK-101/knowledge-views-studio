package app.parley.ui.people

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.parley.AppViewModel
import app.parley.ui.common.Intents
import app.parley.ui.settings.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import app.parley.R

/** Settings › About › "Export diagnostics": preview the report, then save it or share it yourself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var mask by remember { mutableStateOf(true) }
    var tables by remember { mutableStateOf(false) }
    val report by produceState("", mask, tables) {
        value = withContext(Dispatchers.IO) {
            val extra = linkedMapOf(
                "defaultPhoneApp" to vm.isDefaultDialer.value.toString(),
                "contacts" to (vm.contacts.value?.size ?: 0).toString(),
                "privateContacts" to vm.c.vault.contacts.value.size.toString(),
                "accountsWithContacts" to vm.people.index.value.accountCounts.size.toString(),
                "labels" to vm.people.index.value.labelCounts.size.toString(),
                "sims" to vm.sims.value.size.toString(),
                "privateNameLookup" to vm.c.people.privateNames.state.value.enabled.toString(),
            )
            vm.c.people.diagnostics.report(vm.settings.value, vm.people.settings.value, extra, mask) +
                // U10: the contacts tables with every value reduced to its shape (always masked).
                if (tables) "\n" + vm.c.people.diagnostics.rawDump() else ""
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(report.toByteArray()) } }.isSuccess }
            vm.toast(res.getString(if (ok) R.string.diag_saved else R.string.diag_save_failed))
        }
    }
    // U7: scroll-linked top-bar tint.
    val barTint = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(barTint.nestedScrollConnection), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.diag_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } }, scrollBehavior = barTint)
    }) { p ->
        Column(Modifier.padding(p)) {
            Text(
                stringResource(R.string.diag_intro),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
            )
            SwitchRow(stringResource(R.string.diag_mask), stringResource(R.string.diag_mask_summary), mask) { mask = it }
            SwitchRow(
                stringResource(R.string.diag_tables),
                stringResource(R.string.diag_tables_summary),
                tables,
            ) { tables = it }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ saver.launch("parley-diagnostics.txt") }, enabled = report.isNotEmpty()) { Text(stringResource(R.string.diag_save_file)) }
                OutlinedButton({ Intents.shareText(context, report) }, enabled = report.isNotEmpty()) { Text(stringResource(R.string.diag_share)) }
            }
            SelectionContainer(Modifier.padding(16.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
