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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat

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
    val res = androidx.compose.ui.platform.LocalResources.current
    fun ago(t: Long) = if (t <= 0) res.getString(R.string.lists_never) else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null)
                            Text("  " + stringResource(R.string.lists_card_title), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            stringResource(R.string.lists_card_text),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            (state.lastRunError?.let { stringResource(R.string.lists_last_update_error, ago(state.lastRun), it) } ?: stringResource(R.string.lists_last_update, ago(state.lastRun))) +
                                "\n" + pluralStringResource(R.plurals.lists_summary, state.packs.size, state.packs.size, size(repo.totalBytes()), size(repo.cacheBytes())),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button({ UpdateWorker.runNow(context) }, enabled = !state.running) {
                            Icon(Icons.Rounded.Refresh, null)
                            Text(" " + stringResource(R.string.lists_update_now))
                        }
                    }
                }
            }

            item { Header(stringResource(R.string.lists_ready)) }
            if (state.packs.isEmpty()) item { Text(stringResource(R.string.lists_none), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.packs.sortedBy { it.name.lowercase() }, key = { it.id }) { pk ->
                ListItem(
                    headlineContent = { Text(pk.name) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                pluralStringResource(R.plurals.lists_numbers, pk.entries, NumberFormat.getIntegerInstance().format(pk.entries)) +
                                    if (pk.ranges > 0) " · " + pluralStringResource(R.plurals.lists_ranges, pk.ranges, pk.ranges) else "",
                                size(pk.sizeBytes),
                                stringResource(R.string.lists_built, ago(pk.updatedAt)),
                                if (pk.fingerprint != null) stringResource(R.string.lists_signed) else stringResource(R.string.lists_unsigned),
                            ).joinToString(" · ") + (pk.licence.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""),
                        )
                    },
                )
            }

            item { Header(stringResource(R.string.lists_sources)) }
            item {
                val st = state.status[Updater.FTC_KEY]
                ListItem(
                    headlineContent = { Text(stringResource(R.string.lists_ftc_title)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.lists_ftc_text))
                            SourceLine(st, ::ago, ::size)
                        }
                    },
                    trailingContent = { Switch(cfg.ftcEnabled, { v -> setConfig { it.copy(ftcEnabled = v) } }) },
                )
                if (cfg.ftcEnabled) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.lists_keep_last), style = MaterialTheme.typography.bodySmall)
                        listOf(7, 30, 90).forEach { d -> FilterChip(cfg.ftcDays == d, { setConfig { it.copy(ftcDays = d) } }, label = { Text(pluralStringResource(R.plurals.lists_days, d, d)) }) }
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.lists_arcep_title)) },
                    supportingContent = { Text(stringResource(R.string.lists_arcep_text)) },
                    trailingContent = { Switch(cfg.arcepEnabled, { v -> setConfig { it.copy(arcepEnabled = v) } }) },
                )
            }
            items(cfg.community, key = { "c" + it.url }) { src ->
                ListItem(
                    headlineContent = { Text(state.packs.firstOrNull { it.sourceUrl == src.url && it.origin == "community" }?.name ?: stringResource(R.string.lists_community)) },
                    supportingContent = {
                        Column {
                            Text(src.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            SourceLine(state.status[src.url], ::ago, ::size)
                        }
                    },
                    trailingContent = { IconButton({ setConfig { c -> c.copy(community = c.community.filter { it.url != src.url }) } }) { Icon(Icons.Rounded.Delete, stringResource(R.string.lists_remove)) } },
                )
            }
            item { AddCommunity(cfg) { url -> setConfig { c -> c.copy(community = c.community + CommunitySource(url, System.currentTimeMillis())) } } }

            item { Header(stringResource(R.string.lists_schedule)) }
            item {
                ToggleItem(stringResource(R.string.lists_auto), null, cfg.auto) { v -> setConfig { it.copy(auto = v) } }
                if (cfg.auto) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(12 to R.string.lists_twice_daily, 24 to R.string.lists_daily, 168 to R.string.lists_weekly).forEach { (h, label) ->
                            FilterChip(cfg.intervalHours == h, { setConfig { it.copy(intervalHours = h) } }, label = { Text(stringResource(label)) })
                        }
                    }
                    ToggleItem(stringResource(R.string.lists_unmetered), null, cfg.unmeteredOnly) { v -> setConfig { it.copy(unmeteredOnly = v) } }
                    ToggleItem(stringResource(R.string.lists_idle), stringResource(R.string.lists_idle_summary), cfg.idleOnly) { v -> setConfig { it.copy(idleOnly = v) } }
                    ToggleItem(stringResource(R.string.lists_charging), null, cfg.chargingOnly) { v -> setConfig { it.copy(chargingOnly = v) } }
                }
            }

            item { Header(stringResource(R.string.lists_how)) }
            item {
                Text(
                    stringResource(R.string.lists_how_text),
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
        if (st.downloaded > 0) stringResource(R.string.lists_checked_downloaded, ago(st.lastAttempt), size(st.downloaded)) else stringResource(R.string.lists_checked, ago(st.lastAttempt)),
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
        !trimmed.startsWith("https://", ignoreCase = true) -> stringResource(R.string.lists_only_https)
        cfg.community.any { it.url == trimmed } -> stringResource(R.string.lists_already)
        else -> null
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.lists_add_text), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                url, { url = it }, Modifier.weight(1f), singleLine = true, label = { Text("https://…/list.parleylist") },
                isError = error != null, supportingText = error?.let { e -> { Text(e) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            TextButton({ add(trimmed); url = "" }, enabled = trimmed.isNotEmpty() && error == null) { Text(stringResource(R.string.lists_add)) }
        }
    }
}
