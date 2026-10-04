package app.parley.ui.settings

import app.parley.ui.ParleyListItem
import app.parley.ui.sync.shared.SharedLabelRoutes
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.people.PeopleRoutes
import app.parley.messaging.MessagingRoutes
import androidx.compose.material.icons.rounded.NotificationsActive
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
        SettingsCategory.BLOCKING -> Icons.Rounded.Block
        SettingsCategory.CONTACTS -> Icons.Rounded.People
        SettingsCategory.HISTORY -> Icons.Rounded.History
        SettingsCategory.MESSAGING -> Icons.AutoMirrored.Rounded.Chat
        SettingsCategory.PRIVACY -> Icons.Rounded.Shield
        SettingsCategory.BACKUP -> Icons.Rounded.Backup
        SettingsCategory.NOTIFICATIONS -> Icons.Rounded.Notifications
        SettingsCategory.ABOUT -> Icons.Rounded.Info
    }

/**
 * Categories in groups, so the list reads in chunks rather than as one long pile. Reminders, a page of its own for
 * every reminder Parley sends, sits with Notifications.
 */
private val categoryGroups = listOf(
    listOf(SettingsCategory.APPEARANCE, SettingsCategory.LAYOUT),
    listOf(SettingsCategory.CALLS, SettingsCategory.KEYPAD, SettingsCategory.BLOCKING),
    listOf(SettingsCategory.CONTACTS, SettingsCategory.HISTORY, SettingsCategory.MESSAGING),
    listOf(SettingsCategory.PRIVACY, SettingsCategory.BACKUP, null, SettingsCategory.NOTIFICATIONS),
    listOf(SettingsCategory.ABOUT),
)

