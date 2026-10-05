package app.parley.ui.settings

import app.parley.common.history.RetentionDefaults
import app.parley.ui.Destination
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storefront
import app.parley.data.calls.ReputationLearner
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.DensityMedium
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.ManageHistory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneLocked
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Quickreply
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SimCardDownload
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.catching
import app.parley.jobs.UserErrorText
import app.parley.jobs.UserJobs
import app.parley.ui.ParleyListItem
import app.parley.ui.common.JobProgress
import app.parley.BuildConfigInfo
import app.parley.blocking.BlockingActions
import app.parley.common.AppSettings
import app.parley.common.HomeLayout
import app.parley.common.ListDensity
import app.parley.common.MessagedRecord
import app.parley.common.ThemeMode
import app.parley.common.calls.RecentsLayout
import app.parley.common.ux.BackupNudge
import app.parley.common.calls.LockScreenCaller
import app.parley.common.ux.RecentsStyle
import app.parley.common.ux.SalesLines
import app.parley.common.vcard.ImportReport
import app.parley.data.AccountRef
import app.parley.data.export.ContactExport
import app.parley.ui.export.SealedImportDialog
import app.parley.messaging.CsvImportRequest
import app.parley.messaging.MessagingInbox
import app.parley.messaging.MessagingRoutes
import app.parley.security.AppLock
import app.parley.ui.CallColors
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.backup.BackupReminderBanner
import app.parley.ui.backup.rememberBackupFirst
import app.parley.ui.blocking.BlockingDialog
import app.parley.ui.blocking.BlockingDialogs
import app.parley.ui.calls.DialerRoleGuide
import app.parley.ui.calls.rememberDialerRoleRequest
import app.parley.ui.calltime.NotificationHealthCard
import app.parley.ui.common.Format
import app.parley.ui.contact.ContactPageRoutes
import app.parley.ui.extras.ExtrasRoutes
import app.parley.ui.history.CallHistoryNotes
import app.parley.ui.history.ClearHistoryRow
import app.parley.ui.history.KeepFullHistoryRow
import app.parley.ui.history.csvBomRow
import app.parley.ui.history.keptForeverRow
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.history.HistoryRoutes
import app.parley.ui.history.recentsLayoutLabels
import app.parley.ui.home.label
import app.parley.ui.home.recentsStyleLabels
import app.parley.ui.people.AvatarStyleSetting
import app.parley.ui.people.CrashReportsRow
import app.parley.ui.people.ExportAccountRow
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.people.PreferNicknameRow
import app.parley.ui.people.SecondLineRow
import app.parley.ui.people.SwipeSettings
import app.parley.ui.people.accountLabel
import app.parley.ui.people.hasSeveralAccounts
import app.parley.ui.startOrSay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.LinkRow
import app.parley.ui.InfoRow
import app.parley.ui.rowColors
import app.parley.ui.SwitchRow
import app.parley.ui.ParleyDialog

/** Saves a settings change. */
@Composable
internal fun rememberSettingsSetter(vm: AppViewModel): ((AppSettings) -> AppSettings) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(vm) { { f -> scope.launch { vm.c.settings.update(f) } } }
}

// ---------------------------------------------------------------- Appearance

