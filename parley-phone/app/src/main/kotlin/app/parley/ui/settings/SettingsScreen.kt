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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.BuildConfigInfo
import app.parley.common.AnswerGesture
import app.parley.common.AppSettings
import app.parley.common.ListDensity
import app.parley.common.StartTab
import app.parley.common.ThemeMode
import app.parley.common.vcard.ImportReport
import app.parley.data.AccountRef
import app.parley.data.VCardIO
import app.parley.ui.CallColors
import app.parley.ui.Routes
import app.parley.ui.contact.Section
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    var editReplies by remember { mutableStateOf(false) }
    var importAccounts by remember { mutableStateOf<Pair<Uri, List<AccountRef>>?>(null) }
    var skipDuplicates by remember { mutableStateOf(true) }
    var importReport by remember { mutableStateOf<ImportReport?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }

    fun set(f: (AppSettings) -> AppSettings) = scope.launch { vm.c.settings.update(f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }

    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshEnvironment() }
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
    val unknownTonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = res.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            set { it.copy(unknownRingtone = uri?.toString()) }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { importAccounts = uri to withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            if (progress != null) item { LinearProgressIndicator(Modifier.padding(16.dp)); Text(progress!!, Modifier.padding(horizontal = 16.dp)) }
            item {
                ListItem(
                    leadingContent = { Icon(if (isDefault) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (isDefault) CallColors.Accept else MaterialTheme.colorScheme.error) },
                    headlineContent = { Text(if (isDefault) "Parley is your default phone app" else "Parley is not the default phone app") },
                    supportingContent = { if (!isDefault) Text("Needed to show calls, manage blocking and the call log.") },
                    trailingContent = {
                        if (!isDefault) TextButton({
                            context.getSystemService(RoleManager::class.java)?.let { role.launch(it.createRequestRoleIntent(RoleManager.ROLE_DIALER)) }
                        }) { Text("Set") }
                    },
                )
            }

            item { Section("Appearance") }
            item {
                Choice("Theme", ThemeMode.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, s.themeMode.ordinal) { i -> set { it.copy(themeMode = ThemeMode.entries[i]) } }
                SwitchRow("Pure black dark theme", "Saves power on OLED screens", s.amoledBlack) { v -> set { it.copy(amoledBlack = v) } }
                if (Build.VERSION.SDK_INT >= 31) SwitchRow("Wallpaper colours", "Material You dynamic colour", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
                Choice("List density", listOf("Comfortable", "Compact"), s.density.ordinal) { i -> set { it.copy(density = ListDensity.entries[i]) } }
                MenuRow("Open on", listOf("Favorites", "Recents", "Contacts", "Keypad"), s.startTab.ordinal) { i -> set { it.copy(startTab = StartTab.entries[i]) } }
                SwitchRow("Call & message buttons on contacts", "Tapping a contact still opens it", s.contactRowActions) { v -> set { it.copy(contactRowActions = v) } }
                MenuRow("Sort and show names by", listOf("First name", "Last name"), if (s.sortByFirstName) 0 else 1) { i -> set { it.copy(sortByFirstName = i == 0) } }
            }

            item { Section("Calls") }
            item {
                Choice("Answer incoming calls by", listOf("Swipe", "Tap"), s.answerGesture.ordinal) { i -> set { it.copy(answerGesture = AnswerGesture.entries[i]) } }
                SwitchRow("Confirm before calling", "Avoids accidental calls from lists and search", s.confirmBeforeCall) { v -> set { it.copy(confirmBeforeCall = v) } }
                SwitchRow("Keypad tones", null, s.dialpadTones) { v -> set { it.copy(dialpadTones = v) } }
                SwitchRow("Keypad vibration", null, s.dialpadHaptics) { v -> set { it.copy(dialpadHaptics = v) } }
                SwitchRow("Show SIM in call history", "Only when two SIMs are active", s.showSimLabels) { v -> set { it.copy(showSimLabels = v) } }
                LinkRow("Quick reply messages", s.quickReplies.joinToString(" · ")) { editReplies = true }
                LinkRow("Speed dial", "Long-press 2–9 on the keypad") { open(Routes.SPEED_DIAL) }
                LinkRow("Blocking & screening", null) { open(Routes.BLOCKING) }
                SwitchRow("Let repeat callers through", "An unknown number blocked earlier rings if it calls again within 3 minutes", s.repeatCallerRingsThrough) { v -> set { it.copy(repeatCallerRingsThrough = v) } }
                val toneName = s.unknownRingtone?.let { u -> runCatching { android.media.RingtoneManager.getRingtone(context, Uri.parse(u))?.getTitle(context) }.getOrNull() }
                LinkRow("Ringtone for unknown callers", toneName ?: "Same as usual") {
                    unknownTonePicker.launch(
                        Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, s.unknownRingtone?.let(Uri::parse)),
                    )
                }
                MenuRow(
                    "Keep call history", listOf("Forever", "30 days", "90 days", "6 months", "1 year"),
                    listOf(0, 30, 90, 180, 365).indexOf(s.callLogRetentionDays).coerceAtLeast(0),
                ) { i -> set { it.copy(callLogRetentionDays = listOf(0, 30, 90, 180, 365)[i]) } }
                LinkRow("SIM & calling accounts", "Default SIM, Wi-Fi calling (system settings)") {
                    runCatching { context.startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)) }
                }
                LinkRow("Call forwarding, waiting & voicemail", "Carrier settings (system)") {
                    runCatching { context.startActivity(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS)) }
                }
            }

            item { Section("Contacts") }
            item {
                val current = accounts.indexOfFirst { it.type == s.defaultAccountType && it.name == s.defaultAccountName }.coerceAtLeast(0)
                MenuRow("Save new contacts to", accounts.map { it.displayLabel }, current) { i ->
                    val a = accounts[i]
                    set { it.copy(defaultAccountType = a.type, defaultAccountName = a.name) }
                }
                LinkRow("Import from .vcf or .csv file", null) {
                    importer.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "text/csv", "text/comma-separated-values", "application/octet-stream", "*/*"))
                }
                LinkRow("Export all to .vcf file", "Plain-text backup you control") { exporter.launch("contacts.vcf") }
                LinkRow("Export all to .csv file", "For spreadsheets") { csvExporter.launch("contacts.csv") }
                LinkRow("Find & merge duplicates", null) { open(Routes.DUPLICATES) }
                LinkRow("Contact health check", "Fix numbers without country code, empty and stale contacts") { open(Routes.HEALTH) }
                LinkRow("Birthdays & dates", null) { open(Routes.BIRTHDAYS) }
                SwitchRow("Birthday reminders", "A notification on the day, at ${s.birthdayReminderHour}:00", s.birthdayReminders) { v -> set { it.copy(birthdayReminders = v) } }
                if (s.birthdayReminders) {
                    MenuRow("Reminder time", (6..22).map { "$it:00" }, (s.birthdayReminderHour - 6).coerceIn(0, 16)) { i ->
                        set { it.copy(birthdayReminderHour = i + 6) }
                        app.parley.work.RemindersWorker.schedule(context, i + 6)
                    }
                }
                SwitchRow("Keep-in-touch nudges", "For contacts where you set a reminder", s.reachOutNudges) { v -> set { it.copy(reachOutNudges = v) } }
            }

            item { Section("Security") }
            item {
                SwitchRow("App lock", "Fingerprint, face or screen lock to open Parley. Incoming calls always show.", s.appLock) { v ->
                    val act = context as? androidx.fragment.app.FragmentActivity
                    if (act != null) app.parley.security.AppLock.authenticate(act, if (v) "Turn on app lock" else "Turn off app lock") { ok -> if (ok) set { it.copy(appLock = v) } }
                }
                if (s.appLock) {
                    MenuRow("Lock again after", listOf("Immediately", "1 minute", "5 minutes", "15 minutes", "1 hour"), listOf(0, 1, 5, 15, 60).indexOf(s.lockAfterMinutes).coerceAtLeast(0)) { i ->
                        set { it.copy(lockAfterMinutes = listOf(0, 1, 5, 15, 60)[i]) }
                    }
                }
                SwitchRow("Hide screen content", "Blocks screenshots and hides Parley in the recent-apps view", s.secureScreen) { v -> set { it.copy(secureScreen = v) } }
                SwitchRow("Hide private contacts", "Discreet mode: private contacts and their calls disappear from lists and search", s.hideVault) { v -> set { it.copy(hideVault = v) } }
                SwitchRow("Private call history", "Calls with private contacts are moved out of the system call log", s.privateVaultHistory) { v -> set { it.copy(privateVaultHistory = v) } }
            }

            item { Section("Privacy & device") }
            item {
                LinkRow("Privacy dashboard", "What Parley can access and why") { open(Routes.PRIVACY) }
                val nm = context.getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
                    LinkRow("Allow full-screen incoming calls", "Currently off: calls may only show as a notification") {
                        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))) }
                    }
                }
                LinkRow("Battery optimisation", "Some phones delay calls for optimised apps. Set Parley to “Unrestricted”.") {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
                if (Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true) || Build.MANUFACTURER.equals("POCO", true)) {
                    LinkRow("Xiaomi: lock screen & pop-up permissions", "Allow “Show on lock screen” and “Display pop-up windows while running in background”") {
                        val miui = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
                        runCatching { context.startActivity(miui) }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                        }
                    }
                }
                LinkRow("App permissions (system)", null) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                }
            }

            item { Section("About") }
            item {
                ListItem(
                    headlineContent = { Text("Parley ${BuildConfigInfo.versionName(context)}") },
                    supportingContent = { Text("Free and open source (GPL-3.0). No internet access, no ads, no trackers, no accounts.") },
                )
            }
        }
    }

    if (editReplies) {
        val items = remember { mutableStateListOf<String>().apply { addAll(s.quickReplies) } }
        AlertDialog(
            onDismissRequest = { editReplies = false },
            title = { Text("Quick replies") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.indices.forEach { i -> OutlinedTextField(items[i], { items[i] = it }, singleLine = true) }
                }
            },
            confirmButton = { TextButton({ set { it.copy(quickReplies = items.filter { t -> t.isNotBlank() }) }; editReplies = false }) { Text("Save") } },
            dismissButton = { TextButton({ set { it.copy(quickReplies = AppSettings.DEFAULT_QUICK_REPLIES) }; editReplies = false }) { Text("Reset") } },
        )
    }
    importAccounts?.let { (uri, accs) ->
        AlertDialog(
            onDismissRequest = { importAccounts = null },
            title = { Text("Import contacts into") },
            text = {
                Column {
                    SwitchRow("Skip contacts I already have", "Same number or e-mail", skipDuplicates) { skipDuplicates = it }
                    accs.forEach { a ->
                        ListItem(headlineContent = { Text(a.displayLabel) }, modifier = Modifier.clickable {
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

private fun exportMessage(r: VCardIO.ExportResult): String =
    "Exported ${r.exported} contacts" + if (r.failures.isEmpty()) "" else " · ${r.failures.size} failed: ${r.failures.first()}"

/** What an import did: counts, then every failed card with its reason, then fields that had no place. */
@Composable
private fun ImportReportDialog(report: ImportReport, onDismiss: () -> Unit) {
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

@Composable
fun SwitchRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!value) },
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = { Switch(value, onChange) },
    )
}

@Composable
fun LinkRow(title: String, sub: String?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                options.forEachIndexed { i, o ->
                    SegmentedButton(selected == i, { onPick(i) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(o) }
                }
            }
        },
    )
}

@Composable
private fun MenuRow(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open = true },
        headlineContent = { Text(title) },
        supportingContent = { Text(options.getOrElse(selected) { "" }) },
        trailingContent = {
            DropdownMenu(open, { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, onClick = { open = false; onPick(i) }) }
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
