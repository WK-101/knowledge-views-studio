package app.parley.messaging

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.MessagedRecord
import app.parley.common.NumberText
import app.parley.container
import app.parley.data.PhoneEnv
import app.parley.data.messaging.LastMessaged
import app.parley.ui.EmptyState
import kotlinx.coroutines.launch

/**
 * F13, for the privacy dashboard: "Keep a record of numbers you message" (on by default), how many numbers it holds,
 * and a link to the "Messaged numbers" screen (M10) with per-item delete, clear all and automatic expiry.
 */
@Composable
fun MessagedRecordSection(openList: () -> Unit) {
    val store = LocalContext.current.container.messaging
    val enabled by store.recordEnabled.collectAsStateWithLifecycle()
    val record by store.lastMessaged.collectAsStateWithLifecycle()
    val expiry by store.expiryDays.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ListItem(
        headlineContent = { Text("Keep a record of numbers you message") },
        supportingContent = {
            Text(
                if (enabled) {
                    "${record.size} numbers. Shows “Last messaged via WhatsApp · 2 days ago” on a number's page. Encrypted, on this phone only; " +
                        "never private contacts; " + (if (expiry > 0) "forgotten after $expiry days." else "follows your call-history retention.")
                } else {
                    "Off: Parley doesn't note which numbers you open chats with."
                },
            )
        },
        trailingContent = { Switch(enabled, onCheckedChange = { v -> scope.launch { store.setRecordEnabled(v) } }) },
    )
    ListItem(
        headlineContent = { Text("Messaged numbers") },
        supportingContent = { Text("See, delete or forget them automatically") },
        modifier = Modifier.clickable(onClick = openList),
    )
}

/**
 * M10 "Messaged numbers" (from the privacy dashboard, Settings › Messaging and Recents ⋮): the numbers you opened a
 * chat with through Parley, each with delete, "Clear all", "Don't keep a record" and "Forget after N days".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagedNumbersScreen(vm: AppViewModel, back: () -> Unit) {
    val store = vm.c.messaging
    val enabled by store.recordEnabled.collectAsStateWithLifecycle()
    val record by store.lastMessaged.collectAsStateWithLifecycle()
    val expiry by store.expiryDays.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val region = remember { PhoneEnv.countryIso(context) }
    var confirmClear by remember { mutableStateOf(false) }
    var expiryMenu by remember { mutableStateOf(false) }
    val entries = remember(record) { record.values.sortedByDescending { it.at } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Messaged numbers") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                if (entries.isNotEmpty()) IconButton({ confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "Clear all") }
            },
        )
    }) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p)) {
            item {
                ListItem(
                    headlineContent = { Text("Keep a record of numbers you message") },
                    supportingContent = {
                        Text(
                            if (enabled) "Encrypted, on this phone only. Never private contacts. Used for “Last messaged via…” on a number's page."
                            else "Off: nothing is noted. Turning it off also cleared the list.",
                        )
                    },
                    trailingContent = { Switch(enabled, onCheckedChange = { v -> scope.launch { store.setRecordEnabled(v) } }) },
                    modifier = Modifier.clickable { scope.launch { store.setRecordEnabled(!enabled) } },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Forget messaged numbers after") },
                    supportingContent = {
                        Text(
                            MessagedRecord.expiryLabel(expiry) + if (settings.callLogRetentionDays > 0) {
                                " · call-history retention (${settings.callLogRetentionDays} days) applies too; the shorter wins"
                            } else {
                                ""
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Timer, null) },
                    trailingContent = {
                        DropdownMenu(expiryMenu, { expiryMenu = false }) {
                            MessagedRecord.EXPIRY_CHOICES.forEach { d ->
                                DropdownMenuItem({ Text(MessagedRecord.expiryLabel(d)) }, onClick = {
                                    expiryMenu = false
                                    scope.launch { store.setExpiryDays(d) }
                                })
                            }
                        }
                    },
                    modifier = Modifier.clickable { expiryMenu = true },
                )
                HorizontalDivider()
            }
            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        Icons.AutoMirrored.Rounded.Chat, "No messaged numbers",
                        if (enabled) "Numbers you open a chat with from Parley (WhatsApp, Signal, Telegram, Viber, SMS) show up here." else null,
                    )
                }
            }
            items(entries, key = { it.key }) { e ->
                RecordRow(e, region, onOpen = e.number?.let { n -> { vm.navigate(app.parley.NavEvent.History(n)) } }) {
                    scope.launch { e.number?.let { store.forget(it) } ?: store.forgetKey(e.key) }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all ${entries.size} numbers?") },
            text = { Text("Only Parley's record goes; your chats in the messengers stay.") },
            confirmButton = { TextButton({ confirmClear = false; scope.launch { store.clearAll() } }) { Text("Clear all") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecordRow(e: LastMessaged, region: String, onOpen: (() -> Unit)?, onDelete: () -> Unit) {
    val shown = e.number?.let { n -> NumberText.toE164(n, region)?.let(NumberText::formatInternational) ?: n }
        ?: "…" + e.key.takeLast(4)
    val ago = DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    ListItem(
        headlineContent = { Text(shown, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text("${e.label} · $ago") },
        trailingContent = { IconButton(onDelete) { Icon(Icons.Rounded.Close, "Delete $shown") } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier,
    )
}
