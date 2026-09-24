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
import app.parley.data.AccountRef
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
    var progress by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<List<AccountRef>>(emptyList()) }

    fun set(f: (AppSettings) -> AppSettings) = scope.launch { vm.c.settings.update(f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { accounts = withContext(Dispatchers.IO) { vm.c.contacts.accounts() } }

    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshEnvironment() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        if (uri != null) scope.launch {
            progress = "Exporting…"
            val n = vm.c.vcards.export(uri, vm.c.contacts.contacts.value.orEmpty())
            progress = null
            vm.toast("Exported $n contacts")
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
                LinkRow("Import from .vcf file", null) { importer.launch(arrayOf("text/x-vcard", "text/vcard", "text/directory", "application/octet-stream", "*/*")) }
                LinkRow("Export all to .vcf file", "Plain-text backup you control") { exporter.launch("contacts.vcf") }
                LinkRow("Find & merge duplicates", null) { open(Routes.DUPLICATES) }
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
                    accs.forEach { a ->
                        ListItem(headlineContent = { Text(a.displayLabel) }, modifier = Modifier.clickable {
                            importAccounts = null
                            scope.launch {
                                progress = "Importing…"
                                val n = try { vm.c.vcards.import(uri, a) } catch (e: Exception) { vm.toast("Import failed: ${e.message}"); 0 }
                                progress = null
                                vm.toast("Imported $n contacts")
                            }
                        })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ importAccounts = null }) { Text("Cancel") } },
        )
    }
}

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
