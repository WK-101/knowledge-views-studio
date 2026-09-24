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

/** Health check › "Accounts": what Android reports vs what owns contacts, sync switched off, phone account. */
@Composable
fun AccountDiagnosticsSection(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<AccountReport?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { report = vm.c.people.accounts.report() }
    val r = report ?: return
    fun label(a: AccountRef?) = a?.displayLabel ?: "Phone"
    fun syncSettings() = runCatching {
        context.startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).putExtra(Settings.EXTRA_AUTHORITIES, arrayOf(ContactsContract.AUTHORITY)))
    }.onFailure { vm.toast("Couldn't open Android's account settings") }

    Column {
        Section("Accounts")
        r.findings.forEach { f ->
            val a = f.account?.let { AccountRef(it.type, it.name) }
            when (f.kind) {
                AccountFindingKind.MASTER_SYNC_OFF -> Finding(
                    Icons.Rounded.SyncDisabled, "Automatic sync is off for this phone",
                    "No account uploads or downloads contact changes until it's back on.", "Open sync settings", ::syncSettings,
                )
                AccountFindingKind.SYNC_OFF -> Finding(
                    Icons.Rounded.SyncDisabled, "Contacts sync is off for ${label(a)}",
                    "${f.count} contacts. Changes stay on this phone and don't reach your other devices.", "Open sync settings", ::syncSettings,
                )
                AccountFindingKind.ORPHANED -> Finding(
                    Icons.Rounded.Warning, "${f.count} contacts in ${label(a)}",
                    "That account is no longer signed in on this phone, so nothing keeps these contacts in sync. Move them to another account or export them.",
                    "Show them",
                ) {
                    vm.people.clearFilter()
                    a?.let { vm.people.setAccount(it.displayLabel) }
                    vm.navigate(NavEvent.Tab(StartTab.CONTACTS))
                }
                AccountFindingKind.NO_CONTACTS -> Finding(
                    Icons.Rounded.Warning, "${label(a)} has no contacts",
                    "The account is signed in and can hold contacts, but none are stored there. If you expected some, check that its contacts sync finished.",
                    null, null,
                )
                AccountFindingKind.LOCAL_MISSING -> Finding(
                    Icons.Rounded.Warning, "Phone-only storage isn't set up",
                    "Some apps only offer “Phone” as a place to save contacts once Android has created it. Parley can create it by adding and removing an empty entry.",
                    "Fix",
                ) {
                    scope.launch {
                        vm.toast(if (vm.c.people.accounts.createLocalAccount()) "Phone-only storage is ready" else "Couldn't set it up")
                        round++
                    }
                }
            }
        }
        if (r.findings.isEmpty()) {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.CheckCircle, null, tint = CallColors.Accept) },
                headlineContent = { Text("Accounts look fine") },
                supportingContent = { Text(if (r.syncKnown) "Every signed-in account syncs its contacts" else "Android didn't let Parley check sync settings") },
            )
        }
        Text(
            "Signed in: " + (r.signedIn.joinToString { "${it.first.displayLabel} (${it.second})" }.ifEmpty { "no contacts accounts" }) +
                "\nHolding contacts: " + r.owning.joinToString { "${it.first.displayLabel} (${it.second})" }.ifEmpty { "none" },
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
