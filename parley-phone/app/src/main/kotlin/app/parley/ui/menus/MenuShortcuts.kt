package app.parley.ui.menus

import app.parley.ui.ParleyMenuItem
import app.parley.ui.OverflowMenu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuShortcut
import app.parley.common.catching
import app.parley.shortcuts.Shortcuts
import app.parley.ui.Bidi
import app.parley.ui.ConfirmDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.common.Format
import app.parley.ui.rowColors
import kotlinx.coroutines.launch

/**
 * The menu shortcuts saved for any of [numbers] (a contact's, or the number of a number history), as a "Shortcuts"
 * group. A tap calls the number and sends the digits with their pauses (`number,,2,1,4`, through the usual call path);
 * ⋮ renames one, puts it on the home screen or deletes it. Nothing shows while there are none.
 */
@Composable
fun MenuShortcutsBlock(vm: AppViewModel, numbers: List<String>, who: String, photoUri: String? = null) {
    val store = vm.c.menus
    LaunchedEffect(Unit) { catching { store.load() } }
    val state by store.state.collectAsStateWithLifecycle()
    val list = remember(state, numbers) { MenuMemory.shortcutsFor(state, numbers, vm.countryIso) }
    if (list.isEmpty()) return
    var renaming by remember { mutableStateOf<MenuShortcut?>(null) }
    var deleting by remember { mutableStateOf<MenuShortcut?>(null) }

    SegmentedGroup(title = stringResource(R.string.menus_shortcuts)) {
        list.forEach { sc ->
            item(sc.id) { ShortcutRow(vm, sc, who, photoUri, onRename = { renaming = sc }, onDelete = { deleting = sc }) }
        }
    }
    renaming?.let { sc -> RenameDialog(vm, sc, who, photoUri) { renaming = null } }
    deleting?.let { sc -> DeleteDialog(vm, sc) { deleting = null } }
}

@Composable
private fun ShortcutRow(vm: AppViewModel, sc: MenuShortcut, who: String, photoUri: String?, onRename: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val dial = MenuMemory.dialString(sc.number, sc.steps)
    val summary = stringResource(
        R.string.menus_shortcut_summary, Bidi.ltr(Format.number(sc.number, vm.countryIso)), Bidi.ltr(MenuMemory.label(sc.steps)),
    )
    ParleyListItem(
        headlineContent = { Text(sc.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Rounded.Dialpad, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            OverflowMenu(label = stringResource(R.string.label_more_for, sc.name)) { close ->
                ParleyMenuItem(stringResource(R.string.menus_rename)) { close(); onRename() }
                ParleyMenuItem(stringResource(R.string.detail_add_home)) {
                    close()
                    if (!Shortcuts.pinMenu(context, sc.id, sc.name, dial, who, photoUri)) {
                        vm.toast(res.getString(R.string.menus_home_unavailable))
                    }
                }
                ParleyMenuItem(stringResource(R.string.action_delete), destructive = true) { close(); onDelete() }
            }
        },
        colors = rowColors(),
        modifier = Modifier.fillMaxWidth().clickable(
            role = Role.Button, onClickLabel = stringResource(R.string.action_call_named, sc.name),
        ) { vm.requestCall(dial, who) },
    )
}

@Composable
private fun RenameDialog(vm: AppViewModel, sc: MenuShortcut, who: String, photoUri: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember(sc.id) { mutableStateOf(sc.name) }
    ConfirmDialog(
        title = stringResource(R.string.menus_rename_title),
        text = null,
        confirmLabel = stringResource(R.string.action_save),
        confirmEnabled = MenuMemory.cleanName(name) != null,
        onConfirm = {
            onDone()
            val chosen = name
            vm.viewModelScope.launch {
                vm.c.menus.update { MenuMemory.renameShortcut(it, sc.id, chosen) }
                val dial = MenuMemory.dialString(sc.number, sc.steps)
                MenuMemory.cleanName(chosen)?.let { Shortcuts.renameMenu(context.applicationContext, sc.id, it, dial, who, photoUri) }
            }
        },
        onDismiss = onDone,
        content = {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(MenuMemory.MAX_NAME) }, singleLine = true,
                label = { Text(stringResource(R.string.label_name)) }, modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Composable
private fun DeleteDialog(vm: AppViewModel, sc: MenuShortcut, onDone: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    ConfirmDialog(
        title = stringResource(R.string.menus_delete_title, sc.name),
        text = stringResource(R.string.menus_delete_body),
        confirmLabel = stringResource(R.string.action_delete),
        destructive = true,
        onConfirm = {
            onDone()
            val gone = res.getString(R.string.menus_deleted)
            val app = context.applicationContext
            vm.viewModelScope.launch {
                vm.c.menus.update { MenuMemory.removeShortcut(it, sc.id) }
                Shortcuts.disableMenu(app, sc.id, gone)
                vm.toast(gone)
            }
        },
        onDismiss = onDone,
    )
}