@Composable
internal fun AppearancePage(vm: AppViewModel, open: (Destination) -> Unit = {}) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val themes = listOf(stringResource(R.string.set_theme_system), stringResource(R.string.set_theme_light), stringResource(R.string.set_theme_dark))
    val densities = listOf(stringResource(R.string.set_density_comfortable), stringResource(R.string.set_density_compact))
    val sortOptions = listOf(stringResource(R.string.set_sort_first_name), stringResource(R.string.set_sort_last_name))
    val nameOrders = listOf(stringResource(R.string.set_name_order_first), stringResource(R.string.set_name_order_last))
    SegmentedGroup(stringResource(R.string.set_group_theme)) {
        choiceRow("theme", themes, s.themeMode.ordinal, Icons.Rounded.DarkMode) { i -> set { it.copy(themeMode = ThemeMode.entries[i]) } }
        if (Build.VERSION.SDK_INT >= 31) switchRow("dynamic_color", s.dynamicColor, Icons.Rounded.Wallpaper) { v -> set { it.copy(dynamicColor = v) } }
    }
    SegmentedGroup(stringResource(R.string.set_group_names)) {
        menuRow("sort_names", sortOptions, if (s.sortByFirstName) 0 else 1, Icons.Rounded.SortByAlpha) { i -> set { it.copy(sortByFirstName = i == 0) } }
        menuRow("name_order", nameOrders, if (s.showNamesLastFirst) 1 else 0, Icons.Rounded.SwapHoriz) { i -> set { it.copy(showNamesLastFirst = i == 1) } }
    }
    // Every one-time tip shows again.
    val tipsReset = stringResource(R.string.ux_tips_reset_done)
    SegmentedGroup(stringResource(R.string.set_group_tips)) {
        linkRow("reset_tips", Icons.Rounded.Lightbulb) {
            vm.c.ux.resetTips()
            vm.toast(tipsReset)
        }
    }
    AdvancedGroup {
        switchRow("amoled", s.amoledBlack, Icons.Rounded.Contrast) { v -> set { it.copy(amoledBlack = v) } }
        choiceRow("density", densities, s.density.ordinal, Icons.Rounded.DensityMedium) { i -> set { it.copy(density = ListDensity.entries[i]) } }
        item("avatar_style") { AvatarStyleSetting(vm) }
        item("second_line") { SecondLineRow(vm, Icons.AutoMirrored.Rounded.ShortText) }
        item("prefer_nickname") { PreferNicknameRow(vm, Icons.Rounded.Badge) }
    }
}

// ---------------------------------------------------------------- Layout & gestures

@Composable
internal fun LayoutPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val navTabsTitle = settingTitle("nav_tabs")
    val navTabsHelp = stringResource(R.string.set_nav_tabs_help)
    // "Open on" offers the tabs actually in the bar (a combined option can take one out).
    val layout = HomeLayout(s.navTabs, s.surfaces)
    val tabLabels = layout.visible.map { it.label }
    SegmentedGroup(stringResource(R.string.set_group_navigation_bar)) {
        item("nav_tabs") {
            Column {
                ParleyListItem(
                    headlineContent = { Text(navTabsTitle) },
                    supportingContent = { Text(navTabsHelp) },
                    colors = rowColors(),
                )
                NavTabsEditor(s.navTabs, inside = layout.absorbed.associateWith { layout.hostOf(it) }) { next -> set { it.copy(navTabs = next, startTab = next.startTab(it.startTab)) } }
            }
        }
        val visible = layout.visible
        menuRow("start_tab", tabLabels, visible.indexOf(layout.startTab(s.startTab)).coerceAtLeast(0), Icons.Rounded.PhoneAndroid) { i ->
            set { it.copy(startTab = visible[i]) }
        }
    }
    // Simple mode, set up here (for someone else, or for yourself).
    SegmentedGroup {
        linkRow("simple_mode", Icons.Rounded.Accessibility) { open(ExtrasRoutes.SimpleSetup) }
    }
    AdvancedSection {
        // Combine Keypad + Recents and Favourites + Contacts (optional), and the Recents row tap.
        LayoutSettingsGroup(vm)
        SegmentedGroup(stringResource(R.string.set_group_gestures)) {
            item("swipe_actions") { SwipeSettings(vm) }
        }
    }
}

// ---------------------------------------------------------------- Calls

/**
 * Settings › Calls: the default phone app, the four pages the rest is on, and the rows used most. Each page keeps
 * the setting keys it always had, so search and "What Parley can do" open it on the right row.
 */
