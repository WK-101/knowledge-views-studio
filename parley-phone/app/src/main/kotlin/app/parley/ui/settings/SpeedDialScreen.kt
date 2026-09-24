package app.parley.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.AppViewModel
import app.parley.common.TextSearch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedDialScreen(vm: AppViewModel, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entries by vm.c.prefs.speedDials.collectAsStateWithLifecycle(emptyList())
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(settingTitle("speed_dial")) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            items((2..9).toList()) { key ->
                val e = entries.firstOrNull { it.key == key }
                ListItem(
                    modifier = Modifier.clickable { editing = key },
                    leadingContent = { Text("$key") },
                    headlineContent = { Text(e?.label ?: e?.number?.let(::bidiLtr) ?: stringResource(R.string.set_speed_dial_not_set)) },
                    supportingContent = { e?.let { Text(bidiLtr(it.number)) } },
                    trailingContent = { if (e != null) IconButton({ scope.launch { vm.c.prefs.clearSpeedDial(key) } }) { Icon(Icons.Rounded.Delete, stringResource(R.string.set_clear)) } },
                )
            }
        }
    }
    editing?.let { key ->
        var q by remember { mutableStateOf("") }
        val matches = contacts.orEmpty().filter { q.length >= 2 && it.phones.isNotEmpty() && TextSearch.matches(q, it.displayName, it.phones.map { p -> p.number }) }.take(5)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.set_speed_dial_key, key)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    OutlinedTextField(q, { q = it }, label = { Text(stringResource(R.string.set_name_or_number)) }, singleLine = true)
                    matches.forEach { c ->
                        c.phones.forEach { ph ->
                            ListItem(headlineContent = { Text(c.displayName) }, supportingContent = { Text(bidiLtr(ph.number)) }, modifier = Modifier.clickable {
                                scope.launch { vm.c.prefs.setSpeedDial(key, ph.number, c.displayName) }
                                editing = null
                            })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    if (q.isNotBlank()) scope.launch { vm.c.prefs.setSpeedDial(key, q.trim(), null) }
                    editing = null
                }) { Text(stringResource(R.string.set_use_number)) }
            },
            dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}
