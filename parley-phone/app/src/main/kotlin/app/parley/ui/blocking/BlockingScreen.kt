package app.parley.ui.blocking

import android.Manifest
import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.CallPolicy
import app.parley.common.RuleType
import app.parley.common.ScreeningSettings
import app.parley.data.Permissions
import app.parley.ui.common.Format
import app.parley.ui.contact.Section
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    val system by vm.c.blocks.systemList.collectAsStateWithLifecycle()
    val log by vm.c.blocks.blockedCalls.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<BlockRule?>(null) }
    var addNumber by remember { mutableStateOf(false) }
    var screenerHeld by remember { mutableStateOf(Permissions.isCallScreener(context)) }
    val s = settings.screening

    fun setScreening(f: (ScreeningSettings) -> ScreeningSettings) = scope.launch { vm.c.settings.update { it.copy(screening = f(it.screening)) } }

    val numbersPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) setScreening { it.copy(blockNeighbourSpoofing = true) } else vm.toast("Needed to know your own number")
    }
    val screeningRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        screenerHeld = Permissions.isCallScreener(context)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Blocking & screening") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item {
                Card(Modifier.padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row { Icon(Icons.Rounded.Shield, null); Text("  Works offline", style = MaterialTheme.typography.titleSmall) }
                        Text(
                            "Parley decides on your phone using your own rules. No number is ever sent anywhere, so there is no crowd-sourced spam list. " +
                                "Contacts are never blocked unless you block them yourself.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            item {
                Toggle("Block private & hidden numbers", null, s.blockHidden) { v -> setScreening { it.copy(blockHidden = v) } }
                Toggle("Block numbers not in contacts", "Strict mode: only people you know can ring", s.blockNonContacts) { v -> setScreening { it.copy(blockNonContacts = v) } }
                Toggle("Block neighbour spoofing", "Unknown numbers that differ from yours only in the last 4 digits", s.blockNeighbourSpoofing) { v ->
                    if (v && !Permissions.has(context, Manifest.permission.READ_PHONE_NUMBERS)) numbersPermission.launch(Manifest.permission.READ_PHONE_NUMBERS)
                    else setScreening { it.copy(blockNeighbourSpoofing = v) }
                }
                Toggle("Block failed caller verification", "Carrier says the caller ID is spoofed (STIR/SHAKEN, mostly US)", s.blockFailedVerification) { v ->
                    setScreening { it.copy(blockFailedVerification = v) }
                }
                ListItem(
                    headlineContent = { Text("When a call is blocked") },
                    supportingContent = {
                        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                            BlockAction.entries.forEachIndexed { i, a ->
                                SegmentedButton(s.defaultAction == a, { setScreening { it.copy(defaultAction = a) } }, SegmentedButtonDefaults.itemShape(i, 2)) {
                                    Text(if (a == BlockAction.REJECT) "Reject" else "Silence")
                                }
                            }
                        }
                    },
                )
                if (!screenerHeld) {
                    ListItem(
                        headlineContent = { Text("Screen before ringing") },
                        supportingContent = { Text("Optional: also make Parley the call-screening app so blocked calls never ring at all.") },
                        trailingContent = {
                            TextButton({
                                context.getSystemService(RoleManager::class.java)?.let { rm ->
                                    if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) screeningRole.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                                }
                            }) { Text("Enable") }
                        },
                    )
                }
            }
            item {
                Section("Your rules")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ editing = BlockRule(pattern = "", type = RuleType.PREFIX) }) { Icon(Icons.Rounded.Add, null); Text(" Add rule") }
                }
            }
            if (rules.isEmpty()) item { Text("No rules yet. Example: prefix +1 900, or pattern 0800* for all 0800 numbers.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rules, key = { "r" + it.id }) { r ->
                ListItem(
                    modifier = Modifier.clickable { editing = r },
                    leadingContent = { Icon(Icons.Rounded.Rule, null) },
                    headlineContent = { Text(r.pattern) },
                    supportingContent = {
                        Text(listOfNotNull(r.type.name.lowercase().replaceFirstChar { it.uppercase() }, if (r.action == BlockAction.SILENCE) "silence" else "reject", r.note).joinToString(" · "))
                    },
                    trailingContent = { Switch(r.enabled, { v -> scope.launch { vm.c.blocks.saveRule(r.copy(enabled = v)) } }) },
                )
            }
            item {
                Section("Blocked numbers (system list)")
                TextButton({ addNumber = true }, Modifier.padding(horizontal = 16.dp)) { Icon(Icons.Rounded.Add, null); Text(" Block a number") }
                if (!vm.c.blocks.canUseSystemList()) {
                    Text("Make Parley your default phone app to manage the system block list.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            items(system, key = { "s" + it.id }) { b ->
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Block, null) },
                    headlineContent = { Text(Format.number(b.number, vm.countryIso)) },
                    trailingContent = { IconButton({ vm.unblockNumber(b.number) }) { Icon(Icons.Rounded.Delete, "Unblock") } },
                )
            }
            item {
                Section("Recently blocked by Parley")
                if (log.isEmpty()) Text("Nothing blocked yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextButton({ scope.launch { vm.c.blocks.clearBlockedLog() } }, Modifier.padding(horizontal = 16.dp)) { Text("Clear log") }
            }
            items(log.take(100), key = { "l" + it.id }) { e ->
                ListItem(
                    headlineContent = { Text(e.number?.let { Format.number(it, vm.countryIso) } ?: "Private number") },
                    supportingContent = { Text("${Format.fullDate(context, e.time)} · ${reasonText(e.reason)} · ${e.action.lowercase()}") },
                )
            }
        }
    }

    editing?.let { r -> RuleDialog(r, onDismiss = { editing = null }, onDelete = { scope.launch { vm.c.blocks.deleteRule(r.id) }; editing = null }) { saved ->
        scope.launch { vm.c.blocks.saveRule(saved) }
        editing = null
    } }
    if (addNumber) {
        var n by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addNumber = false },
            title = { Text("Block a number") },
            text = { OutlinedTextField(n, { n = it }, label = { Text("Phone number") }, singleLine = true) },
            confirmButton = { TextButton({ if (n.isNotBlank()) vm.blockNumber(n.trim()); addNumber = false }) { Text("Block") } },
            dismissButton = { TextButton({ addNumber = false }) { Text("Cancel") } },
        )
    }
}

