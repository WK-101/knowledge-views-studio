package app.parley.ui.settings

import app.parley.ui.Destination
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
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
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Storefront
import app.parley.data.calls.ReputationLearner
import androidx.compose.material.icons.automirrored.rounded.PhoneForwarded
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DensityMedium
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.ViewAgenda
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
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.BuildConfigInfo
import app.parley.blocking.BlockingActions
import app.parley.common.AnswerGesture
import app.parley.common.AppSettings
import app.parley.common.HomeLayout
import app.parley.common.ListDensity
import app.parley.common.MessagedRecord
import app.parley.common.ThemeMode
import app.parley.common.calls.RecentsLayout
import app.parley.common.ux.BackupNudge
import app.parley.common.ux.CallScreenBackground
import app.parley.common.ux.RecentsStyle
import app.parley.common.vcard.ImportReport
import app.parley.data.AccountRef
import app.parley.data.VCardIO
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
import app.parley.ui.journal.HistoryTab
import app.parley.ui.people.AvatarStyleSetting
import app.parley.ui.people.CrashReportsRow
import app.parley.ui.people.ExportAccountRow
import app.parley.ui.people.LabelsRow
import app.parley.ui.people.PeopleRoutes
import app.parley.ui.people.PreferNicknameRow
import app.parley.ui.people.SecondLineRow
import app.parley.ui.people.SwipeSettings
import app.parley.ui.people.accountLabel
import app.parley.ui.people.hasSeveralAccounts
import app.parley.ui.temporary.rememberTemporaryItems
import app.parley.work.RemindersWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.LinkRow
import app.parley.ui.InfoRow
import app.parley.ui.rowColors
import app.parley.ui.SwitchRow
import app.parley.ui.ParleyDialog
import androidx.compose.material.icons.automirrored.rounded.MergeType

/** Saves a settings change. */
@Composable
private fun rememberSettingsSetter(vm: AppViewModel): ((AppSettings) -> AppSettings) -> Unit {
    val scope = rememberCoroutineScope()
    return remember(vm) { { f -> scope.launch { vm.c.settings.update(f) } } }
}

private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }
}

// ---------------------------------------------------------------- Appearance

