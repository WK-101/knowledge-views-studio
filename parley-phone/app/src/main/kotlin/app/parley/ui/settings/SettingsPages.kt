package app.parley.ui.settings

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.PhoneForwarded
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DensityMedium
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
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
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SettingsPhone
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.SimCardDownload
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.BuildConfigInfo
import app.parley.common.AnswerGesture
import app.parley.common.AppSettings
import app.parley.common.ListDensity
import app.parley.common.ThemeMode
import app.parley.common.vcard.ImportReport
import app.parley.data.AccountRef
import app.parley.data.VCardIO
import app.parley.ui.CallColors
import app.parley.ui.Routes
import app.parley.ui.SegmentedGroup
import app.parley.ui.blocking.BlockingRoutes
import app.parley.ui.history.HistoryRoutes
import app.parley.ui.home.label
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.people.accountLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saves a settings change. */
@Composable
private fun rememberSettingsSetter(vm: AppViewModel): ((AppSettings) -> AppSettings) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(vm) { { f -> scope.launch { vm.c.settings.update(f) } } }
}

private fun android.content.Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

// ---------------------------------------------------------------- Appearance

@Composable
internal fun AppearancePage(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val themes = listOf(stringResource(R.string.set_theme_system), stringResource(R.string.set_theme_light), stringResource(R.string.set_theme_dark))
    val densities = listOf(stringResource(R.string.set_density_comfortable), stringResource(R.string.set_density_compact))
    val sortOptions = listOf(stringResource(R.string.set_sort_first_name), stringResource(R.string.set_sort_last_name))
    val navTabsTitle = settingTitle("nav_tabs")
    val navTabsHelp = stringResource(R.string.set_nav_tabs_help)
    SegmentedGroup(stringResource(R.string.set_group_theme)) {
        choiceRow("theme", themes, s.themeMode.ordinal, Icons.Rounded.DarkMode) { i -> set { it.copy(themeMode = ThemeMode.entries[i]) } }
        switchRow("amoled", s.amoledBlack, Icons.Rounded.Contrast) { v -> set { it.copy(amoledBlack = v) } }
        if (Build.VERSION.SDK_INT >= 31) switchRow("dynamic_color", s.dynamicColor, Icons.Rounded.Wallpaper) { v -> set { it.copy(dynamicColor = v) } }
    }
    // L1: per-app language (the system screen on Android 13+, an in-app picker before).
    SegmentedGroup(stringResource(R.string.lang_title)) { item("language") { LanguageRow() } }
    val tabLabels = s.navTabs.visible.map { it.label }
    SegmentedGroup(stringResource(R.string.set_group_navigation_bar)) {
        item("nav_tabs") {
            Column {
                ListItem(
                    headlineContent = { Text(navTabsTitle) },
                    supportingContent = { Text(navTabsHelp) },
                    colors = rowColors(),
                )
                NavTabsEditor(s.navTabs) { next -> set { it.copy(navTabs = next, startTab = next.startTab(it.startTab)) } }
            }
        }
        val visible = s.navTabs.visible
        menuRow("start_tab", tabLabels, visible.indexOf(s.navTabs.startTab(s.startTab)).coerceAtLeast(0), Icons.Rounded.PhoneAndroid) { i ->
            set { it.copy(startTab = visible[i]) }
        }
    }
    SegmentedGroup(stringResource(R.string.set_group_lists)) {
        choiceRow("density", densities, s.density.ordinal, Icons.Rounded.DensityMedium) { i -> set { it.copy(density = ListDensity.entries[i]) } }
        switchRow("row_actions", s.contactRowActions, Icons.Rounded.TouchApp) { v -> set { it.copy(contactRowActions = v) } }
        item("swipe_actions") { app.parley.ui.people.SwipeSettings(vm) }
        item("avatar_style") { app.parley.ui.people.AvatarStyleSetting(vm) }
    }
    SegmentedGroup(stringResource(R.string.set_group_names)) {
        menuRow("sort_names", sortOptions, if (s.sortByFirstName) 0 else 1, Icons.Rounded.SortByAlpha) { i -> set { it.copy(sortByFirstName = i == 0) } }
        item("second_line") { app.parley.ui.people.SecondLineRow(vm, Icons.AutoMirrored.Rounded.ShortText) }
        item("prefer_nickname") { app.parley.ui.people.PreferNicknameRow(vm, Icons.Rounded.Badge) }
    }
    // U2: every one-time tip shows again.
    val tipsReset = stringResource(R.string.ux_tips_reset_done)
    SegmentedGroup(stringResource(R.string.set_group_tips)) {
        linkRow("reset_tips", Icons.Rounded.Lightbulb) {
            vm.c.ux.resetTips()
            vm.toast(tipsReset)
        }
    }
}