/** The root's Reminders row (null in [categoryGroups]). */
private const val REMINDERS_ROW = "reminders"

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
            // Tools, the one hub (everything Parley does, by what you want done), as in every tab's ⋮ menu.
            SegmentedGroup {
                item("tools") {
                    ParleyListItem(
                        modifier = Modifier.clickable { open(DiscoverRoutes.Capabilities) },
                        leadingContent = { TonalIcon(Icons.Rounded.Handyman, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) },
                        headlineContent = { Text(stringResource(R.string.discover_title)) },
                        supportingContent = { Text(stringResource(R.string.discover_summary), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = rowColors(),
                    )
                }
            }
            categoryGroups.forEach { group ->
                SegmentedGroup {
                    group.forEach { c ->
                        if (c == null) item(REMINDERS_ROW) {
                            ParleyListItem(
                                modifier = Modifier.clickable { open(RemindersRoutes.Page()) },
                                leadingContent = {
                                    val cs = MaterialTheme.colorScheme
                                    TonalIcon(Icons.Rounded.NotificationsActive, cs.secondaryContainer, cs.onSecondaryContainer)
                                },
                                headlineContent = { Text(settingTitle("reminders")) },
                                supportingContent = { Text(settingSummary("reminders"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors = rowColors(),
                            )
                        } else item(c.name) {
                            ParleyListItem(
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
    // M7: the same entries in a duress session (the duress PIN's row is there, shown off), so search gives nothing away.
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
                ParleyListItem(
                    modifier = Modifier.clickable { onPick(e) },
                    leadingContent = { Icon(e.category.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    // A folded setting says so: the page opens its Advanced group for it.
                    overlineContent = {
                        Text(if (e.advanced) stringResource(R.string.set_search_in_advanced, e.category.localTitle()) else e.category.localTitle())
                    },
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
        "kept_forever" -> "archive".takeIf { !archiveOn }
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
                SettingsCategory.BLOCKING -> BlockingPage(vm, open)
                SettingsCategory.CONTACTS -> ContactsPage(vm, open)
                SettingsCategory.HISTORY -> HistoryPage(vm, open)
                SettingsCategory.MESSAGING -> MessagingPage(vm)
                SettingsCategory.PRIVACY -> PrivacyPage(vm, open)
                SettingsCategory.BACKUP -> BackupPage(vm, open)
                SettingsCategory.NOTIFICATIONS -> NotificationsPage(vm)
                SettingsCategory.ABOUT -> AboutPage(open, vm)
            }
        }
    }
}

@Composable
internal fun QuickRepliesDialog(
    current: List<String>,
    nameReply: String,
    onDismiss: () -> Unit,
    /** The quick replies, and the reply for numbers not in your contacts (blank: off). */
    onSave: (List<String>, String) -> Unit,
) {
    val items = remember { mutableStateListOf<String>().apply { addAll(current) } }
    var name by rememberSaveable { mutableStateOf(nameReply) }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_quick_replies_dialog)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.indices.forEach { i -> OutlinedTextField(items[i], { items[i] = it }, singleLine = true) }
                // "Text me your name": offered first to unknown numbers, on its own so it can be turned off.
                OutlinedTextField(
                    name, { name = it }, label = { Text(stringResource(R.string.set_name_reply_label)) },
                    supportingText = { Text(stringResource(R.string.set_name_reply_help)) }, minLines = 2,
                )
            }
        },
        confirmButton = { TextButton({ onSave(items.filter { t -> t.isNotBlank() }, name.trim()) }) { Text(stringResource(R.string.set_save)) } },
        dismissButton = {
            TextButton({ onSave(AppSettings.DEFAULT_QUICK_REPLIES, AppSettings.DEFAULT_NAME_REPLY) }) { Text(stringResource(R.string.set_reset)) }
        },
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
internal fun importSummary(report: ImportReport): String = importSummaryText(LocalResources.current, report)

/** [importSummary] outside composition (the end of an import job). */
internal fun importSummaryText(res: android.content.res.Resources, report: ImportReport): String = buildList {
    add(res.getString(R.string.set_import_imported_of, report.imported, report.cardsParsed + report.cardsFailed))
    if (report.skippedDuplicates > 0) add(res.getQuantityString(R.plurals.set_import_duplicates_skipped, report.skippedDuplicates, report.skippedDuplicates))
    if (report.cardsFailed > 0) add(res.getQuantityString(R.plurals.set_import_failed, report.cardsFailed, report.cardsFailed))
    val unmapped = report.unmappedProperties.values.sum()
    if (unmapped > 0) add(res.getQuantityString(R.plurals.set_import_unmapped, unmapped, unmapped))
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
                report.savedInstead?.let { Text(stringResource(R.string.main_new_contacts_saved_instead, it), style = MaterialTheme.typography.bodyMedium) }
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
    SettingPlace.REMINDERS -> RemindersRoutes.Page(e.key)
    // Calls' own pages, scrolled to the setting.
    SettingPlace.CALLS_ANSWERING, SettingPlace.CALLS_DURING, SettingPlace.CALLS_SIMS, SettingPlace.CALLS_SITUATIONS ->
        CallsRoutes.Page(CallsSubPage.at(place)?.name ?: CallsSubPage.ANSWERING.name, e.key)
    // Plan minutes are offered only where they're asked for (Tools, or search).
    SettingPlace.SIMS -> HistoryRoutes.Sims(plans = e.key in PLAN_KEYS)
    else -> placeRoutes.getValue(place)
}

/** The SIM screen's settings about plan minutes. */
private val PLAN_KEYS = setOf("plan_minutes", "sim_billing")

/** The screen each other [SettingPlace] is on (one each, so a new place without a screen fails its test). */
private val placeRoutes: Map<SettingPlace, Destination> by lazy {
    mapOf(
        SettingPlace.BLOCKING to Routes.Blocking,
        SettingPlace.SIMS to HistoryRoutes.Sims(),
        SettingPlace.CONTACT_PAGE to ContactPageRoutes.Sections,
        SettingPlace.SIMPLE_MODE to ExtrasRoutes.SimpleSetup,
        SettingPlace.CALL_TIME to Routes.CallTime,
        SettingPlace.BACKUP to Routes.Backup,
        SettingPlace.SYNC to Routes.Sync,
        SettingPlace.TEMPORARY to Routes.Temporary,
        SettingPlace.HELPERS to FamilyRoutes.Helpers,
        SettingPlace.DRIVE_PROFILE to DriveRoutes.Profile,
        SettingPlace.PHONE_MENUS to CallsRoutes.PhoneMenus,
        SettingPlace.APP_LOCK to AppLockRoutes.UnlockWith,
        SettingPlace.SHARED_LABELS to SharedLabelRoutes.All,
    )
}

/** A tool found by search: the tool itself (Settings holds no launcher row for it), else the Tools hub. */
private fun toolsRoute(key: String): Destination = toolRoutes[key] ?: DiscoverRoutes.Capabilities

/** The screen of each tool Settings search finds (SettingPlace.TOOLS). */
internal val toolRoutes: Map<String, Destination> by lazy {
    mapOf(
        "scan_qr" to QrRoutes.Scan,
        "coming_from" to DiscoverRoutes.ComingFrom,
        "what_parley_can_do" to DiscoverRoutes.Capabilities,
        "dry_run" to BlockingRoutes.DryRun,
        "labels" to PeopleRoutes.Labels,
        "temporary_contacts" to Routes.Temporary,
        "duplicates" to Routes.Duplicates,
        "health" to Routes.Health,
        "bulk_add" to MessagingRoutes.BulkAdd,
        "birthdays" to Routes.Birthdays,
        "insights" to HistoryRoutes.Insights(),
        "messaged_numbers" to MessagingRoutes.Messaged,
        "history_details" to Routes.journal(HistoryTab.CALLS),
        "time_machine" to Routes.journal(HistoryTab.SNAPSHOTS),
    )
}

/** Settings that don't exist on this phone, left out of search. */
private val unavailableHere: Set<String> = buildSet {
    if (Build.VERSION.SDK_INT < 31) add("dynamic_color")
    if (listOf("Xiaomi", "Redmi", "POCO").none { Build.MANUFACTURER.equals(it, true) }) add("xiaomi")
}
