package app.parley.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.data.GroupInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top bar shown while contacts are selected: bulk actions. */
@Composable
fun SelectionBar(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val all by vm.people.filtered.collectAsStateWithLifecycle()
    val chosen = all.orEmpty().filter { it.id in selection }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmPrivate by remember { mutableStateOf(false) }
    var labelPicker by remember { mutableStateOf<List<GroupInfo>?>(null) }
    val res = LocalResources.current

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        if (uri != null) scope.launch {
            val n = vm.c.vcards.export(uri, chosen).exported
            vm.toast(res.getQuantityString(R.plurals.sel_exported, n, n))
        }
    }

    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        // Same room for the status bar and cutout as the header it replaces.
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        val insets = androidx.compose.material3.TopAppBarDefaults.windowInsets
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(insets).heightIn(min = 64.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ vm.selection.value = emptySet() }) { Icon(Icons.Rounded.Close, stringResource(R.string.sel_clear)) }
            Text(pluralStringResource(R.plurals.sel_count, selection.size, selection.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ vm.selection.value = all.orEmpty().map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, stringResource(R.string.home_select_all)) }
            val allStarred = chosen.isNotEmpty() && chosen.all { it.starred }
            IconButton({
                scope.launch { chosen.forEach { vm.c.contacts.setStarred(it.id, !allStarred) } }
            }) { Icon(if (allStarred) Icons.Rounded.Star else Icons.Rounded.StarOutline, stringResource(if (allStarred) R.string.sel_unstar else R.string.sel_star)) }
            IconButton({
                val uri = vm.c.contacts.multiVcardUri(chosen.map { it.lookupKey }.filter { it.isNotEmpty() })
                val i = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(i, res.getQuantityString(R.plurals.sel_share_title, chosen.size, chosen.size))) }
            }) { Icon(Icons.Rounded.Share, stringResource(R.string.main_share)) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.main_more_actions)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.sel_add_to_label)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Label, null) }, onClick = {
                        menu = false
                        scope.launch { labelPicker = withContext(Dispatchers.IO) { vm.c.contacts.groups() } }
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.sel_message_all)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, onClick = {
                        menu = false
                        val numbers = chosen.mapNotNull { c -> (c.phones.firstOrNull { it.type == 2 } ?: c.phones.firstOrNull())?.number }
                        if (numbers.isEmpty()) vm.toast(res.getString(R.string.sel_no_numbers))
                        else runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + numbers.joinToString(";")))) }
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.sel_introduce)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) }, onClick = {
                        menu = false
                        // M13: one prefilled chat at a time; you press Send yourself.
                        if (!app.parley.messaging.IntroduceStart.fromContacts(vm, chosen)) vm.toast(res.getString(R.string.sel_no_numbers))
                    })
                    if (chosen.size >= 2) {
                        DropdownMenuItem({ Text(stringResource(R.string.sel_merge)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallMerge, null) }, onClick = {
                            menu = false
                            scope.launch {
                                vm.c.contacts.join(chosen.map { it.id })
                                vm.selection.value = emptySet()
                                vm.toast(res.getQuantityString(R.plurals.sel_merged, chosen.size, chosen.size))
                            }
                        })
                    }
                    app.parley.ui.people.CopyAsTextMenuItem(chosen) { menu = false }
                    DropdownMenuItem({ Text(stringResource(R.string.sel_export_vcf)) }, leadingIcon = { Icon(Icons.Rounded.FileDownload, null) }, onClick = {
                        menu = false
                        exporter.launch("contacts-${chosen.size}.vcf")
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.sel_move_private)) }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; confirmPrivate = true })
                    DropdownMenuItem({ Text(stringResource(R.string.main_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }

    if (confirmPrivate) {
        app.parley.ui.people.MoveToPrivateDialog(vm, chosen.map { it.id }, onDismiss = { confirmPrivate = false }) { vm.selection.value = emptySet() }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.sel_delete_title, chosen.size, chosen.size)) },
            text = { Text(stringResource(R.string.sel_delete_body)) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    vm.deleteContacts(chosen.map { it.id })
                    vm.selection.value = emptySet()
                }) { Text(stringResource(R.string.main_delete)) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
    labelPicker?.let { groups ->
        AlertDialog(
            onDismissRequest = { labelPicker = null },
            title = { Text(stringResource(R.string.sel_add_to_label)) },
            text = {
                Column {
                    if (groups.isEmpty()) Text(stringResource(R.string.sel_no_labels))
                    groups.forEach { g ->
                        ListItem(
                            headlineContent = { Text(g.title) },
                            supportingContent = { Text(g.account.displayLabel) },
                            modifier = Modifier.clickable {
                                labelPicker = null
                                scope.launch {
                                    val skipped = vm.c.contacts.addToGroup(chosen.map { it.id }, g)
                                    vm.toast(if (skipped == 0) res.getString(R.string.sel_added_to, g.title) else res.getQuantityString(R.plurals.sel_added_skipped, skipped, skipped))
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ labelPicker = null }) { Text(stringResource(R.string.main_cancel)) } },
        )
    }
}
