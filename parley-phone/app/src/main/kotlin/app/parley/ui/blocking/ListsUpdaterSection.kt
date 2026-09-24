package app.parley.ui.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.blocking.ListsUpdaterClient
import app.parley.common.spam.PackOrigin
import app.parley.data.SpamListStore
import kotlinx.coroutines.launch

/**
 * Spam lists › "Get automatic updates (optional app)" (B4c). Parley never touches the internet: the optional
 * Parley Lists app downloads public lists, and Parley copies and verifies them through a protected link.
 */
@Composable
fun ListsUpdaterSection(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.c.lists.state.collectAsStateWithLifecycle()
    var refreshKey by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf(false) }
    var readable by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<List<ListsUpdaterClient.RemotePack>?>(null) }
    var subs by remember { mutableStateOf(ListsUpdaterClient.subscriptions(context)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var keyConflict by remember { mutableStateOf<Pair<String, String>?>(null) }
    val now = remember(refreshKey) { System.currentTimeMillis() }

    // Re-check when coming back from installing or opening the companion.
    LifecycleResumeEffect(Unit) {
        refreshKey++
        onPauseOrDispose { }
    }
    LaunchedEffect(refreshKey) {
        installed = ListsUpdaterClient.isInstalled(context)
        readable = installed && ListsUpdaterClient.canRead(context)
        remote = if (readable) ListsUpdaterClient.available(context) else null
        subs = ListsUpdaterClient.subscriptions(context)
    }

    fun report(id: String, r: SpamListStore.InstallResult) {
        when (r) {
            is SpamListStore.InstallResult.Installed -> vm.toast(if (r.replaced) "Updated ${r.pack.name}" else "Added ${r.pack.name}")
            is SpamListStore.InstallResult.Older -> vm.toast("You already have a newer version")
            is SpamListStore.InstallResult.Failed -> if ("different key" in r.reason) keyConflict = id to r.reason else vm.toast(r.reason)
        }
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDownload, null)
                Text("  Get automatic updates (optional app)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (installed) IconButton({ refreshKey++ }) { Icon(Icons.Rounded.Refresh, "Check again") }
            }
            Text(
                "Parley never touches the internet. The optional Parley Lists app downloads public lists (US FTC reported calls, " +
                    "regulator ranges, community lists) and never sees your contacts or calls. Parley copies the lists from it, " +
                    "checks their signatures, and refreshes them daily.",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                !installed -> {
                    Text("Not installed. Get it from ${ListsUpdaterClient.WHERE_TO_GET}.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text("It asks only for internet access. Parley reads its lists only when both apps come from the same developer.", style = MaterialTheme.typography.bodySmall)
                }
                !readable -> Text(
                    "Parley Lists is installed, but it's signed by a different developer than this copy of Parley, so Parley can't read it. " +
                        "Install both apps from the same source.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    OutlinedButton({ ListsUpdaterClient.launchIntent(context)?.let { context.startActivity(it) } }) { Text("Open Parley Lists") }
                    val r = remote
                    if (r.isNullOrEmpty()) Text("No lists there yet. Open Parley Lists and tap Update now.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    val r = remote
    if (readable && !r.isNullOrEmpty()) {
        r.forEach { pk ->
            val on = pk.id in subs
            val local = state.packs.firstOrNull { it.id == pk.id }
            val err = if (on) ListsUpdaterClient.lastError(context, pk.id) else null
            ListItem(
                headlineContent = { Text(pk.name) },
                supportingContent = {
                    Column {
                        Text(
                            listOfNotNull(
                                "%,d numbers".format(pk.entries) + if (pk.ranges > 0) " · ${pk.ranges} ranges" else "",
                                "built ${ago(pk.updated, now)}",
                                if (pk.fingerprint != null) "signed" else "unsigned",
                                when {
                                    !on -> null
                                    local?.origin == PackOrigin.UPDATER && local.version >= pk.version -> "up to date"
                                    else -> "update pending"
                                },
                            ).joinToString(" · "),
                        )
                        if (pk.licence.isNotBlank()) Text(pk.licence, style = MaterialTheme.typography.bodySmall)
                        err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                trailingContent = {
                    Switch(on, { v ->
                        scope.launch {
                            busy = pk.id
                            if (v) report(pk.id, ListsUpdaterClient.subscribe(context, vm.c.lists, pk.id)) else ListsUpdaterClient.unsubscribe(context, vm.c.lists, pk.id)
                            subs = ListsUpdaterClient.subscriptions(context)
                            busy = null
                        }
                    }, enabled = busy == null)
                },
            )
        }
    }

    keyConflict?.let { (id, reason) ->
        AlertDialog(
            onDismissRequest = { keyConflict = null },
            title = { Text("Replace the list?") },
            text = { Text("$reason\n\nThis happens when Parley Lists was reinstalled, or you added the same list from another source. Replace only if you trust Parley Lists on this phone.") },
            confirmButton = {
                TextButton({
                    keyConflict = null
                    scope.launch { report(id, ListsUpdaterClient.copy(context, vm.c.lists, id, force = true)) }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton({ keyConflict = null }) { Text("Keep mine") } },
        )
    }
}
