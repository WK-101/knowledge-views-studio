package app.parley.ui.people

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.common.people.SecondLineMode
import app.parley.data.AccountRef
import app.parley.ui.contact.Section
import app.parley.ui.settings.LinkRow
import app.parley.ui.settings.SwitchRow
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R

/** Settings › Appearance: second line under names. */
@Composable
fun SecondLineRow(vm: AppViewModel, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val s by vm.people.settings.collectAsStateWithLifecycle()
    app.parley.ui.settings.MenuRow(
        app.parley.common.SettingsCatalog["second_line"].title,
        SecondLineMode.entries.map { m ->
            stringResource(
                when (m) {
                    SecondLineMode.NUMBER -> R.string.second_number
                    SecondLineMode.COMPANY_TITLE -> R.string.second_company
                    SecondLineMode.NICKNAME -> R.string.second_nickname
                    SecondLineMode.ACCOUNT -> R.string.second_account
                    SecondLineMode.NONE -> R.string.second_none
                },
            )
        },
        s.secondLine.ordinal, icon,
        sub = stringResource(R.string.second_line_summary),
    ) { i -> vm.people.update { it.copy(secondLine = SecondLineMode.entries[i]) } }
}

/** Settings › Appearance: prefer nicknames. */
@Composable
fun PreferNicknameRow(vm: AppViewModel, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val s by vm.people.settings.collectAsStateWithLifecycle()
    SwitchRow(app.parley.common.SettingsCatalog["prefer_nickname"].title, stringResource(R.string.prefer_nickname_summary), s.preferNickname, icon) { v ->
        vm.people.update { it.copy(preferNickname = v) }
    }
}

/** Settings › Contacts: labels. */
@Composable
fun LabelsRow(vm: AppViewModel, open: (String) -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val idx by vm.people.index.collectAsStateWithLifecycle()
    LinkRow(app.parley.common.SettingsCatalog["labels"].title, pluralStringResource(R.plurals.labels_row_summary, idx.labelCounts.size, idx.labelCounts.size), icon) { open(PeopleRoutes.LABELS) }
}

/** Whether "Export one account" applies (more than one account has contacts). */
@Composable
fun hasSeveralAccounts(vm: AppViewModel): Boolean = vm.people.index.collectAsStateWithLifecycle().value.accountCounts.size > 1

/** Settings › Contacts: export the contacts of one account (with per-account counts). */
@Composable
fun ExportAccountRow(vm: AppViewModel, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val idx by vm.people.index.collectAsStateWithLifecycle()
    var chooseAccount by remember { mutableStateOf(false) }
    var exportAccount by remember { mutableStateOf<AccountRef?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        val a = exportAccount
        if (uri != null && a != null) scope.launch {
            val ids = vm.contacts.value.orEmpty().filter { a.displayLabel in idx.extras[it.id]?.accounts.orEmpty() }.map { it.id }
            val r = vm.c.vcards.exportIds(uri, ids)
            val res = context.resources
            val done = res.getQuantityString(R.plurals.export_account_done, r.exported, r.exported, a.displayLabel)
            vm.toast(if (r.failures.isEmpty()) done else res.getQuantityString(R.plurals.export_account_failed, r.failures.size, done, r.failures.size))
        }
    }
    LinkRow(app.parley.common.SettingsCatalog["export_account"].title, idx.accountCounts.entries.joinToString(" · ") { context.getString(R.string.ppl_account_count, it.key.displayLabel, it.value) }, icon) { chooseAccount = true }
    if (chooseAccount) {
        AlertDialog(
            onDismissRequest = { chooseAccount = false },
            title = { Text(stringResource(R.string.export_account_title)) },
            text = {
                Column {
                    idx.accountCounts.entries.sortedByDescending { it.value }.forEach { (a, n) ->
                        ListItem(headlineContent = { Text(a.displayLabel) }, supportingContent = { Text(pluralStringResource(R.plurals.lbl_n_contacts, n, n)) }, modifier = Modifier.clickable {
                            chooseAccount = false
                            exportAccount = a
                            exporter.launch("contacts-" + (a.name ?: "phone").replace(Regex("[^A-Za-z0-9._-]"), "_") + ".vcf")
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ chooseAccount = false }) { Text(stringResource(R.string.dc_cancel)) } },
        )
    }
}

/** Privacy dashboard additions. */
@Composable
fun PrivacyLinks(vm: AppViewModel) {
    val pn by vm.c.people.privateNames.state.collectAsStateWithLifecycle()
    Section(stringResource(R.string.privacy_section))
    LinkRow(stringResource(R.string.privacy_who_can_see), stringResource(R.string.privacy_who_can_see_summary)) {
        vm.navigate(NavEvent.Route(PeopleRoutes.WHO_CAN_SEE))
    }
    LinkRow(stringResource(R.string.privacy_private_names), if (pn.enabled) stringResource(R.string.dc_on) else stringResource(R.string.dc_off)) { vm.navigate(NavEvent.Route(PeopleRoutes.PRIVATE_NAMES)) }
}

/** "Google · me@x (212)": account label with its number of contacts. */
fun AppViewModel.accountLabel(a: AccountRef): String {
    val idx = people.index.value
    return if (idx.loaded) idx.labelWithCount(a) else a.displayLabel
}