@Composable
internal fun CallsPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    // The role request, with the by-hand guide when Android refuses without asking.
    val requestRole = rememberDialerRoleRequest { vm.refreshEnvironment() }
    SegmentedGroup {
        item("default_dialer") {
            InfoRow(
                stringResource(if (isDefault) R.string.set_is_default else R.string.set_not_default),
                if (isDefault) null else settingSummary("default_dialer"),
                if (isDefault) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                trailing = if (isDefault) null else ({
                    TextButton({ requestRole() }) { Text(stringResource(R.string.set_set_default)) }
                }),
            )
        }
        // The by-hand guide, for phones that refuse the request without asking.
        if (!isDefault) item("default_dialer_help") {
            var guide by remember { mutableStateOf(false) }
            LinkRow(settingTitle("default_dialer_help"), settingSummary("default_dialer_help"), Icons.AutoMirrored.Rounded.HelpOutline) { guide = true }
            if (guide) DialerRoleGuide { guide = false }
        }
    }
    CallsSubPageLinks(open)
    CallExtrasGroups(vm, open)
    SegmentedGroup(stringResource(R.string.set_group_before_calling)) {
        switchRow("confirm_call", s.confirmBeforeCall, Icons.Rounded.CheckCircle) { v -> set { it.copy(confirmBeforeCall = v) } }
        item("pocket_guard") { PocketGuardRow(vm) }
    }
}

// ---------------------------------------------------------------- Keypad

@Composable
internal fun KeypadPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    SegmentedGroup(stringResource(R.string.set_group_feedback)) {
        switchRow("keypad_tones", s.dialpadTones, Icons.Rounded.MusicNote) { v -> set { it.copy(dialpadTones = v) } }
        switchRow("keypad_vibration", s.dialpadHaptics, Icons.Rounded.Vibration) { v -> set { it.copy(dialpadHaptics = v) } }
    }
    AdvancedGroup {
        item("keypad_letters") { KeypadLettersRow(vm, Icons.Rounded.Translate) }
        linkRow("speed_dial", Icons.Rounded.Speed) { open(Routes.SpeedDial) }
        item("ussd") { UssdRow(vm, Icons.Rounded.Tag) }
    }
}

// ---------------------------------------------------------------- Blocking

@Composable
internal fun BlockingPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val scope = rememberCoroutineScope()
    val snoozing = s.screening.snoozeActive(System.currentTimeMillis())
    val snoozeOn = stringResource(R.string.set_expecting_call_on)
    SegmentedGroup {
        linkRow("blocking", Icons.Rounded.Block) { open(Routes.Blocking) }
        switchRow("repeat_callers", s.repeatCallerRingsThrough, Icons.Rounded.Repeat) { v -> set { it.copy(repeatCallerRingsThrough = v) } }
        switchRow("expecting_call", snoozing, Icons.Rounded.HourglassTop, sub = if (snoozing) snoozeOn else null) { v ->
            if (v) BlockingDialogs.show(BlockingDialog.Snooze)
            else scope.launch { BlockingActions.snooze(vm.c, 0) }
        }
    }
    AdvancedSection {
        BlockingAdvanced(vm, open)
    }
}

