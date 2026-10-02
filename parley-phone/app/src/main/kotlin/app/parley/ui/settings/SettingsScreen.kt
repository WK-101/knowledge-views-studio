package app.parley.ui.settings

import app.parley.ui.Destination
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalResources
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
import app.parley.common.SettingPlace
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Handyman
import app.parley.common.SettingsCategory
import app.parley.common.SettingsSearch
import app.parley.common.circle.ReminderDelivery
import app.parley.common.vcard.ImportReport
import app.parley.data.VCardIO
import app.parley.ui.EmptyState
import app.parley.ui.LocalHighlightKey
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.backup.BackupReminderBanner
import app.parley.ui.calls.rememberDialerRoleRequest
import app.parley.ui.common.unmappedLabel
import app.parley.ui.contact.ContactPageRoutes
import app.parley.ui.discover.DiscoverRoutes
import app.parley.ui.extras.ExtrasRoutes
import app.parley.ui.family.FamilyRoutes
import app.parley.ui.drive.DriveRoutes
import app.parley.ui.history.HistoryRoutes
import app.parley.ui.journal.HistoryTab
import app.parley.ui.people.hasSeveralAccounts
import app.parley.ui.qr.QrRoutes
import app.parley.ui.segmentShape
import app.parley.ui.SettingsScaffold
import app.parley.ui.TonalIcon
import app.parley.ui.rowColors
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.BackButton
import app.parley.ui.ParleyDialog

val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
        SettingsCategory.LAYOUT -> Icons.Rounded.Dashboard
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
    listOf(SettingsCategory.APPEARANCE, SettingsCategory.LAYOUT),
    listOf(SettingsCategory.CALLS, SettingsCategory.KEYPAD, SettingsCategory.CALL_TIME, SettingsCategory.BLOCKING),
    listOf(SettingsCategory.CONTACTS, SettingsCategory.HISTORY, SettingsCategory.MESSAGING),
    listOf(SettingsCategory.PRIVACY, SettingsCategory.BACKUP, SettingsCategory.NOTIFICATIONS),
    listOf(SettingsCategory.ABOUT),
)