// ---------------------------------------------------------------- Calls

@Composable
internal fun CallsPage(vm: AppViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    // P4: the role request, with the by-hand guide when Android refuses without asking.
    val requestRole = app.parley.ui.calls.rememberDialerRoleRequest { vm.refreshEnvironment() }
    val unknownTonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            set { it.copy(unknownRingtone = uri?.toString()) }
        }
    }
    val gestures = listOf(stringResource(R.string.set_answer_swipe), stringResource(R.string.set_answer_tap))
    val sameAsUsual = stringResource(R.string.set_same_as_usual)
    // The ringtone's title comes from the media provider: read it off the main thread.
    val toneName by androidx.compose.runtime.produceState<String?>(null, s.unknownRingtone) {
        value = s.unknownRingtone?.let { u ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { android.media.RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context) }.getOrNull()
            }
        }
    }
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
        // P4: the by-hand guide, for phones that refuse the request without asking.
        if (!isDefault) item("default_dialer_help") {
            var guide by remember { mutableStateOf(false) }
            LinkRow(settingTitle("default_dialer_help"), settingSummary("default_dialer_help"), Icons.AutoMirrored.Rounded.HelpOutline) { guide = true }
            if (guide) app.parley.ui.calls.DialerRoleGuide { guide = false }
        }
    }
    SegmentedGroup(stringResource(R.string.set_group_answering)) {
        choiceRow("answer_gesture", gestures, s.answerGesture.ordinal, Icons.Rounded.TouchApp) { i -> set { it.copy(answerGesture = AnswerGesture.entries[i]) } }
        switchRow("confirm_call", s.confirmBeforeCall, Icons.Rounded.CheckCircle) { v -> set { it.copy(confirmBeforeCall = v) } }
        item("call_haptics") { CallHapticsRow(vm, Icons.Rounded.Vibration) }
        item("connect_haptic") { ConnectHapticRow(vm, Icons.Rounded.Vibration) }
        linkRow("unknown_ringtone", Icons.Rounded.MusicNote, sub = toneName ?: sameAsUsual) {
            unknownTonePicker.launch(
                Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.unknownRingtone?.let(Uri::parse)),
            )
        }
    }
    CallExtrasGroups(vm)
    // R8/R9/X1: the memory prompt, notes on the lock screen and the pre-call peek.
    MemorySettingsGroup(vm)
    SegmentedGroup(stringResource(R.string.set_group_sims)) {
        linkRow("sims", Icons.Rounded.SimCard) { open(HistoryRoutes.SIMS) }
        linkRow("sim_accounts", Icons.Rounded.SettingsPhone, external = true) { context.startSafely(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
        linkRow("carrier_settings", Icons.AutoMirrored.Rounded.PhoneForwarded, external = true) { context.startSafely(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)) }
    }
}

// ---------------------------------------------------------------- Keypad