@Composable
internal fun AppearancePage(vm: AppViewModel, open: (Destination) -> Unit = {}) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val themes = listOf(stringResource(R.string.set_theme_system), stringResource(R.string.set_theme_light), stringResource(R.string.set_theme_dark))
    val densities = listOf(stringResource(R.string.set_density_comfortable), stringResource(R.string.set_density_compact))
    val sortOptions = listOf(stringResource(R.string.set_sort_first_name), stringResource(R.string.set_sort_last_name))
    SegmentedGroup(stringResource(R.string.set_group_theme)) {
        choiceRow("theme", themes, s.themeMode.ordinal, Icons.Rounded.DarkMode) { i -> set { it.copy(themeMode = ThemeMode.entries[i]) } }
        switchRow("amoled", s.amoledBlack, Icons.Rounded.Contrast) { v -> set { it.copy(amoledBlack = v) } }
        if (Build.VERSION.SDK_INT >= 31) switchRow("dynamic_color", s.dynamicColor, Icons.Rounded.Wallpaper) { v -> set { it.copy(dynamicColor = v) } }
    }
    SegmentedGroup(stringResource(R.string.set_group_lists)) {
        choiceRow("density", densities, s.density.ordinal, Icons.Rounded.DensityMedium) { i -> set { it.copy(density = ListDensity.entries[i]) } }
        item("avatar_style") { AvatarStyleSetting(vm) }
    }
    SegmentedGroup(stringResource(R.string.set_group_names)) {
        menuRow("sort_names", sortOptions, if (s.sortByFirstName) 0 else 1, Icons.Rounded.SortByAlpha) { i -> set { it.copy(sortByFirstName = i == 0) } }
        item("second_line") { SecondLineRow(vm, Icons.AutoMirrored.Rounded.ShortText) }
        item("prefer_nickname") { PreferNicknameRow(vm, Icons.Rounded.Badge) }
    }
    // Every one-time tip shows again.
    val tipsReset = stringResource(R.string.ux_tips_reset_done)
    SegmentedGroup(stringResource(R.string.set_group_tips)) {
        linkRow("reset_tips", Icons.Rounded.Lightbulb) {
            vm.c.ux.resetTips()
            vm.toast(tipsReset)
        }
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
                ListItem(
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
    // Combine Keypad + Recents and Favourites + Contacts (optional), and the Recents row tap.
    LayoutSettingsGroup(vm)
    SegmentedGroup(stringResource(R.string.set_group_gestures)) {
        item("swipe_actions") { SwipeSettings(vm) }
    }
    // Simple mode, set up here (for someone else, or for yourself).
    SegmentedGroup {
        linkRow("simple_mode", Icons.Rounded.Accessibility) { open(ExtrasRoutes.SimpleSetup) }
    }
}

// ---------------------------------------------------------------- Calls

@Composable
internal fun CallsPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    // The role request, with the by-hand guide when Android refuses without asking.
    val requestRole = rememberDialerRoleRequest { vm.refreshEnvironment() }
    val unknownTonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            set { it.copy(unknownRingtone = uri?.toString()) }
        }
    }
    val gestures = listOf(stringResource(R.string.set_answer_swipe), stringResource(R.string.set_answer_tap))
    val backgrounds = listOf(stringResource(R.string.set_call_background_caller), stringResource(R.string.set_call_background_plain))
    val sameAsUsual = stringResource(R.string.set_same_as_usual)
    // The ringtone's title comes from the media provider: read it off the main thread.
    val toneName by produceState<String?>(null, s.unknownRingtone) {
        value = s.unknownRingtone?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching { RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context) }.getOrNull()
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
        // The by-hand guide, for phones that refuse the request without asking.
        if (!isDefault) item("default_dialer_help") {
            var guide by remember { mutableStateOf(false) }
            LinkRow(settingTitle("default_dialer_help"), settingSummary("default_dialer_help"), Icons.AutoMirrored.Rounded.HelpOutline) { guide = true }
            if (guide) DialerRoleGuide { guide = false }
        }
    }
    SegmentedGroup(stringResource(R.string.set_group_answering)) {
        choiceRow("answer_gesture", gestures, s.answerGesture.ordinal, Icons.Rounded.TouchApp) { i -> set { it.copy(answerGesture = AnswerGesture.entries[i]) } }
        // The caller's colour at the top of the call screen, or none; a contact's own picture shows either way.
        choiceRow("call_background", backgrounds, s.callBackground.ordinal, Icons.Rounded.Palette) { i ->
            set { it.copy(callBackground = CallScreenBackground.entries[i]) }
        }
        switchRow("caller_photo", s.showCallerPhoto, Icons.Rounded.AccountCircle) { v -> set { it.copy(showCallerPhoto = v) } }
        switchRow("confirm_call", s.confirmBeforeCall, Icons.Rounded.CheckCircle) { v -> set { it.copy(confirmBeforeCall = v) } }
        item("call_haptics") { CallHapticsRow(vm, Icons.Rounded.Vibration) }
        linkRow("unknown_ringtone", Icons.Rounded.MusicNote, sub = toneName ?: sameAsUsual) {
            unknownTonePicker.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.unknownRingtone?.let(Uri::parse)),
            )
        }
    }
    CallExtrasGroups(vm)
    // RTT (real-time text): Answer with RTT and Android's TTY and RTT settings.
    RttSettingsGroup(vm)
    // Auto-answer and the haptic caller ID.
    CallerRingGroup(vm, open)
    // The memory prompt, notes on the lock screen and the pre-call peek.
    MemorySettingsGroup(vm)
    // Helpers to bring into a call (WP-8).
    FamilySafetyCallsGroup(vm, open)
    // The drive profile and calling abroad (WP-15).
    OnTheRoadGroup(vm, open)
    SegmentedGroup(stringResource(R.string.set_group_sims)) {
        linkRow("sims", Icons.Rounded.SimCard) { open(HistoryRoutes.Sims) }
        linkRow("sim_accounts", Icons.Rounded.SettingsPhone, external = true) { context.startSafely(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
        linkRow("carrier_settings", Icons.AutoMirrored.Rounded.PhoneForwarded, external = true) { context.startSafely(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)) }
    }
    CallsAdvancedGroup(vm)
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
    SegmentedGroup(stringResource(R.string.set_group_keys)) {
        item("keypad_letters") { KeypadLettersRow(vm, Icons.Rounded.Translate) }
        linkRow("speed_dial", Icons.Rounded.Speed) { open(Routes.SpeedDial) }
        item("ussd") { UssdRow(vm, Icons.Rounded.Tag) }
    }
}

// ---------------------------------------------------------------- Call time

