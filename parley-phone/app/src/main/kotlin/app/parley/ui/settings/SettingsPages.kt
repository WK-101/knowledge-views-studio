package app.parley.ui.settings

import android.app.NotificationManager
import android.app.role.RoleManager
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
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
    SegmentedGroup("Theme") {
        choiceRow("theme", listOf("System", "Light", "Dark"), s.themeMode.ordinal, Icons.Rounded.DarkMode) { i -> set { it.copy(themeMode = ThemeMode.entries[i]) } }
        switchRow("amoled", s.amoledBlack, Icons.Rounded.Contrast) { v -> set { it.copy(amoledBlack = v) } }
        if (Build.VERSION.SDK_INT >= 31) switchRow("dynamic_color", s.dynamicColor, Icons.Rounded.Wallpaper) { v -> set { it.copy(dynamicColor = v) } }
    }
    SegmentedGroup("Navigation bar") {
        item("nav_tabs") {
            Column {
                ListItem(
                    headlineContent = { Text(entry("nav_tabs").title) },
                    supportingContent = { Text("Drag to reorder. Links that open a hidden tab (dialling a number, a missed call) still work.") },
                    colors = rowColors(),
                )
                NavTabsEditor(s.navTabs) { next -> set { it.copy(navTabs = next, startTab = next.startTab(it.startTab)) } }
            }
        }
        val visible = s.navTabs.visible
        menuRow("start_tab", visible.map { it.label }, visible.indexOf(s.navTabs.startTab(s.startTab)).coerceAtLeast(0), Icons.Rounded.PhoneAndroid) { i ->
            set { it.copy(startTab = visible[i]) }
        }
    }
    SegmentedGroup("Lists") {
        choiceRow("density", listOf("Comfortable", "Compact"), s.density.ordinal, Icons.Rounded.DensityMedium) { i -> set { it.copy(density = ListDensity.entries[i]) } }
        switchRow("row_actions", s.contactRowActions, Icons.Rounded.TouchApp) { v -> set { it.copy(contactRowActions = v) } }
    }
    SegmentedGroup("Names") {
        menuRow("sort_names", listOf("First name", "Last name"), if (s.sortByFirstName) 0 else 1, Icons.Rounded.SortByAlpha) { i -> set { it.copy(sortByFirstName = i == 0) } }
        item("second_line") { app.parley.ui.people.SecondLineRow(vm, Icons.AutoMirrored.Rounded.ShortText) }
        item("prefer_nickname") { app.parley.ui.people.PreferNicknameRow(vm, Icons.Rounded.Badge) }
    }
}

// ---------------------------------------------------------------- Calls

