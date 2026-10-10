package app.parley.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.AppViewModel
import app.parley.common.TextSearch
import app.parley.ui.ParleyListItem
import kotlinx.coroutines.launch
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedDialScreen(vm: AppViewModel, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entries by vm.c.prefs.speedDials.collectAsStateWithLifecycle(emptyList())
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    ParleyScaffold(topBar = {
        ParleyTopBar(settingTitle("speed_dial"), onBack = back)
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            items((2..9).toList()) { key ->
                val e = entries.firstOrNull { it.key == key }
                ParleyListItem(
                    modifier = Modifier.clickable { editing = key },
                    leadingContent = { Text("$key") }, // l10n-ok: digit
                    headlineContent = { Text(e?.label ?: e?.number?.let(::bidiLtr) ?: stringResource(R.string.label_policy_rhythm_none)) },
                    supportingContent = { e?.let { Text(bidiLtr(it.number)) } },
                    trailingContent = { if (e != null) IconButton({ scope.launch { vm.c.prefs.clearSpeedDial(key) } }) { Icon(Icons.Rounded.Delete, stringResource(R.string.hist_filter_clear)) } },
                )
            }
        }
    }
    editing?.let { key ->
        var q by rememberSaveable { mutableStateOf("") }
        val matches = contacts.orEmpty().filter { q.length >= 2 && it.phones.isNotEmpty() && TextSearch.matches(q, it.displayName, it.phones.map { p -> p.number }) }.take(5)
        ConfirmDialog(
            title = stringResource(R.string.set_speed_dial_key, key),
            text = null,
            confirmLabel = stringResource(R.string.set_use_number),
            onConfirm = {
                if (q.isNotBlank()) scope.launch { vm.c.prefs.setSpeedDial(key, q.trim(), null) }
                editing = null
            },
            onDismiss = { editing = null },
            dismissLabel = stringResource(R.string.dc_cancel),
            content = {
                Column {
                    OutlinedTextField(q, { q = it }, label = { Text(stringResource(R.string.set_name_or_number)) }, singleLine = true)
                    matches.forEach { c ->
                        c.phones.forEach { ph ->
                            ParleyListItem(headlineContent = { Text(c.displayName) }, supportingContent = { Text(bidiLtr(ph.number)) }, modifier = Modifier.clickable {
                                scope.launch { vm.c.prefs.setSpeedDial(key, ph.number, c.displayName) }
                                editing = null
                            })
                        }
                    }
                }
            },
        )
    }
}