@Composable
internal fun CallTimePage(vm: AppViewModel, open: (Destination) -> Unit) {
    SegmentedGroup {
        item("call_time") { CallTimeRow(vm, open, Icons.Rounded.Timer) }
        // Plan minutes are set per SIM: one row, on the Calls page ("SIMs & plan minutes").
        linkRow("sims", Icons.Rounded.SimCard) { open(HistoryRoutes.Sims) }
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
        // I2: tags from your own calls (on), and the optional silence rule (off, only while learning is on).
        switchRow("learn_from_calls", s.screening.learnFromCalls, Icons.Rounded.Storefront) { v ->
            // Learns at once (or forgets everything), in the app's scope so leaving the page doesn't stop it.
            vm.c.scope.launch {
                vm.c.settings.update { it.copy(screening = it.screening.copy(learnFromCalls = v)) }
                runCatching { ReputationLearner.learn(vm.c) }
            }
        }
        switchRow("silence_sales_lines", s.screening.silenceSalesLines, Icons.AutoMirrored.Rounded.VolumeOff, enabled = s.screening.learnFromCalls) { v ->
            set { it.copy(screening = it.screening.copy(silenceSalesLines = v)) }
        }
        switchRow("expecting_call", snoozing, Icons.Rounded.HourglassTop, sub = if (snoozing) snoozeOn else null) { v ->
            if (v) BlockingDialogs.show(BlockingDialog.Snooze)
            else scope.launch { BlockingActions.snooze(vm.c, 0) }
        }
        // I7: notes, To call items and delivery QR codes turning "Expecting a call" on (off until accepted).
        item("expected_hints") { ExpectedHintsRow(vm) }
    }
    SegmentedGroup(stringResource(R.string.set_group_lists_rules)) {
        linkRow("spam_lists", Icons.Rounded.Inventory2) { open(BlockingRoutes.Lists) }
        linkRow("templates", Icons.Rounded.Style) { open(BlockingRoutes.Templates) }
        linkRow("dry_run", Icons.Rounded.Science) { open(BlockingRoutes.DryRun) }
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
    var importAccounts by remember { mutableStateOf<Pair<Uri, List<AccountRef>>?>(null) }
    var skipDuplicates by remember { mutableStateOf(true) }
    var importReport by remember { mutableStateOf<ImportReport?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    val tempCount = rememberTemporaryItems(vm).size
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
    // A large import offers "Back up first?" before anything is written.
    val backupFirst = rememberBackupFirst(vm)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val count = vm.c.vcards.estimateCount(uri)
            backupFirst.ask(count, BackupNudge.LARGE_IMPORT) {
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
    SegmentedGroup(stringResource(R.string.set_group_contact_list)) {
        switchRow("row_actions", s.contactRowActions, Icons.Rounded.TouchApp) { v -> set { it.copy(contactRowActions = v) } }
    }
    SegmentedGroup(stringResource(R.string.set_group_organise)) {
        if (accounts.isNotEmpty()) {
            val current = accounts.indexOfFirst { it.type == s.defaultAccountType && it.name == s.defaultAccountName }.coerceAtLeast(0)
            menuRow("default_account", accounts.map { vm.accountLabel(it) }, current, Icons.Rounded.AccountCircle) { i ->
                val a = accounts[i]
                set { it.copy(defaultAccountType = a.type, defaultAccountName = a.name) }
            }
        }
        item("labels") { LabelsRow(vm, open, Icons.AutoMirrored.Rounded.Label) }
        switchRow("mirror_relations", s.mirrorRelations, Icons.Rounded.SyncAlt) { v -> set { it.copy(mirrorRelations = v) } }
        linkRow("temporary_contacts", Icons.Rounded.AutoDelete, sub = tempSub) {
            open(Routes.Temporary)
        }
        linkRow("bulk_add", Icons.Rounded.GroupAdd) { open(MessagingRoutes.BulkAdd) }
        linkRow("duplicates", Icons.AutoMirrored.Rounded.MergeType) { open(Routes.Duplicates) }
        linkRow("health", Icons.Rounded.HealthAndSafety) { open(Routes.Health) }
        linkRow("contact_page", Icons.Rounded.ViewAgenda) { open(ContactPageRoutes.Sections) }
    }
    val severalAccounts = hasSeveralAccounts(vm)
    SegmentedGroup(stringResource(R.string.set_group_import_export)) {
        linkRow("import_file", Icons.Rounded.FileUpload) {
            importer.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
        }
        linkRow("export_vcf", Icons.Rounded.FileDownload) { exporter.launch("contacts.vcf") }
        linkRow("export_csv", Icons.Rounded.FileDownload) { csvExporter.launch("contacts.csv") }
    }
    SegmentedGroup(stringResource(R.string.set_group_birthdays)) {
        linkRow("birthdays", Icons.Rounded.Cake) { open(Routes.Birthdays) }
        switchRow("birthday_reminders", s.birthdayReminders, Icons.Rounded.NotificationsActive, sub = reminderSub) { v ->
            set { it.copy(birthdayReminders = v) }
        }
        if (s.birthdayReminders) {
            menuRow("reminder_time", (6..22).map { "$it:00" }, (s.birthdayReminderHour - 6).coerceIn(0, 16), Icons.Rounded.Timer) { i ->
                set { it.copy(birthdayReminderHour = i + 6) }
                RemindersWorker.schedule(context, i + 6)
            }
        }
    }
    SegmentedGroup(stringResource(R.string.set_group_circle)) {
        switchRow("nudges", s.reachOutNudges, Icons.Rounded.Handshake) { v -> set { it.copy(reachOutNudges = v) } }
        // The Circle's reminder and "Log this?" choices.
        circleSettingRows(vm, circleCfg, s.birthdayReminders, s.reachOutNudges)
    }
    AdvancedGroup(setOf("import_sim", "export_account")) {
        linkRow("import_sim", Icons.Rounded.SimCardDownload) { open(PeopleRoutes.SimImport) }
        if (severalAccounts) item("export_account") { ExportAccountRow(vm, Icons.AutoMirrored.Rounded.CallSplit) }
    }

    importAccounts?.let { (uri, accs) ->
        ParleyDialog(
            onDismissRequest = { importAccounts = null },
            title = { Text(stringResource(R.string.set_import_into)) },
            text = {
                Column {
                    SwitchRow(stringResource(R.string.set_skip_duplicates), stringResource(R.string.set_skip_duplicates_body), skipDuplicates) { skipDuplicates = it }
                    accs.forEach { a ->
                        ListItem(headlineContent = { Text(vm.accountLabel(a)) }, colors = rowColors(), modifier = Modifier.clickable {
                            importAccounts = null
                            scope.launch {
                                // A CSV in another layout (Google, Outlook, any columns) goes to the column mapping first.
                                val preview = runCatching { vm.c.vcards.csvPreview(uri) }.getOrNull()
                                if (preview != null && !preview.parley) {
                                    MessagingInbox.csvImport = CsvImportRequest(uri, a, skipDuplicates)
                                    open(MessagingRoutes.CsvMapping)
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
internal fun HistoryPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val archiveOn = vm.c.history.prefs.state.collectAsStateWithLifecycle().value.archiveEnabled
    val retention = listOf(0, 30, 90, 180, 365)
    val retentionLabels = listOf(
        stringResource(R.string.set_forever),
        pluralStringResource(R.plurals.set_days, 30, 30),
        pluralStringResource(R.plurals.set_days, 90, 90),
        pluralStringResource(R.plurals.set_months, 6, 6),
        pluralStringResource(R.plurals.set_years, 1, 1),
    )
    // The former "Call history" sub-screen lives here now: the archive, what's kept forever and the CSV option.
    SegmentedGroup(stringResource(R.string.set_group_call_history)) {
        item("archive") { KeepFullHistoryRow(vm, Icons.Rounded.ManageHistory) }
        menuRow("retention", retentionLabels, retention.indexOf(s.callLogRetentionDays).coerceAtLeast(0), Icons.Rounded.AutoDelete) { i ->
            set { it.copy(callLogRetentionDays = retention[i]) }
        }
        if (archiveOn) keptForeverRow(vm)
        // Clear everything, unknown numbers or missed calls, with an export first.
        item("clear_history") { ClearHistoryRow(vm, open, Icons.Rounded.DeleteSweep) }
        // Deleted calls are restored where everything else is: History & undo › Calls.
        linkRow("history_details", Icons.Rounded.RestoreFromTrash) { open(Routes.journal(HistoryTab.CALLS)) }
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
        linkRow("insights", Icons.Rounded.Insights) { open(HistoryRoutes.Insights) }
        // The People card in Call insights.
        peopleCardRows(vm, circleCfg)
    }
    SegmentedGroup(stringResource(R.string.hist_export_import)) {
        linkRow("import_calls", Icons.Rounded.FileUpload) { open(HistoryRoutes.Import) }
        csvBomRow(vm)
    }
    AdvancedGroup(setOf("sim_labels")) {
        switchRow("sim_labels", s.showSimLabels, Icons.Rounded.SimCard) { v -> set { it.copy(showSimLabels = v) } }
    }
    CallHistoryNotes(vm)
}

// ---------------------------------------------------------------- Messaging

@Composable
internal fun MessagingPage(vm: AppViewModel, open: (Destination) -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val scope = rememberCoroutineScope()
    var editReplies by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SegmentedGroup {
        linkRow("quick_replies", Icons.Rounded.Quickreply, sub = s.quickReplies.joinToString(" · ")) { editReplies = true }
        item("my_details") { MyDetailsRow(vm, Icons.Rounded.Badge) }
    }
    // The record of numbers you opened chats with, and when it forgets them.
    val recorded by vm.c.messaging.lastMessaged.collectAsStateWithLifecycle()
    val recording by vm.c.messaging.recordEnabled.collectAsStateWithLifecycle()
    val expiry by vm.c.messaging.expiryDays.collectAsStateWithLifecycle()
    val messagedSub = if (recording) pluralStringResource(R.plurals.set_numbers_count, recorded.size, recorded.size) else stringResource(R.string.set_not_kept)
    SegmentedGroup(stringResource(R.string.set_group_messaged)) {
        linkRow("messaged_numbers", Icons.AutoMirrored.Rounded.Chat, sub = messagedSub) {
            open(MessagingRoutes.Messaged)
        }
        val choices = MessagedRecord.EXPIRY_CHOICES
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
    SegmentedGroup(stringResource(R.string.set_group_app_lock)) {
        switchRow("app_lock", s.appLock, Icons.Rounded.Lock) { v ->
            val act = context as? FragmentActivity
            if (act != null) AppLock.authenticate(act, res.getString(if (v) R.string.set_app_lock_turn_on else R.string.set_app_lock_turn_off)) { ok -> if (ok) set { it.copy(appLock = v) } }
        }
        if (s.appLock) {
            menuRow("lock_after", lockLabels, lockTimes.indexOf(s.lockAfterMinutes).coerceAtLeast(0), Icons.Rounded.LockClock) { i ->
                set { it.copy(lockAfterMinutes = lockTimes[i]) }
            }
        }
        switchRow("secure_screen", s.secureScreen, Icons.Rounded.VisibilityOff) { v -> set { it.copy(secureScreen = v) } }
    }
    // The family safe word, by label (WP-8).
    FamilySafetyPrivacyGroup(open)
    SegmentedGroup(stringResource(R.string.set_group_private_contacts)) {
        switchRow("hide_vault", s.hideVault, Icons.Rounded.VisibilityOff) { v -> set { it.copy(hideVault = v) } }
        switchRow("private_history", s.privateVaultHistory, Icons.Rounded.PhoneLocked) { v -> set { it.copy(privateVaultHistory = v) } }
    }
    SegmentedGroup(stringResource(R.string.set_group_your_data)) {
        linkRow("privacy_dashboard", Icons.Rounded.PrivacyTip) { open(Routes.Privacy) }
        linkRow("who_can_see", Icons.Rounded.Apps) { open(PeopleRoutes.WhoCanSee) }
        linkRow("private_names", Icons.Rounded.Badge, sub = if (pn.enabled) on else off) { open(PeopleRoutes.PrivateNames) }
    }
    AdvancedGroup(setOf("private_directory", "app_permissions", "delete_all_data")) {
        linkRow("private_directory", Icons.Rounded.PhoneLocked, sub = if (pn.directory) on else off) { open(PeopleRoutes.PrivateNames) }
        linkRow("app_permissions", Icons.Rounded.AdminPanelSettings, external = true) {
            context.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
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
    // The overdue reminder and its threshold.
    BackupReminderBanner(vm)
    val ux by vm.c.ux.state.collectAsStateWithLifecycle()
    val reminderOptions = BackupNudge.REMINDER_DAYS.map { pluralStringResource(R.plurals.ux_backup_after_days, it, it) }
    SegmentedGroup(stringResource(R.string.set_group_backups)) {
        linkRow(
            "backup", Icons.Rounded.Backup,
            sub = lastBackup,
        ) { open(Routes.Backup) }
        menuRow(
            "backup_reminder", reminderOptions, BackupNudge.REMINDER_DAYS.indexOf(ux.backupReminderDays).coerceAtLeast(0),
            Icons.Rounded.NotificationsActive,
        ) { i -> vm.c.ux.setBackupReminderDays(BackupNudge.REMINDER_DAYS[i]) }
        linkRow("sync", Icons.Rounded.Sync) { open(Routes.Sync) }
        linkRow("markdown_export", Icons.Rounded.Description) { open(Routes.Sync) }
    }
    SegmentedGroup(stringResource(R.string.set_group_undo)) {
        linkRow("journal", Icons.Rounded.RestoreFromTrash) { open(Routes.journal()) }
        linkRow("time_machine", Icons.Rounded.ManageHistory) { open(Routes.journal(HistoryTab.SNAPSHOTS)) }
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
