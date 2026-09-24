package app.parley.ui.settings

import android.app.role.RoleManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.AppSettings
import app.parley.common.SettingEntry
import app.parley.common.SettingsCategory
import app.parley.common.SettingsSearch
import app.parley.common.vcard.ImportReport
import app.parley.data.VCardIO
import app.parley.ui.EmptyState
import app.parley.ui.LocalHighlightKey
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.segmentShape

val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
        SettingsCategory.CALLS -> Icons.Rounded.Call
        SettingsCategory.KEYPAD -> Icons.Rounded.Dialpad
        SettingsCategory.CALL_TIME -> Icons.Rounded.Timer
        SettingsCategory.BLOCKING -> Icons.Rounded.Block
        SettingsCategory.CONTACTS -> Icons.Rounded.People
        SettingsCategory.HISTORY -> Icons.Rounded.History
        SettingsCategory.MESSAGING -> Icons.AutoMirrored.Rounded.Chat
        SettingsCategory.PRIVACY -> Icons.Rounded.Shield
        SettingsCategory.BACKUP -> Icons.Rounded.Backup
        SettingsCategory.NOTIFICATIONS -> Icons.Rounded.Notifications
        SettingsCategory.ABOUT -> Icons.Rounded.Info
    }

/** Categories in groups, so the list reads in chunks rather than as one long pile. */
private val categoryGroups = listOf(
    listOf(SettingsCategory.APPEARANCE),
    listOf(SettingsCategory.CALLS, SettingsCategory.KEYPAD, SettingsCategory.CALL_TIME, SettingsCategory.BLOCKING),
    listOf(SettingsCategory.CONTACTS, SettingsCategory.HISTORY, SettingsCategory.MESSAGING),
    listOf(SettingsCategory.PRIVACY, SettingsCategory.BACKUP, SettingsCategory.NOTIFICATIONS),
    listOf(SettingsCategory.ABOUT),
)