/** Settings: categories with a one-line summary each, and a search over every setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // The role request, with the by-hand guide when Android refuses without asking.
    val requestRole = rememberDialerRoleRequest { vm.refreshEnvironment() }
    BackHandler(searching) { searching = false; query = "" }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    ParleyScaffold(
        modifier = if (searching) Modifier else Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AnimatedContent(searching, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "settings-search") { s ->
                if (s) {
                    SettingsSearchBar(query, { query = it }) { searching = false; query = "" }
                } else {
                    ParleyTopBar(
                        stringResource(R.string.set_settings),
                        onBack = back,
                        actions = { IconButton({ searching = true }) { Icon(Icons.Rounded.Search, stringResource(R.string.set_search_settings)) } },
                        scrollBehavior = scroll,
                        large = true,
                    )
                }
            }
        },
    ) { p ->
        if (searching) {
            SearchResults(query, Modifier.padding(p), onClear = { query = "" }) { e -> open(settingRoute(e)) }
            return@ParleyScaffold
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
            // A quiet reminder once a backup is overdue (Not now snoozes it for a week).
            BackupReminderBanner(vm, Modifier.padding(vertical = 0.dp))
            // Tools (birthdays, blocking, backups, History & undo…) are also here, not only in the tabs' ⋮ menus.
            SegmentedGroup {
                item("tools") {
                    ListItem(
                        modifier = Modifier.clickable { open(Routes.Tools) },
                        leadingContent = { TonalIcon(Icons.Rounded.Handyman, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) },
                        headlineContent = { Text(stringResource(R.string.set_tools_title)) },
                        supportingContent = { Text(stringResource(R.string.set_tools_summary), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = rowColors(),
                    )
                }
                // P8: everything Parley does, by what you want done.
                item("what_parley_can_do") {
                    ListItem(
                        modifier = Modifier.clickable { open(DiscoverRoutes.Capabilities) },
                        leadingContent = {
                            TonalIcon(Icons.Rounded.Explore, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                        },
                        headlineContent = { Text(stringResource(R.string.discover_title)) },
                        supportingContent = { Text(stringResource(R.string.discover_summary), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = rowColors(),
                    )
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
    ParleyTopBar(
        navigationIcon = { BackButton(onClose, stringResource(R.string.set_close_search)) },
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
private fun SearchResults(query: String, modifier: Modifier, onClear: () -> Unit, onPick: (SettingEntry) -> Unit) {
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
        // No match: clear the search and start again.
        EmptyState(
            Icons.AutoMirrored.Rounded.ManageSearch, stringResource(R.string.set_search_no_match, query), modifier = modifier,
            action = stringResource(R.string.ux_empty_clear_search), onAction = onClear,
        )
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        items(results, key = { it.key }) { e ->
            val i = results.indexOf(e)
            Surface(
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
fun SettingsPageScreen(vm: AppViewModel, category: SettingsCategory, focus: String?, back: () -> Unit, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val severalAccounts = hasSeveralAccounts(vm)
    val archiveOn = vm.c.history.prefs.state.collectAsStateWithLifecycle().value.archiveEnabled
    val circle by vm.c.circle.config.collectAsStateWithLifecycle()
    // A setting found by search that is shown only when another one is on: point at that one instead, and say why.
    val controller = when (focus) {
        "lock_after" -> "app_lock".takeIf { !s.appLock }
        "reminder_time", "date_lead" -> "birthday_reminders".takeIf { !s.birthdayReminders }
        "kept_forever" -> "archive".takeIf { !archiveOn }
        "circle_delivery" -> "nudges".takeIf { !s.reachOutNudges }
        "circle_weekly_cap" -> when {
            !s.reachOutNudges -> "nudges"
            circle.delivery != ReminderDelivery.AS_DUE -> "circle_delivery"
            else -> null
        }
        "first_mover" -> "people_card".takeIf { !circle.peopleCard }
        else -> null
    }
    val shown = controller ?: when {
        focus == "export_account" && !severalAccounts -> "export_vcf"
        // One row for SIMs and their plan minutes (search finds it by either name).
        focus == "plan_minutes" -> "sims"
        else -> focus
    }
    CompositionLocalProvider(LocalHighlightKey provides shown) {
        SettingsScaffold(category.localTitle(), back) {
            if (focus != null && controller != null) {
                Text(
                    stringResource(R.string.set_search_shown_after, settingTitle(focus), settingTitle(controller)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            when (category) {
                SettingsCategory.APPEARANCE -> AppearancePage(vm, open)
                SettingsCategory.LAYOUT -> LayoutPage(vm, open)
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
    ParleyDialog(
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

internal fun exportMessage(context: Context, r: VCardIO.ExportResult): String {
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
    val res = LocalResources.current
    ParleyDialog(
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
                    Text(report.unmappedProperties.entries.joinToString("\n") { unmappedLabel(res, it.key) + " × ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_ok)) } },
    )
}

private const val MAX_REPORT_ITEMS = 50

/** Where Settings search takes you for [e]: its category page scrolled to it, or the screen it lives on. */
internal fun settingRoute(e: SettingEntry): Destination = when (val place = e.place) {
    null -> Routes.settingsPage(e.category, e.key)
    SettingPlace.TOOLS -> toolsRoute(e.key)
    SettingPlace.DELETED_CALLS -> Routes.journal(HistoryTab.CALLS)
    else -> placeRoutes.getValue(place)
}

/** The screen each other [SettingPlace] is on (one each, so a new place without a screen fails its test). */
private val placeRoutes: Map<SettingPlace, Destination> by lazy {
    mapOf(
        SettingPlace.BLOCKING to Routes.Blocking,
        SettingPlace.SIMS to HistoryRoutes.Sims,
        SettingPlace.CONTACT_PAGE to ContactPageRoutes.Sections,
        SettingPlace.SIMPLE_MODE to ExtrasRoutes.SimpleSetup,
        SettingPlace.CALL_TIME to Routes.CallTime,
        SettingPlace.BACKUP to Routes.Backup,
        SettingPlace.SYNC to Routes.Sync,
        SettingPlace.TEMPORARY to Routes.Temporary,
        SettingPlace.HELPERS to FamilyRoutes.Helpers,
        SettingPlace.DRIVE_PROFILE to DriveRoutes.Profile,
        SettingPlace.PHONE_MENUS to CallsRoutes.PhoneMenus,
    )
}

/** A Tools entry found by search: the screen itself when it has one, else Tools. */
private fun toolsRoute(key: String): Destination = when (key) {
    "scan_qr" -> QrRoutes.Scan
    "coming_from" -> DiscoverRoutes.ComingFrom
    "what_parley_can_do" -> DiscoverRoutes.Capabilities
    else -> Routes.Tools
}

/** Settings that don't exist on this phone, left out of search. */
private val unavailableHere: Set<String> = buildSet {
    if (Build.VERSION.SDK_INT < 31) add("dynamic_color")
    if (listOf("Xiaomi", "Redmi", "POCO").none { Build.MANUFACTURER.equals(it, true) }) add("xiaomi")
}