private fun reasonText(r: String) = when (r) {
    "HIDDEN" -> "hidden number"
    "NOT_A_CONTACT" -> "not a contact"
    "NEIGHBOUR_SPOOF" -> "neighbour spoofing"
    "VERIFICATION_FAILED" -> "failed verification"
    "SYSTEM_LIST" -> "block list"
    else -> "rule"
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!value) },
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = { Switch(value, onChange) },
    )
}

@Composable
private fun RuleDialog(rule: BlockRule, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (BlockRule) -> Unit) {
    var pattern by remember { mutableStateOf(rule.pattern) }
    var type by remember { mutableStateOf(rule.type) }
    var action by remember { mutableStateOf(rule.action) }
    var note by remember { mutableStateOf(rule.note.orEmpty()) }
    val valid = pattern.isNotBlank() && (type != RuleType.WILDCARD || CallPolicy.wildcardRegex(pattern) != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) "New rule" else "Edit rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleType.entries.forEach { t -> FilterChip(type == t, { type = t }, label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                OutlinedTextField(
                    pattern, { pattern = it }, singleLine = true,
                    label = { Text(when (type) { RuleType.EXACT -> "Number"; RuleType.PREFIX -> "Starts with"; RuleType.WILDCARD -> "Pattern" }) },
                    supportingText = {
                        Text(when (type) {
                            RuleType.EXACT -> "Matches this number in any format."
                            RuleType.PREFIX -> "e.g. +44 70 or 0900. Works for national and international forms."
                            RuleType.WILDCARD -> "* = any digits, ? = one digit. e.g. +1 855* or 0800??????"
                        })
                    },
                    isError = pattern.isNotBlank() && !valid,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlockAction.entries.forEach { a -> FilterChip(action == a, { action = a }, label = { Text(if (a == BlockAction.REJECT) "Reject" else "Silence") }) }
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton({ onSave(rule.copy(pattern = pattern.trim(), type = type, action = action, note = note.ifBlank { null })) }, enabled = valid) { Text("Save") } },
        dismissButton = {
            Row {
                if (rule.id != 0L) TextButton(onDelete) { Text("Delete") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}
