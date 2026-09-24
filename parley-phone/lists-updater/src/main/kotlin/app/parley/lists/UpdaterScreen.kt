package app.parley.lists

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdaterScreen(repo: ListsRepo) {
    val context = LocalContext.current
    val state by repo.state.collectAsStateWithLifecycle()
    val cfg = state.config
    fun setConfig(f: (UpdaterConfig) -> UpdaterConfig) {
        val s = repo.setConfig(f)
        UpdateWorker.schedule(context, s.config)
        Updater.prune(repo, s.config)
    }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)
    fun ago(t: Long) = if (t <= 0) "never" else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    Scaffold(topBar = { TopAppBar(title = { Text("Parley Lists") }) }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null)
                            Text("  Public lists for Parley", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "This app downloads public spam lists and hands them to Parley. It can't see your contacts, calls or messages, " +
                                "and it never sends a phone number anywhere: every download is the same static file for everyone. " +
                                "Parley itself has no internet access.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Last update: ${ago(state.lastRun)}" + (state.lastRunError?.let { " · $it" } ?: "") +
                                "\n${state.packs.size} ${if (state.packs.size == 1) "list" else "lists"} · ${size(repo.totalBytes())} (+ ${size(repo.cacheBytes())} of cached FTC days)",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button({ UpdateWorker.runNow(context) }, enabled = !state.running) {
                            Icon(Icons.Rounded.Refresh, null)
                            Text(" Update now")
                        }
                    }
                }
            }

            item { Header("Lists ready for Parley") }
            if (state.packs.isEmpty()) item { Text("None yet. Tap Update now.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.packs.sortedBy { it.name.lowercase() }, key = { it.id }) { pk ->
                ListItem(
                    headlineContent = { Text(pk.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                "%,d numbers".format(pk.entries) + if (pk.ranges > 0) " · ${pk.ranges} ranges" else "",
                                size(pk.sizeBytes),
                                "built ${ago(pk.updatedAt)}",
                                if (pk.fingerprint != null) "signed" else "unsigned",
                            ).joinToString(" · ") + (pk.licence.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                        )
                    },
                )
            }

            item { Header("Sources") }
            item {
                val st = state.status[Updater.FTC_KEY]
                ListItem(
                    headlineContent = { Text("US FTC: reported calls") },
                    supportingContent = {
                        Column {
                            Text("Numbers people reported to the FTC's Do Not Call registry. Public data, updated each weekday; not verified by the FTC.")
                            SourceLine(st, ::ago, ::size)
                        }
                    },
                    trailingContent = { Switch(cfg.ftcEnabled, { v -> setConfig { it.copy(ftcEnabled = v) } }) },
                )
                if (cfg.ftcEnabled) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep reports from the last", style = MaterialTheme.typography.bodySmall)
                        listOf(7, 30, 90).forEach { d -> FilterChip(cfg.ftcDays == d, { setConfig { it.copy(ftcDays = d) } }, label = { Text("$d days") }) }
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("France: ARCEP telemarketing ranges") },
                    supportingContent = { Text("The 12 number blocks ARCEP reserved for automated calling platforms. Built in: nothing to download.") },
                    trailingContent = { Switch(cfg.arcepEnabled, { v -> setConfig { it.copy(arcepEnabled = v) } }) },
                )
            }
            items(cfg.community, key = { "c" + it.url }) { src ->
                ListItem(
                    headlineContent = { Text(state.packs.firstOrNull { it.sourceUrl == src.url && it.origin == "community" }?.name ?: "Community list") },
                    supportingContent = {
                        Column {
                            Text(src.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            SourceLine(state.status[src.url], ::ago, ::size)
                        }
                    },
                    trailingContent = { IconButton({ setConfig { c -> c.copy(community = c.community.filter { it.url != src.url }) } }) { Icon(Icons.Rounded.Delete, "Remove") } },
                )
            }
            item { AddCommunity(cfg) { url -> setConfig { c -> c.copy(community = c.community + CommunitySource(url, System.currentTimeMillis())) } } }

            item { Header("Schedule") }
            item {
                ToggleItem("Update automatically", null, cfg.auto) { v -> setConfig { it.copy(auto = v) } }
                if (cfg.auto) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(12 to "Twice a day", 24 to "Daily", 168 to "Weekly").forEach { (h, label) ->
                            FilterChip(cfg.intervalHours == h, { setConfig { it.copy(intervalHours = h) } }, label = { Text(label) })
                        }
                    }
                    ToggleItem("Only on Wi-Fi or other unmetered networks", null, cfg.unmeteredOnly) { v -> setConfig { it.copy(unmeteredOnly = v) } }
                    ToggleItem("Only while the phone is idle", "Android runs the update when you're not using the phone", cfg.idleOnly) { v -> setConfig { it.copy(idleOnly = v) } }
                    ToggleItem("Only while charging", null, cfg.chargingOnly) { v -> setConfig { it.copy(chargingOnly = v) } }
                }
            }

            item { Header("How Parley gets the lists") }
            item {
                Text(
                    "Parley copies lists through a protected link that only apps signed by the same developer can open, checks each file's " +
                        "checksums and signature, and refreshes them once a day. Lists built here are signed with this install's key:",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(repo.fingerprint(), Modifier.padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(text, Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SourceLine(st: SourceStatus?, ago: (Long) -> String, size: (Long) -> String) {
    if (st == null) return
    Text(
        "Checked ${ago(st.lastAttempt)}" + (if (st.downloaded > 0) " · downloaded ${size(st.downloaded)}" else ""),
        style = MaterialTheme.typography.bodySmall,
    )
    st.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ToggleItem(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { s -> { Text(s) } },
        trailingContent = { Switch(value, onChange) },
    )
}

@Composable
private fun AddCommunity(cfg: UpdaterConfig, add: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val trimmed = url.trim()
    val error = when {
        trimmed.isEmpty() -> null
        !trimmed.startsWith("https://", ignoreCase = true) -> "Only https:// links"
        cfg.community.any { it.url == trimmed } -> "Already added"
        else -> null
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Add a community list: a link to a .parleylist file. Its publisher's signature is kept, and Parley checks it.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text("https://…/list.parleylist") },
                isError = error != null, supportingText = error?.let { e -> { Text(e) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            TextButton({ add(trimmed); url = "" }, enabled = trimmed.isNotEmpty() && error == null) { Text("Add") }
        }
    }
}