@Composable
internal fun KeypadPage(vm: AppViewModel, open: (String) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    SegmentedGroup(stringResource(R.string.set_group_feedback)) {
        switchRow("keypad_tones", s.dialpadTones, Icons.Rounded.MusicNote) { v -> set { it.copy(dialpadTones = v) } }
        switchRow("keypad_vibration", s.dialpadHaptics, Icons.Rounded.Vibration) { v -> set { it.copy(dialpadHaptics = v) } }
    }
    SegmentedGroup(stringResource(R.string.set_group_keys)) {
        item("keypad_letters") { KeypadLettersRow(vm, Icons.Rounded.Translate) }
        linkRow("speed_dial", Icons.Rounded.Speed) { open(Routes.SPEED_DIAL) }
        item("ussd") { UssdRow(vm, Icons.Rounded.Tag) }
    }
}

// ---------------------------------------------------------------- Call time

@Composable
internal fun CallTimePage(vm: AppViewModel, open: (String) -> Unit) {
    SegmentedGroup {
        item("call_time") { CallTimeRow(vm, open, Icons.Rounded.Timer) }
        linkRow("plan_minutes", Icons.Rounded.SimCard) { open(HistoryRoutes.SIMS) }
    }
}

// ---------------------------------------------------------------- Blocking

@Composable
internal fun BlockingPage(vm: AppViewModel, open: (String) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val scope = rememberCoroutineScope()
    val snoozing = s.screening.snoozeActive(System.currentTimeMillis())
    val snoozeOn = stringResource(R.string.set_expecting_call_on)
    SegmentedGroup {
        linkRow("blocking", Icons.Rounded.Block) { open(Routes.BLOCKING) }
        switchRow("repeat_callers", s.repeatCallerRingsThrough, Icons.Rounded.Repeat) { v -> set { it.copy(repeatCallerRingsThrough = v) } }
        switchRow("expecting_call", snoozing, Icons.Rounded.HourglassTop, sub = if (snoozing) snoozeOn else null) { v ->
            if (v) app.parley.ui.blocking.BlockingDialogs.show(app.parley.ui.blocking.BlockingDialog.Snooze)
            else scope.launch { app.parley.blocking.BlockingActions.snooze(vm.c, 0) }
        }
    }
    SegmentedGroup(stringResource(R.string.set_group_lists_rules)) {
        linkRow("spam_lists", Icons.Rounded.Inventory2) { open(BlockingRoutes.LISTS) }
        linkRow("templates", Icons.Rounded.Style) { open(BlockingRoutes.TEMPLATES) }
        linkRow("dry_run", Icons.Rounded.Science) { open(BlockingRoutes.DRY_RUN) }
        linkRow("transfer", Icons.Rounded.ImportExport) { open(BlockingRoutes.TRANSFER) }
    }
}

// ---------------------------------------------------------------- Contacts