/** Settings: categories with a one-line summary each, and a search over every setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshEnvironment() }
    BackHandler(searching) { searching = false; query = "" }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = if (searching) Modifier else Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AnimatedContent(searching, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "settings-search") { s ->
                if (s) {
                    SettingsSearchBar(query, { query = it }) { searching = false; query = "" }
                } else {
                    LargeTopAppBar(
                        title = { Text("Settings") },
                        navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                        actions = { IconButton({ searching = true }) { Icon(Icons.Rounded.Search, "Search settings") } },
                        scrollBehavior = scroll,
                    )
                }
            }
        },
    ) { p ->
        if (searching) {
            SearchResults(query, Modifier.padding(p)) { e -> open(Routes.settingsPage(e.category, e.key)) }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isDefault) {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text("Parley is not the default phone app", style = MaterialTheme.typography.titleSmall)
                            Text("Needed to show calls, manage blocking and the call log.", style = MaterialTheme.typography.bodySmall)
                        }
                        FilledTonalButton({
                            context.getSystemService(RoleManager::class.java)?.let { role.launch(it.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
                        }) { Text("Set") }
                    }
                }
            }
            categoryGroups.forEach { group ->
                SegmentedGroup {
                    group.forEach { c ->
                        item(c.name) {
                            ListItem(
                                modifier = Modifier.clickable { open(Routes.settingsPage(c)) },
                                leadingContent = { TonalIcon(c.icon, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) },
                                headlineContent = { Text(c.title) },
                                supportingContent = { Text(c.summary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors = rowColors(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TopAppBar(
        navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search settings") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, "Clear") } },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
    )
}

@Composable
private fun SearchResults(query: String, modifier: Modifier, onPick: (SettingEntry) -> Unit) {
    val results = remember(query) { SettingsSearch.search(query) }
    if (query.isBlank()) {
        EmptyState(Icons.AutoMirrored.Rounded.ManageSearch, "Search all settings", "Try “dark”, “vibration”, “backup” or “spam”.", modifier)
        return
    }
    if (results.isEmpty()) {
        EmptyState(Icons.AutoMirrored.Rounded.ManageSearch, "No settings match “$query”", modifier = modifier)
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        items(results, key = { it.key }) { e ->
            val i = results.indexOf(e)
            androidx.compose.material3.Surface(
                shape = segmentShape(i, results.size),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            ) {
                ListItem(
                    modifier = Modifier.clickable { onPick(e) },
                    leadingContent = { Icon(e.category.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    overlineContent = { Text(e.category.title) },
                    headlineContent = { Text(e.title) },
                    supportingContent = { Text(e.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    colors = rowColors(),
                )
            }
        }
    }
}

/** One category of Settings; [focus] is a setting to scroll to and highlight (from search). */
@Composable
fun SettingsPageScreen(vm: AppViewModel, category: SettingsCategory, focus: String?, back: () -> Unit, open: (String) -> Unit) {
    CompositionLocalProvider(LocalHighlightKey provides focus) {
        SettingsScaffold(category.title, back) {
            when (category) {
                SettingsCategory.APPEARANCE -> AppearancePage(vm)
                SettingsCategory.CALLS -> CallsPage(vm, open)
                SettingsCategory.KEYPAD -> KeypadPage(vm, open)
                SettingsCategory.CALL_TIME -> CallTimePage(vm, open)
                SettingsCategory.BLOCKING -> BlockingPage(vm, open)
                SettingsCategory.CONTACTS -> ContactsPage(vm, open)
                SettingsCategory.HISTORY -> HistoryPage(vm, open)
                SettingsCategory.MESSAGING -> MessagingPage(vm)
                SettingsCategory.PRIVACY -> PrivacyPage(vm, open)
                SettingsCategory.BACKUP -> BackupPage(vm, open)
                SettingsCategory.NOTIFICATIONS -> NotificationsPage(vm)
                SettingsCategory.ABOUT -> AboutPage(open)
            }
        }
    }
}

@Composable
internal fun QuickRepliesDialog(current: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val items = remember { mutableStateListOf<String>().apply { addAll(current) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick replies") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.indices.forEach { i -> OutlinedTextField(items[i], { items[i] = it }, singleLine = true) }
            }
        },
        confirmButton = { TextButton({ onSave(items.filter { t -> t.isNotBlank() }) }) { Text("Save") } },
        dismissButton = { TextButton({ onSave(AppSettings.DEFAULT_QUICK_REPLIES) }) { Text("Reset") } },
    )
}

internal fun exportMessage(r: VCardIO.ExportResult): String =
    "Exported ${r.exported} contacts" + if (r.failures.isEmpty()) "" else " · ${r.failures.size} failed: ${r.failures.first()}"

/** What an import did: counts, then every failed card with its reason, then fields that had no place. */
@Composable
internal fun ImportReportDialog(report: ImportReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import finished") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(report.summary(), style = MaterialTheme.typography.bodyLarge)
                if (report.failures.isNotEmpty()) {
                    Text("Not imported", style = MaterialTheme.typography.titleSmall)
                    report.failures.take(MAX_REPORT_ITEMS).forEach { f ->
                        Text(
                            (if (f.index > 0) "Card ${f.index}: " else "") + f.reason + if (f.snippet.isNotEmpty()) "\n" + f.snippet.take(120) else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (report.failures.size > MAX_REPORT_ITEMS) Text("…and ${report.failures.size - MAX_REPORT_ITEMS} more", style = MaterialTheme.typography.bodySmall)
                }
                if (report.unmappedProperties.isNotEmpty()) {
                    Text("Fields with no place in a contact", style = MaterialTheme.typography.titleSmall)
                    Text(report.unmappedProperties.entries.joinToString("\n") { "${it.key} × ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("OK") } },
    )
}

private const val MAX_REPORT_ITEMS = 50