@Composable
internal fun CallsPage(vm: AppViewModel, open: (String) -> Unit) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshEnvironment() }
    val unknownTonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            set { it.copy(unknownRingtone = uri?.toString()) }
        }
    }
    SegmentedGroup {
        item("default_dialer") {
            InfoRow(
                if (isDefault) "Parley is your default phone app" else "Parley is not the default phone app",
                if (isDefault) null else entry("default_dialer").summary,
                if (isDefault) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                trailing = if (isDefault) null else ({
                    TextButton({ context.getSystemService(RoleManager::class.java)?.let { role.launch(it.createRequestRoleIntent(RoleManager.ROLE_DIALER)) } }) { Text("Set") }
                }),
            )
        }
    }
    SegmentedGroup("Answering and calling") {
        choiceRow("answer_gesture", listOf("Swipe", "Tap"), s.answerGesture.ordinal, Icons.Rounded.TouchApp) { i -> set { it.copy(answerGesture = AnswerGesture.entries[i]) } }
        switchRow("confirm_call", s.confirmBeforeCall, Icons.Rounded.CheckCircle) { v -> set { it.copy(confirmBeforeCall = v) } }
        item("call_haptics") { CallHapticsRow(vm, Icons.Rounded.Vibration) }
        val toneName = s.unknownRingtone?.let { u -> runCatching { android.media.RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context) }.getOrNull() }
        linkRow("unknown_ringtone", Icons.Rounded.MusicNote, sub = toneName ?: "Same as usual") {
            unknownTonePicker.launch(
                Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.unknownRingtone?.let(Uri::parse)),
            )
        }
    }
    SegmentedGroup("SIMs and carrier") {
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
    SegmentedGroup("Feedback") {
        switchRow("keypad_tones", s.dialpadTones, Icons.Rounded.MusicNote) { v -> set { it.copy(dialpadTones = v) } }
        switchRow("keypad_vibration", s.dialpadHaptics, Icons.Rounded.Vibration) { v -> set { it.copy(dialpadHaptics = v) } }
    }
    SegmentedGroup("Keys") {
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
    SegmentedGroup {
        linkRow("blocking", Icons.Rounded.Block) { open(Routes.BLOCKING) }
        switchRow("repeat_callers", s.repeatCallerRingsThrough, Icons.Rounded.Repeat) { v -> set { it.copy(repeatCallerRingsThrough = v) } }
        switchRow("expecting_call", snoozing, Icons.Rounded.HourglassTop, sub = if (snoozing) "On: unknown callers ring for now" else entry("expecting_call").summary) { v ->
            if (v) app.parley.ui.blocking.BlockingDialogs.show(app.parley.ui.blocking.BlockingDialog.Snooze)
            else scope.launch { app.parley.blocking.BlockingActions.snooze(vm.c, 0) }
        }
    }
    SegmentedGroup("Lists and rules") {
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
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }
    var importAccounts by remember { mutableStateOf<Pair<Uri, List<AccountRef>>?>(null) }
    var skipDuplicates by remember { mutableStateOf(true) }
    var importReport by remember { mutableStateOf<ImportReport?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    val temps by vm.c.meta.temporaryContacts().collectAsStateWithLifecycle(emptyList())
    val vault by vm.c.vault.contacts.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        if (uri != null) scope.launch {
            progress = "Exporting…"
            val r = vm.c.vcards.export(uri, vm.c.contacts.contacts.value.orEmpty())
            progress = null
            vm.toast(exportMessage(r))
        }
    }
    val csvExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            progress = "Exporting…"
            val r = try { vm.c.vcards.exportCsv(uri, vm.c.contacts.contacts.value.orEmpty()) } catch (e: Exception) { VCardIO.ExportResult(0, listOf(e.message ?: "error")) }
            progress = null
            vm.toast(exportMessage(r))
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { importAccounts = uri to withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }
    }

    progress?.let { msg ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
    SegmentedGroup("Organise") {
        if (accounts.isNotEmpty()) {
            val current = accounts.indexOfFirst { it.type == s.defaultAccountType && it.name == s.defaultAccountName }.coerceAtLeast(0)
            menuRow("default_account", accounts.map { vm.accountLabel(it) }, current, Icons.Rounded.AccountCircle) { i ->
                val a = accounts[i]
                set { it.copy(defaultAccountType = a.type, defaultAccountName = a.name) }
            }
        }
        item("labels") { app.parley.ui.people.LabelsRow(vm, open, Icons.AutoMirrored.Rounded.Label) }
        val tempCount = temps.size + vault.count { it.expiresAt != null }
        linkRow("temporary_contacts", Icons.Rounded.AutoDelete, sub = if (tempCount == 0) entry("temporary_contacts").summary else "$tempCount · they delete themselves") {
            open(Routes.TEMPORARY)
        }
        linkRow("duplicates", Icons.AutoMirrored.Rounded.CallMerge) { open(Routes.DUPLICATES) }
        linkRow("health", Icons.Rounded.HealthAndSafety) { open(Routes.HEALTH) }
    }
    val severalAccounts = app.parley.ui.people.hasSeveralAccounts(vm)
    SegmentedGroup("Import and export") {
        linkRow("import_file", Icons.Rounded.FileUpload) {
            importer.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
        }
        linkRow("import_sim", Icons.Rounded.SimCardDownload) { open(PeopleRoutes.SIM_IMPORT) }
        linkRow("export_vcf", Icons.Rounded.FileDownload) { exporter.launch("contacts.vcf") }
        linkRow("export_csv", Icons.Rounded.FileDownload) { csvExporter.launch("contacts.csv") }
        if (severalAccounts) item("export_account") { app.parley.ui.people.ExportAccountRow(vm, Icons.AutoMirrored.Rounded.CallSplit) }
    }
    SegmentedGroup("Birthdays and reminders") {
        linkRow("birthdays", Icons.Rounded.Cake) { open(Routes.BIRTHDAYS) }
        switchRow("birthday_reminders", s.birthdayReminders, Icons.Rounded.NotificationsActive, sub = "A notification on the day, at ${s.birthdayReminderHour}:00") { v ->
            set { it.copy(birthdayReminders = v) }
        }
        if (s.birthdayReminders) {
            menuRow("reminder_time", (6..22).map { "$it:00" }, (s.birthdayReminderHour - 6).coerceIn(0, 16), Icons.Rounded.Timer) { i ->
                set { it.copy(birthdayReminderHour = i + 6) }
                app.parley.work.RemindersWorker.schedule(context, i + 6)
            }
        }
        switchRow("nudges", s.reachOutNudges, Icons.Rounded.Handshake) { v -> set { it.copy(reachOutNudges = v) } }
    }

    importAccounts?.let { (uri, accs) ->
        AlertDialog(
            onDismissRequest = { importAccounts = null },
            title = { Text("Import contacts into") },
            text = {
                Column {
                    SwitchRow("Skip contacts I already have", "Same number or e-mail", skipDuplicates) { skipDuplicates = it }
                    accs.forEach { a ->
                        ListItem(headlineContent = { Text(vm.accountLabel(a)) }, colors = rowColors(), modifier = Modifier.clickable {
                            importAccounts = null
                            scope.launch {
                                progress = "Importing…"
                                val report = try {
                                    vm.c.vcards.import(uri, a, skipDuplicates = skipDuplicates)
                                } catch (e: Exception) {
                                    vm.toast("Import failed: ${e.message}")
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
            dismissButton = { TextButton({ importAccounts = null }) { Text("Cancel") } },
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
    SegmentedGroup("Call history") {
        item("archive") { app.parley.ui.history.KeepFullHistoryRow(vm, open, Icons.Rounded.ManageHistory) }
        linkRow("history_details", Icons.Rounded.RestoreFromTrash) { open(HistoryRoutes.SETTINGS) }
        menuRow("retention", listOf("Forever", "30 days", "90 days", "6 months", "1 year"), retention.indexOf(s.callLogRetentionDays).coerceAtLeast(0), Icons.Rounded.AutoDelete) { i ->
            set { it.copy(callLogRetentionDays = retention[i]) }
        }
    }
    SegmentedGroup("Recents") {
        switchRow("sim_labels", s.showSimLabels, Icons.Rounded.SimCard) { v -> set { it.copy(showSimLabels = v) } }
        linkRow("insights", Icons.Rounded.Insights) { open(HistoryRoutes.INSIGHTS) }
        linkRow("import_calls", Icons.Rounded.FileUpload) { open(HistoryRoutes.IMPORT) }
    }
}

// ---------------------------------------------------------------- Messaging

@Composable
internal fun MessagingPage(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    var editReplies by remember { mutableStateOf(false) }
    SegmentedGroup {
        linkRow("quick_replies", Icons.Rounded.Quickreply, sub = s.quickReplies.joinToString(" · ")) { editReplies = true }
        item("my_details") { MyDetailsRow(vm, Icons.Rounded.Badge) }
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
    val s by vm.settings.collectAsStateWithLifecycle()
    val set = rememberSettingsSetter(vm)
    val pn by vm.c.people.privateNames.state.collectAsStateWithLifecycle()
    val lockTimes = listOf(0, 1, 5, 15, 60)
    SegmentedGroup("App lock") {
        switchRow("app_lock", s.appLock, Icons.Rounded.Lock) { v ->
            val act = context as? androidx.fragment.app.FragmentActivity
            if (act != null) app.parley.security.AppLock.authenticate(act, if (v) "Turn on app lock" else "Turn off app lock") { ok -> if (ok) set { it.copy(appLock = v) } }
        }
        if (s.appLock) {
            menuRow("lock_after", listOf("Immediately", "1 minute", "5 minutes", "15 minutes", "1 hour"), lockTimes.indexOf(s.lockAfterMinutes).coerceAtLeast(0), Icons.Rounded.LockClock) { i ->
                set { it.copy(lockAfterMinutes = lockTimes[i]) }
            }
        }
        switchRow("secure_screen", s.secureScreen, Icons.Rounded.VisibilityOff) { v -> set { it.copy(secureScreen = v) } }
    }
    SegmentedGroup("Private contacts") {
        switchRow("hide_vault", s.hideVault, Icons.Rounded.VisibilityOff) { v -> set { it.copy(hideVault = v) } }
        switchRow("private_history", s.privateVaultHistory, Icons.Rounded.PhoneLocked) { v -> set { it.copy(privateVaultHistory = v) } }
    }
    SegmentedGroup("Your data") {
        linkRow("privacy_dashboard", Icons.Rounded.PrivacyTip) { open(Routes.PRIVACY) }
        linkRow("who_can_see", Icons.Rounded.Apps) { open(PeopleRoutes.WHO_CAN_SEE) }
        linkRow("private_names", Icons.Rounded.Badge, sub = if (pn.enabled) "On" else "Off") { open(PeopleRoutes.PRIVATE_NAMES) }
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
    SegmentedGroup("Backups") {
        linkRow(
            "backup", Icons.Rounded.Backup,
            sub = if (b.lastBackupAt > 0) "Last backup ${app.parley.ui.common.Format.shortWhen(context, b.lastBackupAt)} · encrypted" else null,
        ) { open(Routes.BACKUP) }
        linkRow("sync", Icons.Rounded.Sync) { open(Routes.SYNC) }
    }
    SegmentedGroup("Undo") {
        linkRow("journal", Icons.Rounded.RestoreFromTrash) { open(Routes.JOURNAL) }
        linkRow("time_machine", Icons.Rounded.ManageHistory) { open(Routes.CHANGES) }
    }
}

// ---------------------------------------------------------------- Notifications & device

@Composable
internal fun NotificationsPage(vm: AppViewModel) {
    val context = LocalContext.current
    app.parley.ui.calltime.NotificationHealthCard(vm)
    SegmentedGroup {
        linkRow("notification_settings", Icons.Rounded.Notifications, external = true) {
            context.startSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        val fullScreenOff = Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()
        linkRow(
            "full_screen", if (fullScreenOff) Icons.Rounded.Warning else Icons.Rounded.Fullscreen,
            sub = if (fullScreenOff) "Currently off: calls may only show as a notification" else "On: incoming calls show over the lock screen",
            external = true,
        ) {
            if (Build.VERSION.SDK_INT >= 34) {
                context.startSafely(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName)))
            } else {
                context.startSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        linkRow("battery", Icons.Rounded.BatteryAlert, sub = "Some phones delay calls for optimised apps. Set Parley to “Unrestricted”.", external = true) {
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
internal fun AboutPage(open: (String) -> Unit) {
    val context = LocalContext.current
    SegmentedGroup {
        item("version") {
            InfoRow("Parley ${BuildConfigInfo.versionName(context)}", entry("version").summary, Icons.Rounded.Info, trailing = {
                androidx.compose.material3.Icon(Icons.Rounded.CheckCircle, "No internet access", tint = CallColors.Accept)
            })
        }
        linkRow("diagnostics", Icons.Rounded.BugReport, sub = "App version, device and settings, with numbers masked. Nothing is sent: you choose where it goes.") {
            open(PeopleRoutes.DIAGNOSTICS)
        }
    }
    Text(
        "Parley has no internet permission: Android itself stops it from sending anything anywhere.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}
