package app.parley.ui.people

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.people.SecondLineMode
import app.parley.data.AccountRef
import app.parley.ui.contact.Section
import app.parley.ui.settings.LinkRow
import app.parley.ui.settings.SwitchRow
import kotlinx.coroutines.launch

/** Settings › Appearance additions: second line under names, prefer nickname. */
@Composable
fun PeopleAppearanceRows(vm: AppViewModel) {
    val s by vm.people.settings.collectAsStateWithLifecycle()
    ChoiceRow(
        "Second line under names", SecondLineMode.entries.map { it.title }, s.secondLine.ordinal,
        sub = "People with the same name always show their company or number",
    ) { i -> vm.people.update { it.copy(secondLine = SecondLineMode.entries[i]) } }
    SwitchRow("Prefer nicknames", "Show “Bob” instead of “Robert Jones” in lists when a nickname is saved", s.preferNickname) { v ->
        vm.people.update { it.copy(preferNickname = v) }
    }
}

/** Settings › Contacts additions: labels, SIM import, export of one account (with per-account counts). */
@Composable
fun PeopleContactsRows(vm: AppViewModel, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val idx by vm.people.index.collectAsStateWithLifecycle()
    var chooseAccount by remember { mutableStateOf(false) }
    var exportAccount by remember { mutableStateOf<AccountRef?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        val a = exportAccount
        if (uri != null && a != null) scope.launch {
            val ids = vm.contacts.value.orEmpty().filter { a.displayLabel in idx.extras[it.id]?.accounts.orEmpty() }.map { it.id }
            val r = vm.c.vcards.exportIds(uri, ids)
            vm.toast("Exported ${r.exported} contacts from ${a.displayLabel}" + if (r.failures.isEmpty()) "" else " · ${r.failures.size} failed")
        }
    }
    LinkRow("Labels", "${idx.labelCounts.size} labels · rename, merge, ringtones") { open(PeopleRoutes.LABELS) }
    LinkRow("Import from SIM card", "Copy the SIM's phonebook into your contacts") { open(PeopleRoutes.SIM_IMPORT) }
    if (idx.accountCounts.size > 1) LinkRow("Export one account to .vcf", idx.accountCounts.entries.joinToString(" · ") { "${it.key.displayLabel} (${it.value})" }) { chooseAccount = true }
    if (chooseAccount) {
        AlertDialog(
            onDismissRequest = { chooseAccount = false },
            title = { Text("Export contacts from") },
            text = {
                Column {
                    idx.accountCounts.entries.sortedByDescending { it.value }.forEach { (a, n) ->
                        ListItem(headlineContent = { Text(a.displayLabel) }, supportingContent = { Text("$n contacts") }, modifier = Modifier.clickable {
                            chooseAccount = false
                            exportAccount = a
                            exporter.launch("contacts-" + (a.name ?: "phone").replace(Regex("[^A-Za-z0-9._-]"), "_") + ".vcf")
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ chooseAccount = false }) { Text("Cancel") } },
        )
    }
}

/** Settings › About addition. */
@Composable
fun PeopleAboutRows(open: (String) -> Unit) {
    LinkRow("Export diagnostics", "App version, device and settings, with numbers masked. Nothing is sent: you choose where it goes.") { open(PeopleRoutes.DIAGNOSTICS) }
}

/** Privacy dashboard additions. */
@Composable
fun PrivacyLinks(vm: AppViewModel) {
    val pn by vm.c.people.privateNames.state.collectAsStateWithLifecycle()
    Section("Your contacts and other apps")
    LinkRow("Who can see your contacts", "Which apps can read your contacts, and what Parley can do about it") {
        vm.navigate(NavEvent.Route(PeopleRoutes.WHO_CAN_SEE))
    }
    LinkRow("Let apps show private names", if (pn.enabled) "On" else "Off") { vm.navigate(NavEvent.Route(PeopleRoutes.PRIVATE_NAMES)) }
}

/** "Google · me@x (212)": account label with its number of contacts. */
fun AppViewModel.accountLabel(a: AccountRef): String {
    val idx = people.index.value
    return if (idx.loaded) idx.labelWithCount(a) else a.displayLabel
}

@Composable
private fun ChoiceRow(title: String, options: List<String>, selected: Int, sub: String? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open = true },
        headlineContent = { Text(title) },
        supportingContent = { Text(listOfNotNull(options.getOrElse(selected) { "" }, sub).joinToString(" · ")) },
        trailingContent = {
            DropdownMenu(open, { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, onClick = { open = false; onPick(i) }) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