/** Blocking & spam's Advanced group: sales lines learnt from your calls, hints from notes, the lists and the rules. */
@Composable
private fun BlockingAdvanced(vm: AppViewModel, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    // I2: one choice for tags from your own calls (Tag quietly, the default) and the optional silence rule.
    val sales = SalesLines.of(s.screening.learnFromCalls, s.screening.silenceSalesLines)
    val salesChoices = listOf(stringResource(R.string.set_off), stringResource(R.string.set_sales_lines_tag), stringResource(R.string.set_sales_lines_silence))
    val salesSub = if (sales == SalesLines.TAG_AND_SILENCE) stringResource(R.string.set_sales_lines_silence_sub) else null
    SegmentedGroup {
        menuRow("learn_from_calls", salesChoices, sales.ordinal, Icons.Rounded.Storefront, sub = salesSub) { i ->
            val v = SalesLines.entries[i]
            // Learns at once (or forgets everything), in the app's scope so leaving the page doesn't stop it.
            vm.c.scope.launch {
                vm.c.settings.update { it.copy(screening = it.screening.copy(learnFromCalls = v.learn, silenceSalesLines = v.silence)) }
                catching { ReputationLearner.learn(vm.c) }
            }
        }
        // I7: notes, To call items and delivery QR codes turning "Expecting a call" on (off until accepted).
        item("expected_hints") { ExpectedHintsRow(vm) }
    }
    SegmentedGroup(stringResource(R.string.set_group_lists_rules)) {
        linkRow("spam_lists", Icons.Rounded.Inventory2) { open(BlockingRoutes.Lists) }
        linkRow("templates", Icons.Rounded.Style) { open(BlockingRoutes.Templates) }
        linkRow("transfer", Icons.Rounded.ImportExport) { open(BlockingRoutes.Transfer) }
    }
}

// ---------------------------------------------------------------- Contacts

