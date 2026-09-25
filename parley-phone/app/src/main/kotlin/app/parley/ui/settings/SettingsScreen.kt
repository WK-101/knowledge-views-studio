package app.parley.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
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
    // P4: the role request, with the by-hand guide when Android refuses without asking.
    val requestRole = app.parley.ui.calls.rememberDialerRoleRequest { vm.refreshEnvironment() }
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
                        title = { Text(stringResource(R.string.set_settings)) },
                        navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } },
                        actions = { IconButton({ searching = true }) { Icon(Icons.Rounded.Search, stringResource(R.string.set_search_settings)) } },
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
                            Text(stringResource(R.string.set_not_default), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.set_not_default_body), style = MaterialTheme.typography.bodySmall)
                        }
                        FilledTonalButton({
                            requestRole()
                        }) { Text(stringResource(R.string.set_set_default)) }
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
                                headlineContent = { Text(c.localTitle()) },
                                supportingContent = { Text(c.localSummary(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
        navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_close_search)) } },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.set_search_settings)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.set_clear)) } },
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
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    // Localised titles, summaries and keywords; English words keep matching (SettingEntry.localized).
    val catalog = remember(locales) { SettingsText.localizedCatalog(context) }
    val results = remember(query, catalog) { SettingsSearch.search(query, catalog).filter { it.key !in unavailableHere } }
    if (query.isBlank()) {
        EmptyState(Icons.AutoMirrored.Rounded.ManageSearch, stringResource(R.string.set_search_empty_title), stringResource(R.string.set_search_empty_body), modifier)
        return
    }
    if (results.isEmpty()) {
        EmptyState(Icons.AutoMirrored.Rounded.ManageSearch, stringResource(R.string.set_search_no_match, query), modifier = modifier)
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
                    overlineContent = { Text(e.category.localTitle()) },
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
    val s by vm.settings.collectAsStateWithLifecycle()
    val severalAccounts = app.parley.ui.people.hasSeveralAccounts(vm)
    // A setting found by search that is shown only when another one is on: point at that one instead.
    val shown = when {
        focus == "lock_after" && !s.appLock -> "app_lock"
        focus == "reminder_time" && !s.birthdayReminders -> "birthday_reminders"
        focus == "export_account" && !severalAccounts -> "export_vcf"
        else -> focus
    }
    CompositionLocalProvider(LocalHighlightKey provides shown) {
        SettingsScaffold(category.localTitle(), back) {
            when (category) {
                SettingsCategory.APPEARANCE -> AppearancePage(vm)
                SettingsCategory.CALLS -> CallsPage(vm, open)
                SettingsCategory.KEYPAD -> KeypadPage(vm, open)
                SettingsCategory.CALL_TIME -> CallTimePage(vm, open)
                SettingsCategory.BLOCKING -> BlockingPage(vm, open)
                SettingsCategory.CONTACTS -> ContactsPage(vm, open)
                SettingsCategory.HISTORY -> HistoryPage(vm, open)
                SettingsCategory.MESSAGING -> MessagingPage(vm, open)
                SettingsCategory.PRIVACY -> PrivacyPage(vm, open)
                SettingsCategory.BACKUP -> BackupPage(vm, open)
                SettingsCategory.NOTIFICATIONS -> NotificationsPage(vm)
                SettingsCategory.ABOUT -> AboutPage(open, vm)
            }
        }
    }
}

@Composable
internal fun QuickRepliesDialog(current: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val items = remember { mutableStateListOf<String>().apply { addAll(current) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_quick_replies_dialog)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.indices.forEach { i -> OutlinedTextField(items[i], { items[i] = it }, singleLine = true) }
            }
        },
        confirmButton = { TextButton({ onSave(items.filter { t -> t.isNotBlank() }) }) { Text(stringResource(R.string.set_save)) } },
        dismissButton = { TextButton({ onSave(AppSettings.DEFAULT_QUICK_REPLIES) }) { Text(stringResource(R.string.set_reset)) } },
    )
}

internal fun exportMessage(context: android.content.Context, r: VCardIO.ExportResult): String {
    val res = context.resources
    val done = res.getQuantityString(R.plurals.set_exported_contacts, r.exported, r.exported)
    return if (r.failures.isEmpty()) done else
        res.getString(R.string.set_joined, done, res.getQuantityString(R.plurals.set_export_failed, r.failures.size, r.failures.size, r.failures.first()))
}

/** [ImportReport.summary] in the current language: "Imported 12 of 14 · 1 duplicate skipped · 1 failed". */
@Composable
internal fun importSummary(report: ImportReport): String = buildList {
    add(stringResource(R.string.set_import_imported_of, report.imported, report.cardsParsed + report.cardsFailed))
    if (report.skippedDuplicates > 0) add(pluralStringResource(R.plurals.set_import_duplicates_skipped, report.skippedDuplicates, report.skippedDuplicates))
    if (report.cardsFailed > 0) add(pluralStringResource(R.plurals.set_import_failed, report.cardsFailed, report.cardsFailed))
    val unmapped = report.unmappedProperties.values.sum()
    if (unmapped > 0) add(pluralStringResource(R.plurals.set_import_unmapped, unmapped, unmapped))
}.joinToString(" · ")

/** What an import did: counts, then every failed card with its reason, then fields that had no place. */
@Composable
internal fun ImportReportDialog(report: ImportReport, onDismiss: () -> Unit) {
    val res = androidx.compose.ui.platform.LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_import_finished)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(importSummary(report), style = MaterialTheme.typography.bodyLarge)
                if (report.failures.isNotEmpty()) {
                    Text(stringResource(R.string.set_not_imported), style = MaterialTheme.typography.titleSmall)
                    report.failures.take(MAX_REPORT_ITEMS).forEach { f ->
                        Text(
                            (if (f.index > 0) stringResource(R.string.set_import_card_prefix, f.index) else "") + f.reason + if (f.snippet.isNotEmpty()) "\n" + f.snippet.take(120) else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (report.failures.size > MAX_REPORT_ITEMS) {
                        val more = report.failures.size - MAX_REPORT_ITEMS
                        Text(pluralStringResource(R.plurals.set_and_more, more, more), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (report.unmappedProperties.isNotEmpty()) {
                    Text(stringResource(R.string.set_unmapped_fields), style = MaterialTheme.typography.titleSmall)
                    Text(report.unmappedProperties.entries.joinToString("\n") { app.parley.ui.common.unmappedLabel(res, it.key) + " × ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_ok)) } },
    )
}

private const val MAX_REPORT_ITEMS = 50

/** Settings that don't exist on this phone, left out of search. */
private val unavailableHere: Set<String> = buildSet {
    if (android.os.Build.VERSION.SDK_INT < 31) add("dynamic_color")
    if (listOf("Xiaomi", "Redmi", "POCO").none { android.os.Build.MANUFACTURER.equals(it, true) }) add("xiaomi")
}
