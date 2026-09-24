package app.parley.ui.blocking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.LineType
import app.parley.common.NotifyLevel
import app.parley.common.RuleKind
import app.parley.common.RuleTools
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.lineTypeLabel
import app.parley.data.DryRun
import app.parley.data.GroupInfo
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun typeLabel(t: RuleType) = when (t) {
    RuleType.EXACT -> "Number"
    RuleType.PREFIX -> "Starts with"
    RuleType.WILDCARD -> "Pattern"
    RuleType.CALLER_NAME -> "Caller name"
    RuleType.REGION -> "Country"
    RuleType.NOT_MY_REGION -> "Other countries"
    RuleType.LINE_TYPE -> "Line type"
    RuleType.LABEL -> "Label"
}

private val LINE_TYPES = listOf(LineType.VOIP, LineType.PREMIUM_RATE, LineType.SHARED_COST, LineType.TOLL_FREE, LineType.UAN, LineType.PERSONAL_NUMBER, LineType.MOBILE, LineType.FIXED_LINE)

/**
 * Full rule editor (B1, B9, B16, B17, B18, B20, B24, B6): allow or block, what to match with a live preview,
 * SIM, schedule, notification, ringtone, expiry, and "try it on last week" before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(vm: AppViewModel, ruleId: Long, initial: BlockRule, back: () -> Unit) {
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(rules) { rules.firstOrNull { it.id == ruleId } }
    var loadedFrom by remember { mutableStateOf<Long?>(null) }
    var r by remember { mutableStateOf(initial) }
    LaunchedEffect(existing) {
        if (existing != null && loadedFrom != existing.id) {
            r = existing
            loadedFrom = existing.id
        }
    }
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    LaunchedEffect(r.type) { if (r.type == RuleType.LABEL && groups.isEmpty()) groups = withContext(Dispatchers.IO) { vm.c.contacts.groups() } }
    val checked = remember(r.pattern, r.type) { RuleTools.check(r.pattern, r.type, vm.countryIso) }
    val preview = remember(checked.pattern, r.type) { if (checked.error == null && r.type.isNumberRule) RuleTools.preview(r.copy(pattern = checked.pattern), vm.countryIso) else emptyList() }
    var dry by remember { mutableStateOf<DryRun?>(null) }
    var dryRunning by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pickTone = rememberRingtonePicker { r = r.copy(ringtone = it) }
    val allow = r.kind == RuleKind.ALLOW

    fun save() {
        scope.launch {
            vm.c.blocks.saveRule(r.copy(pattern = checked.pattern, note = r.note?.trim()?.ifBlank { null }))
            back()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (ruleId == 0L) "New rule" else "Edit rule") },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = {
                if (ruleId != 0L) IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete rule") }
                TextButton(::save, enabled = checked.error == null) { Text("Save") }
            },
        )
    }) { p ->
        Column(Modifier.padding(p).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(RuleKind.BLOCK to "Block", RuleKind.ALLOW to "Always allow").forEachIndexed { i, (k, label) ->
                    SegmentedButton(r.kind == k, { r = r.copy(kind = k) }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Text(
                if (allow) "Allowed calls ring even in strict mode and beat every block rule. Only emergencies and your contacts come first."
                else "Calls from your contacts are never blocked by these rules (label rules are the exception: they're about contacts).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Match", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RuleType.entries.forEach { t -> FilterChip(r.type == t, { r = r.copy(type = t, pattern = if (t.isNumberRule == r.type.isNumberRule) r.pattern else "") }, label = { Text(typeLabel(t)) }) }
            }
            when (r.type) {
                RuleType.EXACT, RuleType.PREFIX, RuleType.WILDCARD, RuleType.CALLER_NAME, RuleType.REGION -> OutlinedTextField(
                    r.pattern, { r = r.copy(pattern = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            when (r.type) {
                                RuleType.EXACT -> "Phone number"
                                RuleType.PREFIX -> "Starts with"
                                RuleType.WILDCARD -> "Pattern"
                                RuleType.CALLER_NAME -> "Name contains"
                                else -> "Country codes"
                            },
                        )
                    },
                    supportingText = {
                        Text(
                            checked.error ?: when (r.type) {
                                RuleType.EXACT -> "Matches this number however it's written."
                                RuleType.PREFIX -> "e.g. +44 70 or 0900. Works for national and international forms."
                                RuleType.WILDCARD -> "* = any digits, ? = one digit. e.g. +1 855* or 0800??????"
                                RuleType.CALLER_NAME -> "The name your carrier sends (CNAP), e.g. \"Survey\". Not every carrier sends one."
                                else -> "Two letters, comma separated, e.g. GB, IE"
                            },
                        )
                    },
                    isError = r.pattern.isNotBlank() && checked.error != null,
                )
                RuleType.NOT_MY_REGION -> Text("Matches every number from outside ${vm.countryIso}.", style = MaterialTheme.typography.bodyMedium)
                RuleType.LINE_TYPE -> {
                    val selected = r.pattern.split(',').filter { it.isNotBlank() }.toSet()
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LINE_TYPES.forEach { lt ->
                            val on = lt.name in selected
                            FilterChip(on, { r = r.copy(pattern = (if (on) selected - lt.name else selected + lt.name).joinToString(",")) }, label = { Text(lineTypeLabel(lt.name)) })
                        }
                    }
                    Text("Worked out offline from the number itself (libphonenumber). Mobile and fixed lines can't always be told apart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RuleType.LABEL -> {
                    if (groups.isEmpty()) Text("No labels yet. Create one in Contacts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groups.distinctBy { it.title }.forEach { g -> FilterChip(r.pattern == g.id.toString(), { r = r.copy(pattern = g.id.toString(), label = g.title) }, label = { Text(g.title) }) }
                    }
                    Text(
                        if (allow) "Label rules with a ringtone give these contacts their own sound." else "Everyone in this label is blocked, even though they're contacts. Labels sync with your contacts account.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (checked.error == null && r.type.isNumberRule && r.pattern.isNotBlank()) {
                if (checked.pattern != r.pattern.trim()) Text("Saved as ${checked.pattern}", style = MaterialTheme.typography.bodySmall)
                if (preview.isNotEmpty()) Text("Will match " + preview.joinToString(", "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            checked.warnings.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text("  $w", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }

            if (!allow) {
                Text("What happens", style = MaterialTheme.typography.titleSmall)
                ActionChoice(r.action, { r = r.copy(action = it) })
                Text("Silence lets it ring quietly and shows it as a missed call; reject ends it at once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (sims.size >= 2) {
                Text("SIM", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(r.simId == null, { r = r.copy(simId = null) }, label = { Text("Any SIM") })
                    sims.forEach { s -> FilterChip(r.simId == s.id, { r = r.copy(simId = s.id) }, label = { Text(s.label) }) }
                }
                if (r.simId != null && !isDefault) {
                    Text(
                        "Unavailable in screening-only mode: Android doesn't tell screening apps which SIM a call came in on. Make Parley your phone app to use this.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text("When", style = MaterialTheme.typography.titleSmall)
            ScheduleField(r.schedule, { r = r.copy(schedule = it) })

            if (allow) {
                Text("For how long", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val now = remember { System.currentTimeMillis() }
                    FilterChip(r.expiresAt == null, { r = r.copy(expiresAt = null) }, label = { Text("Always") })
                    FilterChip(r.expiresAt != null && r.expiresAt!! - now <= 25 * 3_600_000L, { r = r.copy(expiresAt = now + 24 * 3_600_000L) }, label = { Text("24 h") })
                    FilterChip(r.expiresAt != null && r.expiresAt!! - now > 25 * 3_600_000L, { r = r.copy(expiresAt = now + 7 * 86_400_000L) }, label = { Text("7 days") })
                }
                r.expiresAt?.let { Text("Until ${Format.fullDate(context, it)}", style = MaterialTheme.typography.bodySmall) }
                Text("Ringtone", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ pickTone(r.ringtone) }) { Text(ringtoneTitle(context, r.ringtone) ?: "Same as usual") }
                    if (r.ringtone != null) TextButton({ r = r.copy(ringtone = null) }) { Text("Reset") }
                }
            } else {
                Text("Notify me", style = MaterialTheme.typography.titleSmall)
                NotifyChoice(r.notify, allowDefault = true) { r = r.copy(notify = it) }
            }

            OutlinedTextField(r.note.orEmpty(), { r = r.copy(note = it) }, label = { Text("Name (optional)") }, placeholder = { Text("e.g. Telemarketing") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            if (!allow && checked.error == null && (r.pattern.isNotBlank() || r.type == RuleType.NOT_MY_REGION)) {
                OutlinedButton({
                    dryRunning = true
                    scope.launch {
                        val calls = vm.c.callLog.calls.value.orEmpty()
                        dry = runCatching { vm.c.screener.dryRun(calls, 7, candidateRule = r.copy(id = -1, pattern = checked.pattern, enabled = true, hitCount = 0)) }.getOrNull()
                        dryRunning = false
                    }
                }, enabled = !dryRunning) { Text(if (dryRunning) "Checking last week…" else "Try it on last week's calls") }
                dry?.let { d ->
                    val added = d.added
                    Text(
                        if (added.isEmpty()) "It wouldn't have blocked any call in the last 7 days."
                        else "It would have blocked ${added.size} ${if (added.size == 1) "call" else "calls"} in the last 7 days: " +
                            added.take(5).joinToString(", ") { Format.number(it.call.number, vm.countryIso) } + if (added.size > 5) "…" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Nothing was blocked, logged or counted: this only replays your call history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (existing != null && existing.hitCount > 0) {
                Text("${existing.hitCount} ${if (existing.hitCount == 1) "call" else "calls"}" + (existing.lastHitAt?.let { ", last ${ago(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            Button(::save, enabled = checked.error == null, modifier = Modifier.fillMaxWidth()) { Text("Save rule") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this rule?") },
            confirmButton = { TextButton({ scope.launch { vm.c.blocks.deleteRule(ruleId); back() } }) { Text("Delete") } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Defaults for a new rule from the route. */
fun newRule(kind: RuleKind, type: RuleType, pattern: String) = BlockRule(
    pattern = pattern, type = type, kind = kind, action = BlockAction.REJECT, notify = NotifyLevel.DEFAULT,
    schedule = null as Schedule?,
)