@Composable
internal fun ContactsPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var importAccounts by remember { mutableStateOf<ImportAsk?>(null) }
    var skipDuplicates by remember { mutableStateOf(true) }
    var importReport by remember { mutableStateOf<ImportReport?>(null) }
    // Android 16's cloud default, when it takes new contacts instead of the phone: said under "Save new contacts to".
    var systemDefault by remember { mutableStateOf<AccountRef?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { vm.c.contacts.accounts() to vm.c.contacts.systemDefaultAccount() }.let { (a, d) -> accounts = a; systemDefault = d }
    }
    val importing = stringResource(R.string.set_importing)
    // An encrypted vCard asks for its passphrase first; it travels with its file only, until the import starts.
    var sealedUri by remember { mutableStateOf<Uri?>(null) }
    fun cancelImport() {
        importAccounts?.pass?.fill('\u0000')
        importAccounts = null
    }

    // A large import offers "Back up first?" before anything is written.
    val backupFirst = rememberBackupFirst(vm)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            if (vm.c.vcards.isSealed(uri)) {
                sealedUri = uri
                return@launch
            }
            val count = vm.c.vcards.estimateCount(uri)
            backupFirst.ask(count, BackupNudge.LARGE_IMPORT) {
                scope.launch { importAccounts = ImportAsk(uri, withContext(Dispatchers.IO) { vm.c.contacts.accounts() }) }
            }
        }
    }

    JobProgress(vm, UserJobs.Kind.EXPORT, UserJobs.Kind.IMPORT)
    val circleCfg by vm.c.circle.config.collectAsStateWithLifecycle()
    SegmentedGroup(stringResource(R.string.set_group_contact_list)) {
        switchRow("row_actions", s.contactRowActions, Icons.Rounded.TouchApp) { v -> set { it.copy(contactRowActions = v) } }
    }
    val systemNote = systemDefault?.let { stringResource(R.string.set_default_account_system, it.displayLabel) }
    SegmentedGroup(stringResource(R.string.set_group_organise)) {
        if (accounts.isNotEmpty()) {
            val current = accounts.indexOfFirst { it.type == s.defaultAccountType && it.name == s.defaultAccountName }.coerceAtLeast(0)
            menuRow("default_account", accounts.map { vm.accountLabel(it) }, current, Icons.Rounded.AccountCircle, sub = systemNote) { i ->
                val a = accounts[i]
                set { it.copy(defaultAccountType = a.type, defaultAccountName = a.name) }
            }
        }
        // My card, where people look for it (it was under Messaging).
        item("my_details") { MyDetailsRow(vm, Icons.Rounded.Badge) }
    }
    val severalAccounts = hasSeveralAccounts(vm)
    SegmentedGroup(stringResource(R.string.set_group_import_export)) {
        linkRow("import_file", Icons.Rounded.FileUpload) {
            importer.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
        }
        // One export screen for every format, with private contacts and notes when asked.
        linkRow("export_vcf", Icons.Rounded.FileDownload) { open(Routes.Export(ContactExport.Format.VCARD.name)) }
        linkRow("export_csv", Icons.Rounded.FileDownload) { open(Routes.Export(ContactExport.Format.CSV_PARLEY.name)) }
    }
    SegmentedGroup(stringResource(R.string.set_group_circle)) {
        // How keep-in-touch reminders arrive lives on Reminders; Circle ⋮ › Circle settings lands here.
        item {
            LinkRow(
                stringResource(R.string.set_circle_reminders_title), stringResource(R.string.set_circle_reminders_summary),
                Icons.Rounded.NotificationsActive,
            ) { open(RemindersRoutes.Page("circle_delivery")) }
        }
    }
    AdvancedGroup {
        switchRow("mirror_relations", s.mirrorRelations, Icons.Rounded.SyncAlt) { v -> set { it.copy(mirrorRelations = v) } }
        linkRow("contact_page", Icons.Rounded.ViewAgenda) { open(ContactPageRoutes.Sections) }
        logPromptsRow(vm, circleCfg)
        linkRow("import_sim", Icons.Rounded.SimCardDownload) { open(PeopleRoutes.SimImport) }
        if (severalAccounts) item("export_account") { ExportAccountRow(vm, Icons.AutoMirrored.Rounded.CallSplit) }
    }

    importAccounts?.let { ask ->
        val uri = ask.uri
        val pass = ask.pass
        ParleyDialog(
            onDismissRequest = ::cancelImport,
            title = { Text(stringResource(R.string.set_import_into)) },
            text = {
                Column {
                    SwitchRow(stringResource(R.string.set_skip_duplicates), stringResource(R.string.set_skip_duplicates_body), skipDuplicates) { skipDuplicates = it }
                    ask.accounts.forEach { a ->
                        ParleyListItem(headlineContent = { Text(vm.accountLabel(a)) }, colors = rowColors(), modifier = Modifier.clickable {
                            importAccounts = null
                            scope.launch {
                                // A CSV in another layout (Google, Outlook, any columns) goes to the column mapping first.
                                val preview = if (pass != null) null else catching { vm.c.vcards.csvPreview(uri) }.getOrNull()
                                if (preview != null && !preview.parley) {
                                    MessagingInbox.csvImport = CsvImportRequest(uri, a, skipDuplicates)
                                    open(MessagingRoutes.CsvMapping)
                                    return@launch
                                }
                                val skip = skipDuplicates
                                vm.jobs.start(
                                    UserJobs.Kind.IMPORT, importing,
                                    { e -> res.getString(R.string.set_import_failed_toast, UserErrorText.of(context, e)) },
                                ) { p ->
                                    val report = try {
                                        vm.c.vcards.import(uri, a, { done, total -> p.update(done, total) }, skipDuplicates = skip, passphrase = pass)
                                    } finally {
                                        pass?.fill('\u0000')
                                    }
                                    // The details, if this page is still open; the summary is said either way.
                                    importReport = report
                                    importSummaryText(res, report)
                                }
                            }
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(::cancelImport) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
    sealedUri?.let { uri ->
        SealedImportDialog(uri, onDismiss = { sealedUri = null }) { pass ->
            sealedUri = null
            scope.launch { importAccounts = ImportAsk(uri, withContext(Dispatchers.IO) { vm.c.contacts.accounts() }, pass) }
        }
    }
    importReport?.let { r -> ImportReportDialog(r) { importReport = null } }
}

/** A file waiting for "Import into": the accounts to offer and, for an encrypted vCard, the passphrase that opens it. */
private class ImportAsk(val uri: Uri, val accounts: List<AccountRef>, val pass: CharArray? = null)

// ---------------------------------------------------------------- Recents & history

@Composable
internal fun HistoryPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val archiveOn = vm.c.history.prefs.state.collectAsStateWithLifecycle().value.archiveEnabled
    val retention = RetentionDefaults.CHOICES
    val retentionLabels = listOf(
        stringResource(R.string.set_forever),
        pluralStringResource(R.plurals.set_days, 30, 30),
        pluralStringResource(R.plurals.set_days, 90, 90),
        pluralStringResource(R.plurals.set_months, 6, 6),
        pluralStringResource(R.plurals.set_years, 1, 1),
        pluralStringResource(R.plurals.set_years, 3, 3),
        pluralStringResource(R.plurals.set_years, 5, 5),
    )
    // The former "Call history" sub-screen lives here now: the archive, what's kept forever and the CSV option.
    SegmentedGroup(stringResource(R.string.set_group_call_history)) {
        item("archive") { KeepFullHistoryRow(vm, Icons.Rounded.ManageHistory) }
        menuRow("retention", retentionLabels, retention.indexOf(s.callLogRetentionDays).coerceAtLeast(0), Icons.Rounded.AutoDelete) { i ->
            set { it.copy(callLogRetentionDays = retention[i], callLogRetentionChosen = true) }
        }
        // Clear everything, unknown numbers or missed calls, with an export first (deleted calls come back from History & undo).
        item("clear_history") { ClearHistoryRow(vm, open, Icons.Rounded.DeleteSweep) }
    }
    val layoutLabels = recentsLayoutLabels()
    val styleLabels = recentsStyleLabels()
    val circleCfg by vm.c.circle.config.collectAsStateWithLifecycle()
    SegmentedGroup(stringResource(R.string.set_group_recents)) {
        // Grouped, chronological or by day (also in Recents ⋮).
        menuRow("recents_layout", layoutLabels, s.recentsLayout.ordinal, Icons.AutoMirrored.Rounded.ViewList) { i ->
            set { it.copy(recentsLayout = RecentsLayout.entries[i]) }
        }
        // Rich or simple call rows.
        menuRow("recents_style", styleLabels, s.recentsStyle.ordinal, Icons.Rounded.Palette) { i ->
            set { it.copy(recentsStyle = RecentsStyle.entries[i]) }
        }
        // Recents opens on the chip used last (Blocked and Voicemail aside).
        switchRow("recents_remember_filter", s.rememberRecentsFilter, Icons.Rounded.FilterList) { v -> set { it.copy(rememberRecentsFilter = v) } }
        // The People card in Call insights (Recents ⋮ › Call insights).
        peopleCardRows(vm, circleCfg)
    }
    AdvancedGroup {
        if (archiveOn) keptForeverRow(vm)
        linkRow("import_calls", Icons.Rounded.FileUpload) { open(HistoryRoutes.Import) }
        csvBomRow(vm)
        switchRow("sim_labels", s.showSimLabels, Icons.Rounded.SimCard) { v -> set { it.copy(showSimLabels = v) } }
    }
    CallHistoryNotes(vm)
}

// ---------------------------------------------------------------- Messaging

@Composable
internal fun MessagingPage(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val scope = rememberCoroutineScope()
    var editReplies by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SegmentedGroup {
        linkRow("quick_replies", Icons.Rounded.Quickreply, sub = s.quickReplies.joinToString(" · ")) { editReplies = true }
    }
    // When the record of numbers you opened chats with forgets them (the list itself is in Tools).
    val expiry by vm.c.messaging.expiryDays.collectAsStateWithLifecycle()
    AdvancedGroup {
        val choices = MessagedRecord.EXPIRY_CHOICES
        menuRow("messaged_expiry", choices.map { expiryLabel(context, it) }, choices.indexOf(expiry).coerceAtLeast(0), Icons.Rounded.Timer) { i ->
            scope.launch { vm.c.messaging.setExpiryDays(choices[i]) }
        }
    }
    if (editReplies) {
        QuickRepliesDialog(s.quickReplies, s.nameReply, onDismiss = { editReplies = false }) { list, nameReply ->
            set { it.copy(quickReplies = list, nameReply = nameReply) }
            editReplies = false
        }
    }
}

// ---------------------------------------------------------------- Privacy & security

@Composable
internal fun PrivacyPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val pn by vm.c.people.privateNames.state.collectAsStateWithLifecycle()
    var wipe by remember { mutableStateOf(false) }
    val lockTimes = listOf(0, 1, 5, 15, 60)
    val lockLabels = listOf(
        stringResource(R.string.set_lock_immediately),
        pluralStringResource(R.plurals.set_minutes, 1, 1),
        pluralStringResource(R.plurals.set_minutes, 5, 5),
        pluralStringResource(R.plurals.set_minutes, 15, 15),
        pluralStringResource(R.plurals.set_hours, 1, 1),
    )
    val on = stringResource(R.string.set_on)
    val off = stringResource(R.string.set_off)
    val unlockWith = unlockWithSummary(vm)
    SegmentedGroup(stringResource(R.string.set_group_app_lock)) {
        switchRow("app_lock", s.appLock, Icons.Rounded.Lock) { v ->
            val act = context as? ComponentActivity
            val why = res.getString(if (v) R.string.set_app_lock_turn_on else R.string.set_app_lock_turn_off)
            if (act != null) AppLock.confirm(act, why) { ok -> if (ok) set { it.copy(appLock = v) } }
        }
        if (s.appLock) {
            menuRow("lock_after", lockLabels, lockTimes.indexOf(s.lockAfterMinutes).coerceAtLeast(0), Icons.Rounded.LockClock) { i ->
                set { it.copy(lockAfterMinutes = lockTimes[i]) }
            }
            // I21: the Parley PIN and the duress PIN, on a page of their own.
            linkRow("app_lock_method", Icons.Rounded.Dialpad, sub = unlockWith) { open(AppLockRoutes.UnlockWith) }
        }
    }
    // How much about a caller call notifications and the call screen show while the phone is locked (in enum order).
    val lockCallerLabels = listOf(
        stringResource(R.string.set_lock_screen_caller_name_notes),
        stringResource(R.string.set_lock_screen_caller_name),
        stringResource(R.string.set_lock_screen_caller_initials),
        stringResource(R.string.set_lock_screen_caller_none),
    )
    SegmentedGroup(stringResource(R.string.set_group_lock_screen)) {
        choiceRow("lock_screen_caller", lockCallerLabels, s.lockScreenCaller.ordinal, Icons.Rounded.ScreenLockPortrait) { i ->
            set { it.copy(lockScreenCaller = LockScreenCaller.entries[i]) }
        }
    }
    // The family safe word, by label (WP-8).
    FamilySafetyPrivacyGroup(open)
    SegmentedGroup(stringResource(R.string.set_group_private_contacts)) {
        // After a duress unlock these show the switches as they were left, not what Parley enforces (I21).
        switchRow("hide_vault", s.duress?.hideVault ?: s.hideVault, Icons.Rounded.VisibilityOff) { v -> set { it.copy(hideVault = v) } }
    }
    SegmentedGroup(stringResource(R.string.set_group_your_data)) {
        linkRow("privacy_dashboard", Icons.Rounded.PrivacyTip) { open(Routes.Privacy) }
    }
    AdvancedGroup {
        switchRow("secure_screen", s.secureScreen, Icons.Rounded.VisibilityOff) { v -> set { it.copy(secureScreen = v) } }
        switchRow("private_history", s.duress?.privateVaultHistory ?: s.privateVaultHistory, Icons.Rounded.PhoneLocked) { v ->
            set { it.copy(privateVaultHistory = v) }
        }
        linkRow("who_can_see", Icons.Rounded.Apps) { open(PeopleRoutes.WhoCanSee) }
        linkRow("private_directory", Icons.Rounded.PhoneLocked, sub = if (pn.directory) on else off) { open(PeopleRoutes.PrivateNames) }
        linkRow("app_permissions", Icons.Rounded.AdminPanelSettings, external = true) {
            context.startOrSay(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        }
        linkRow("delete_all_data", Icons.Rounded.DeleteForever) { wipe = true }
    }
    if (wipe) DeleteAllDataDialog(vm) { wipe = false }
}

// ---------------------------------------------------------------- Backup & sync

@Composable
internal fun BackupPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val b by vm.c.backup.prefs.state.collectAsStateWithLifecycle()
    val lastBackup = if (b.lastBackupAt > 0) stringResource(R.string.set_last_backup, Format.shortWhen(context, b.lastBackupAt)) else null
    // The overdue reminder (its threshold is on Reminders).
    BackupReminderBanner(vm)
    SegmentedGroup(stringResource(R.string.set_group_backups)) {
        linkRow(
            "backup", Icons.Rounded.Backup,
            sub = lastBackup,
        ) { open(Routes.Backup) }
    }
    // One row for History & undo (its Snapshots tab is a Tools row of its own).
    SegmentedGroup(stringResource(R.string.set_group_undo)) {
        linkRow("journal", Icons.Rounded.RestoreFromTrash) { open(Routes.journal()) }
    }
    AdvancedGroup {
        linkRow("sync", Icons.Rounded.Sync) { open(Routes.Sync) }
        linkRow("open_export", Icons.Rounded.Description) { open(Routes.Export()) }
    }
}

// ---------------------------------------------------------------- Notifications & device

@Composable
internal fun NotificationsPage(vm: AppViewModel) {
    val context = LocalContext.current
    NotificationHealthCard(vm)
    val fullScreenOffText = stringResource(R.string.set_full_screen_off)
    val fullScreenOnText = stringResource(R.string.set_full_screen_on)
    val batterySub = stringResource(R.string.set_battery_sub)
    SegmentedGroup {
        linkRow("notification_settings", Icons.Rounded.Notifications, external = true) {
            context.startOrSay(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val fullScreenOff = Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()
        linkRow(
            "full_screen", if (fullScreenOff) Icons.Rounded.Warning else Icons.Rounded.Fullscreen,
            sub = if (fullScreenOff) fullScreenOffText else fullScreenOnText,
            external = true,
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                context.startOrSay(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName)))
            } else {
                context.startOrSay(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        linkRow("battery", Icons.Rounded.BatteryAlert, sub = batterySub, external = true) {
            context.startOrSay(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        if (Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) || Build.MANUFACTURER.equals("POCO", true)) {
            linkRow("xiaomi", Icons.Rounded.PhoneAndroid, external = true) {
                val miui = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
                runCatching { context.startActivity(miui) }.onFailure {
                    context.startOrSay(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- About

@Composable
internal fun AboutPage(open: (Destination) -> Unit, vm: AppViewModel? = null) {
    val context = LocalContext.current
    val diagnosticsSub = stringResource(R.string.set_diagnostics_sub)
    SegmentedGroup {
        item("version") {
            InfoRow(stringResource(R.string.set_version_row, BuildConfigInfo.versionName(context)), settingSummary("version"), Icons.Rounded.Info, trailing = {
                Icon(Icons.Rounded.CheckCircle, stringResource(R.string.set_no_internet), tint = CallColors.Accept)
            })
        }
        linkRow("diagnostics", Icons.Rounded.BugReport, sub = diagnosticsSub) {
            open(PeopleRoutes.Diagnostics)
        }
        if (vm != null) item("crash_reports") { CrashReportsRow(vm) }
    }
    Text(
        stringResource(R.string.set_no_internet_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

/** [app.parley.common.MessagedRecord.expiryLabel] in the current language. */
internal fun expiryLabel(context: Context, days: Int): String =
    if (days <= 0) context.getString(R.string.set_expiry_never) else context.resources.getQuantityString(R.plurals.set_expiry_after_days, days, days)