@Composable
internal fun ContactsPage(vm: AppViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var importAccounts by remember { mutableStateOf<Pair<Uri, List<AccountRef>>?>(null) }
    var skipDuplicates by remember { mutableStateOf(true) }
    var importReport by remember { mutableStateOf<ImportReport?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    val tempCount = app.parley.ui.temporary.rememberTemporaryItems(vm).size
    LaunchedEffect(Unit) { accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }
    val exporting = stringResource(R.string.set_exporting)
    val importing = stringResource(R.string.set_importing)

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        if (uri != null) scope.launch {
            progress = exporting
            val r = vm.c.vcards.export(uri, vm.c.contacts.contacts.value.orEmpty())
            progress = null
            vm.toast(exportMessage(context, r))
        }
    }
    val csvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            progress = exporting
            val r = try { vm.c.vcards.exportCsv(uri, vm.c.contacts.contacts.value.orEmpty()) } catch (e: Exception) { VCardIO.ExportResult(0, listOf(e.message ?: "error")) }
            progress = null
            vm.toast(exportMessage(context, r))
        }
    }
    // C2: a large import offers "Back up first?" before anything is written.
    val backupFirst = app.parley.ui.backup.rememberBackupFirst(vm)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val count = vm.c.vcards.estimateCount(uri)
            backupFirst.ask(count, app.parley.common.ux.BackupNudge.LARGE_IMPORT) {
                scope.launch { importAccounts = uri to withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }
            }
        }
    }

    progress?.let { msg ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
    val tempSub = if (tempCount == 0) null else pluralStringResource(R.plurals.set_temporary_count, tempCount, tempCount)
    val reminderSub = stringResource(R.string.set_birthday_reminders_at, "${s.birthdayReminderHour}:00")
    val circleCfg by vm.c.circle.config.collectAsStateWithLifecycle()
    SegmentedGroup(stringResource(R.string.set_group_organise)) {
        if (accounts.isNotEmpty()) {
            val current = accounts.indexOfFirst { it.type == s.defaultAccountType && it.name == s.defaultAccountName }.coerceAtLeast(0)
            menuRow("default_account", accounts.map { vm.accountLabel(it) }, current, Icons.Rounded.AccountCircle) { i ->
                val a = accounts[i]
                set { it.copy(defaultAccountType = a.type, defaultAccountName = a.name) }
            }
        }
        item("labels") { app.parley.ui.people.LabelsRow(vm, open, Icons.AutoMirrored.Rounded.Label) }
        linkRow("temporary_contacts", Icons.Rounded.AutoDelete, sub = tempSub) {
            open(Routes.TEMPORARY)
        }
        linkRow("bulk_add", Icons.Rounded.GroupAdd) { open(app.parley.messaging.MessagingRoutes.BULK_ADD) }
        linkRow("duplicates", Icons.AutoMirrored.Rounded.CallMerge) { open(Routes.DUPLICATES) }
        linkRow("health", Icons.Rounded.HealthAndSafety) { open(Routes.HEALTH) }
    }
    val severalAccounts = app.parley.ui.people.hasSeveralAccounts(vm)
    SegmentedGroup(stringResource(R.string.set_group_import_export)) {
        linkRow("import_file", Icons.Rounded.FileUpload) {
            importer.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
        }
        linkRow("import_sim", Icons.Rounded.SimCardDownload) { open(PeopleRoutes.SIM_IMPORT) }
        linkRow("export_vcf", Icons.Rounded.FileDownload) { exporter.launch("contacts.vcf") }
        linkRow("export_csv", Icons.Rounded.FileDownload) { csvExporter.launch("contacts.csv") }
        if (severalAccounts) item("export_account") { app.parley.ui.people.ExportAccountRow(vm, Icons.AutoMirrored.Rounded.CallSplit) }
    }
    SegmentedGroup(stringResource(R.string.set_group_birthdays)) {
        linkRow("birthdays", Icons.Rounded.Cake) { open(Routes.BIRTHDAYS) }
        switchRow("birthday_reminders", s.birthdayReminders, Icons.Rounded.NotificationsActive, sub = reminderSub) { v ->
            set { it.copy(birthdayReminders = v) }
        }
        if (s.birthdayReminders) {
            menuRow("reminder_time", (6..22).map { "$it:00" }, (s.birthdayReminderHour - 6).coerceIn(0, 16), Icons.Rounded.Timer) { i ->
                set { it.copy(birthdayReminderHour = i + 6) }
                app.parley.work.RemindersWorker.schedule(context, i + 6)
            }
        }
        switchRow("nudges", s.reachOutNudges, Icons.Rounded.Handshake) { v -> set { it.copy(reachOutNudges = v) } }
        // R3/R4/R5: the Circle's reminder and "Log this?" choices.
        circleSettingRows(vm, circleCfg, s.birthdayReminders, s.reachOutNudges)
    }

    importAccounts?.let { (uri, accs) ->
        AlertDialog(
            onDismissRequest = { importAccounts = null },
            title = { Text(stringResource(R.string.set_import_into)) },
            text = {
                Column {
                    SwitchRow(stringResource(R.string.set_skip_duplicates), stringResource(R.string.set_skip_duplicates_body), skipDuplicates) { skipDuplicates = it }
                    accs.forEach { a ->
                        ListItem(headlineContent = { Text(vm.accountLabel(a)) }, colors = rowColors(), modifier = Modifier.clickable {
                            importAccounts = null
                            scope.launch {
                                // M12: a CSV in another layout (Google, Outlook, any columns) goes to the column mapping first.
                                val preview = runCatching { vm.c.vcards.csvPreview(uri) }.getOrNull()
                                if (preview != null && !preview.parley) {
                                    app.parley.messaging.MessagingInbox.csvImport = app.parley.messaging.CsvImportRequest(uri, a, skipDuplicates)
                                    open(app.parley.messaging.MessagingRoutes.CSV_MAPPING)
                                    return@launch
                                }
                                progress = importing
                                val report = try {
                                    vm.c.vcards.import(uri, a, skipDuplicates = skipDuplicates)
                                } catch (e: Exception) {
                                    vm.toast(res.getString(R.string.set_import_failed_toast, e.message.orEmpty()))
                                    null
                                }
                                progress = null
                                importReport = report
                            }
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ importAccounts = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
    importReport?.let { r -> ImportReportDialog(r) { importReport = null } }
}

// ---------------------------------------------------------------- Recents & history

@Composable
internal fun HistoryPage(vm: AppViewModel, open: (String) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val retention = listOf(0, 30, 90, 180, 365)
    val retentionLabels = listOf(
        stringResource(R.string.set_forever),
        pluralStringResource(R.plurals.set_days, 30, 30),
        pluralStringResource(R.plurals.set_days, 90, 90),
        pluralStringResource(R.plurals.set_months, 6, 6),
        pluralStringResource(R.plurals.set_years, 1, 1),
    )
    SegmentedGroup(stringResource(R.string.set_group_call_history)) {
        item("archive") { app.parley.ui.history.KeepFullHistoryRow(vm, open, Icons.Rounded.ManageHistory) }
        linkRow("history_details", Icons.Rounded.RestoreFromTrash) { open(HistoryRoutes.SETTINGS) }
        menuRow("retention", retentionLabels, retention.indexOf(s.callLogRetentionDays).coerceAtLeast(0), Icons.Rounded.AutoDelete) { i ->
            set { it.copy(callLogRetentionDays = retention[i]) }
        }
        // P5: clear everything, unknown numbers or missed calls, with an export first.
        item("clear_history") { app.parley.ui.history.ClearHistoryRow(vm, open, Icons.Rounded.DeleteSweep) }
    }
    val layoutLabels = app.parley.ui.history.recentsLayoutLabels()
    val circleCfg by vm.c.circle.config.collectAsStateWithLifecycle()
    SegmentedGroup(stringResource(R.string.set_group_recents)) {
        switchRow("sim_labels", s.showSimLabels, Icons.Rounded.SimCard) { v -> set { it.copy(showSimLabels = v) } }
        // P8: grouped, chronological or by day (also in Recents ⋮).
        menuRow("recents_layout", layoutLabels, s.recentsLayout.ordinal, Icons.AutoMirrored.Rounded.ViewList) { i ->
            set { it.copy(recentsLayout = app.parley.common.calls.RecentsLayout.entries[i]) }
        }
        linkRow("insights", Icons.Rounded.Insights) { open(HistoryRoutes.INSIGHTS) }
        // R6: the People card in Insights.
        peopleCardRows(vm, circleCfg)
        linkRow("import_calls", Icons.Rounded.FileUpload) { open(HistoryRoutes.IMPORT) }
    }
}

// ---------------------------------------------------------------- Messaging

@Composable
internal fun MessagingPage(vm: AppViewModel, open: (String) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val scope = rememberCoroutineScope()
    var editReplies by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SegmentedGroup {
        linkRow("quick_replies", Icons.Rounded.Quickreply, sub = s.quickReplies.joinToString(" · ")) { editReplies = true }
        item("my_details") { MyDetailsRow(vm, Icons.Rounded.Badge) }
    }
    // M10: the record of numbers you opened chats with, and when it forgets them.
    val recorded by vm.c.messaging.lastMessaged.collectAsStateWithLifecycle()
    val recording by vm.c.messaging.recordEnabled.collectAsStateWithLifecycle()
    val expiry by vm.c.messaging.expiryDays.collectAsStateWithLifecycle()
    val messagedSub = if (recording) pluralStringResource(R.plurals.set_numbers_count, recorded.size, recorded.size) else stringResource(R.string.set_not_kept)
    SegmentedGroup(stringResource(R.string.set_group_messaged)) {
        linkRow("messaged_numbers", Icons.AutoMirrored.Rounded.Chat, sub = messagedSub) {
            open(app.parley.messaging.MessagingRoutes.MESSAGED)
        }
        val choices = app.parley.common.MessagedRecord.EXPIRY_CHOICES
        menuRow("messaged_expiry", choices.map { expiryLabel(context, it) }, choices.indexOf(expiry).coerceAtLeast(0), Icons.Rounded.Timer) { i ->
            scope.launch { vm.c.messaging.setExpiryDays(choices[i]) }
        }
    }
    if (editReplies) {
        QuickRepliesDialog(s.quickReplies, onDismiss = { editReplies = false }) { list ->
            set { it.copy(quickReplies = list) }
            editReplies = false
        }
    }
}

// ---------------------------------------------------------------- Privacy & security

@Composable
internal fun PrivacyPage(vm: AppViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val pn by vm.c.people.privateNames.state.collectAsStateWithLifecycle()
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
    SegmentedGroup(stringResource(R.string.set_group_app_lock)) {
        switchRow("app_lock", s.appLock, Icons.Rounded.Lock) { v ->
            val act = context as? androidx.fragment.app.FragmentActivity
            if (act != null) app.parley.security.AppLock.authenticate(act, res.getString(if (v) R.string.set_app_lock_turn_on else R.string.set_app_lock_turn_off)) { ok -> if (ok) set { it.copy(appLock = v) } }
        }
        if (s.appLock) {
            menuRow("lock_after", lockLabels, lockTimes.indexOf(s.lockAfterMinutes).coerceAtLeast(0), Icons.Rounded.LockClock) { i ->
                set { it.copy(lockAfterMinutes = lockTimes[i]) }
            }
        }
        switchRow("secure_screen", s.secureScreen, Icons.Rounded.VisibilityOff) { v -> set { it.copy(secureScreen = v) } }
    }
    SegmentedGroup(stringResource(R.string.set_group_private_contacts)) {
        switchRow("hide_vault", s.hideVault, Icons.Rounded.VisibilityOff) { v -> set { it.copy(hideVault = v) } }
        switchRow("private_history", s.privateVaultHistory, Icons.Rounded.PhoneLocked) { v -> set { it.copy(privateVaultHistory = v) } }
    }
    SegmentedGroup(stringResource(R.string.set_group_your_data)) {
        linkRow("privacy_dashboard", Icons.Rounded.PrivacyTip) { open(Routes.PRIVACY) }
        linkRow("who_can_see", Icons.Rounded.Apps) { open(PeopleRoutes.WHO_CAN_SEE) }
        linkRow("private_names", Icons.Rounded.Badge, sub = if (pn.enabled) on else off) { open(PeopleRoutes.PRIVATE_NAMES) }
        linkRow("private_directory", Icons.Rounded.PhoneLocked, sub = if (pn.directory) on else off) { open(PeopleRoutes.PRIVATE_NAMES) }
        linkRow("app_permissions", Icons.Rounded.AdminPanelSettings, external = true) {
            context.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        }
    }
}

// ---------------------------------------------------------------- Backup & sync

@Composable
internal fun BackupPage(vm: AppViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    val b by vm.c.backup.prefs.state.collectAsStateWithLifecycle()
    val lastBackup = if (b.lastBackupAt > 0) stringResource(R.string.set_last_backup, app.parley.ui.common.Format.shortWhen(context, b.lastBackupAt)) else null
    // C3: the overdue reminder and its threshold.
    app.parley.ui.backup.BackupReminderBanner(vm)
    val ux by vm.c.ux.state.collectAsStateWithLifecycle()
    val reminderOptions = app.parley.common.ux.BackupNudge.REMINDER_DAYS.map { pluralStringResource(R.plurals.ux_backup_after_days, it, it) }
    SegmentedGroup(stringResource(R.string.set_group_backups)) {
        linkRow(
            "backup", Icons.Rounded.Backup,
            sub = lastBackup,
        ) { open(Routes.BACKUP) }
        menuRow(
            "backup_reminder", reminderOptions, app.parley.common.ux.BackupNudge.REMINDER_DAYS.indexOf(ux.backupReminderDays).coerceAtLeast(0),
            Icons.Rounded.NotificationsActive,
        ) { i -> vm.c.ux.setBackupReminderDays(app.parley.common.ux.BackupNudge.REMINDER_DAYS[i]) }
        linkRow("sync", Icons.Rounded.Sync) { open(Routes.SYNC) }
    }
    SegmentedGroup(stringResource(R.string.set_group_undo)) {
        linkRow("journal", Icons.Rounded.RestoreFromTrash) { open(Routes.JOURNAL) }
        linkRow("time_machine", Icons.Rounded.ManageHistory) { open(Routes.CHANGES) }
    }
}

// ---------------------------------------------------------------- Notifications & device

@Composable
internal fun NotificationsPage(vm: AppViewModel) {
    val context = LocalContext.current
    app.parley.ui.calltime.NotificationHealthCard(vm)
    val fullScreenOffText = stringResource(R.string.set_full_screen_off)
    val fullScreenOnText = stringResource(R.string.set_full_screen_on)
    val batterySub = stringResource(R.string.set_battery_sub)
    SegmentedGroup {
        linkRow("notification_settings", Icons.Rounded.Notifications, external = true) {
            context.startSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val fullScreenOff = Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()
        linkRow(
            "full_screen", if (fullScreenOff) Icons.Rounded.Warning else Icons.Rounded.Fullscreen,
            sub = if (fullScreenOff) fullScreenOffText else fullScreenOnText,
            external = true,
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                context.startSafely(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName)))
            } else {
                context.startSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        linkRow("battery", Icons.Rounded.BatteryAlert, sub = batterySub, external = true) {
            context.startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        if (Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) || Build.MANUFACTURER.equals("POCO", true)) {
            linkRow("xiaomi", Icons.Rounded.PhoneAndroid, external = true) {
                val miui = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
                runCatching { context.startActivity(miui) }.onFailure {
                    context.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- About

@Composable
internal fun AboutPage(open: (String) -> Unit, vm: AppViewModel? = null) {
    val context = LocalContext.current
    val diagnosticsSub = stringResource(R.string.set_diagnostics_sub)
    SegmentedGroup {
        item("version") {
            InfoRow(stringResource(R.string.set_version_row, BuildConfigInfo.versionName(context)), settingSummary("version"), Icons.Rounded.Info, trailing = {
                androidx.compose.material3.Icon(Icons.Rounded.CheckCircle, stringResource(R.string.set_no_internet), tint = CallColors.Accept)
            })
        }
        linkRow("diagnostics", Icons.Rounded.BugReport, sub = diagnosticsSub) {
            open(PeopleRoutes.DIAGNOSTICS)
        }
        if (vm != null) item("crash_reports") { app.parley.ui.people.CrashReportsRow(vm) }
    }
    Text(
        stringResource(R.string.set_no_internet_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

/** [app.parley.common.MessagedRecord.expiryLabel] in the current language. */
internal fun expiryLabel(context: android.content.Context, days: Int): String =
    if (days <= 0) context.getString(R.string.set_expiry_never) else context.resources.getQuantityString(R.plurals.set_expiry_after_days, days, days)
