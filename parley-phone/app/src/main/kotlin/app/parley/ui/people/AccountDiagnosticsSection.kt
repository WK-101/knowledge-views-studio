package app.parley.ui.people

import android.content.Intent
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SyncDisabled
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.StartTab
import app.parley.common.people.AccountFindingKind
import app.parley.data.AccountRef
import app.parley.data.people.AccountReport
import app.parley.ui.CallColors
import app.parley.ui.contact.Section
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

/** Health check › "Accounts": what Android reports vs what owns contacts, sync switched off, phone account. */
@Composable
fun AccountDiagnosticsSection(vm: AppViewModel) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<AccountReport?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { report = vm.c.people.accounts.report() }
    val r = report ?: return
    fun label(a: AccountRef?) = a?.displayLabel ?: res.getString(R.string.ppl_phone)
    fun syncSettings() = runCatching {
        context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).putExtra(Settings.EXTRA_AUTHORITIES, arrayOf(ContactsContract.AUTHORITY)))
    }.onFailure { vm.toast(res.getString(R.string.ppl_sync_settings_failed)) }

    Column {
        Section(stringResource(R.string.ppl_accounts))
        r.findings.forEach { f ->
            val a = f.account?.let { AccountRef(it.type, it.name) }
            when (f.kind) {
                AccountFindingKind.MASTER_SYNC_OFF -> Finding(
                    Icons.Rounded.SyncDisabled, stringResource(R.string.ppl_master_sync_off),
                    stringResource(R.string.ppl_master_sync_off_text), stringResource(R.string.ppl_open_sync_settings), ::syncSettings,
                )
                AccountFindingKind.SYNC_OFF -> Finding(
                    Icons.Rounded.SyncDisabled, stringResource(R.string.ppl_sync_off, label(a)),
                    pluralStringResource(R.plurals.ppl_sync_off_text, f.count, f.count), stringResource(R.string.ppl_open_sync_settings), ::syncSettings,
                )
                AccountFindingKind.ORPHANED -> Finding(
                    Icons.Rounded.Warning, pluralStringResource(R.plurals.ppl_orphaned, f.count, f.count, label(a)),
                    stringResource(R.string.ppl_orphaned_text),
                    stringResource(R.string.ppl_show_them),
                ) {
                    vm.people.clearFilter()
                    a?.let { vm.people.setAccount(it.displayLabel) }
                    vm.navigate(NavEvent.Tab(StartTab.CONTACTS))
                }
                AccountFindingKind.NO_CONTACTS -> Finding(
                    Icons.Rounded.Warning, stringResource(R.string.ppl_no_contacts, label(a)),
                    stringResource(R.string.ppl_no_contacts_text),
                    null, null,
                )
                AccountFindingKind.LOCAL_MISSING -> Finding(
                    Icons.Rounded.Warning, stringResource(R.string.ppl_local_missing),
                    stringResource(R.string.ppl_local_missing_text),
                    stringResource(R.string.ppl_fix),
                ) {
                    scope.launch {
                        vm.toast(res.getString(if (vm.c.people.accounts.createLocalAccount()) R.string.ppl_local_ready else R.string.ppl_local_failed))
                        round++
                    }
                }
            }
        }
        if (r.findings.isEmpty()) {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.CheckCircle, null, tint = CallColors.Accept) },
                headlineContent = { Text(stringResource(R.string.ppl_accounts_fine)) },
                supportingContent = { Text(if (r.syncKnown) stringResource(R.string.ppl_accounts_fine_text) else stringResource(R.string.ppl_accounts_unknown)) },
            )
        }
        Text(
            stringResource(R.string.ppl_signed_in, r.signedIn.joinToString { res.getString(R.string.ppl_account_count, it.first.displayLabel, it.second) }.ifEmpty { res.getString(R.string.ppl_signed_in_none) }) +
                "\n" + stringResource(R.string.ppl_holding, r.owning.joinToString { res.getString(R.string.ppl_account_count, it.first.displayLabel, it.second) }.ifEmpty { res.getString(R.string.ppl_holding_none) }),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Finding(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String?, onAction: (() -> Unit)?) {
    ListItem(
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.error) },
        headlineContent = { Text(title) },
        supportingContent = { Text(body) },
        trailingContent = if (action != null && onAction != null) ({ TextButton(onAction) { Text(action) } }) else null,
    )
}
