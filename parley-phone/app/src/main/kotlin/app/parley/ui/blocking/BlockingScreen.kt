package app.parley.ui.blocking

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.blocking.BlockingActions
import app.parley.blocking.BlockingNotifier
import app.parley.blocking.ExpectingCallTileService
import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.RuleKind
import app.parley.common.RuleTools
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.TraceCodec
import app.parley.common.blocking.PersonalReputation
import app.parley.common.spam.BuiltInPacks
import app.parley.data.GroupInfo
import app.parley.data.Permissions
import app.parley.data.db.BlockedCallEntity
import app.parley.telecom.ScreeningGuard
import app.parley.ui.common.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Situations, not mechanisms: each preset says what it's for and changes a few toggles. */
private data class Preset(val title: String, val help: String, val apply: (AppSettings) -> AppSettings)

private val PRESETS = listOf(
    Preset("Only people I know", "Strangers are silenced; they can still get through by calling twice, and numbers you called ring.") { a ->
        a.copy(repeatCallerRingsThrough = true, screening = a.screening.copy(blockNonContacts = true, blockHidden = true, defaultAction = BlockAction.SILENCE, allowDialled = true, allowAnswered = true))
    },
    Preset("Stop telemarketers", "Blocks spoofed and impossible numbers; strangers still ring.") { a ->
        a.copy(screening = a.screening.copy(blockFailedVerification = true, blockInvalid = true, blockNonContacts = false))
    },
    Preset("Quiet nights", "From 22:00 to 07:00 only contacts ring; others are silenced.") { a ->
        a.copy(screening = a.screening.copy(offHours = OffHours(enabled = true, schedule = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60), allow = OffHoursAllow.CONTACTS)))
    },
    Preset("Let everyone ring", "Turns the checks off. Your own rules and lists stay as they are.") { a ->
        a.copy(screening = a.screening.copy(blockNonContacts = false, blockHidden = false, blockInvalid = false, blockFailedVerification = false, blockNeighbourSpoofing = false, offHours = a.screening.offHours.copy(enabled = false)))
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    val system by vm.c.blocks.systemList.collectAsStateWithLifecycle()
    val log by vm.c.blocks.blockedCalls.collectAsStateWithLifecycle(emptyList())
    val lists by vm.c.lists.state.collectAsStateWithLifecycle()
    val calls by vm.c.callLog.calls.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val s = settings.screening
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggle(k: String) {
        expanded = if (k in expanded) expanded - k else expanded + k
    }
    var addNumber by remember { mutableStateOf(false) }
    var presetToApply by remember { mutableStateOf<Preset?>(null) }
    var testNumber by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    fun setScreening(f: (ScreeningSettings) -> ScreeningSettings) = scope.launch { vm.c.settings.update { it.copy(screening = f(it.screening)) } }

    val numbersPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) setScreening { it.copy(blockNeighbourSpoofing = true) } else vm.toast("Needed to know your own number")
    }
    val allowRules = rules.filter { it.kind == RuleKind.ALLOW }
    val blockRules = rules.filter { it.kind == RuleKind.BLOCK }
    val dismissed = remember { mutableStateOf(setOf<String>()) }
    val suggestions = remember(calls, rules, s.reputationSuggestions, dismissed.value) {
        if (!s.reputationSuggestions) emptyList() else PersonalReputation.suggestions(calls.orEmpty().take(1500), System.currentTimeMillis()) { n ->
            n in dismissed.value || vm.contactFor(n) != null || rules.any { r -> r.type.isNumberRule && app.parley.common.CallPolicy.ruleMatches(r, n, vm.countryIso) }
        }.take(5)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Blocking & screening") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item(key = "status") { ScreeningStatusCard(vm) }

            // B21: "Expecting a call" chip in the header.
            item(key = "snooze") {
                val left = s.snoozeUntil - now
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (left > 0) {
                        InputChip(
                            selected = true, onClick = { scope.launch { BlockingActions.snooze(vm.c, 0); ExpectingCallTileService.refresh(context) } },
                            label = { Text("Unknown callers ring · ${leftText(left)} left") },
                            leadingIcon = { Icon(Icons.Rounded.HourglassTop, null) },
                            trailingIcon = { Text("Stop", fontWeight = FontWeight.Bold) },
                        )
                    } else {
                        Text("Expecting a call?", style = MaterialTheme.typography.labelLarge)
                        listOf(30 to "30 min", 60 to "1 h", 120 to "2 h").forEach { (m, label) ->
                            AssistChip({ scope.launch { BlockingActions.snooze(vm.c, m); ExpectingCallTileService.refresh(context) } }, { Text(label) })
                        }
                    }
                }
            }

            // B23: visible emergency countdown.
            item(key = "emergency-live") {
                val ends = remember(now) { ScreeningGuard.emergencyWindowEndsAt(context) }
                if (ends != null) {
                    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Emergency, null)
                            Text("  Emergency call-back window: nothing is blocked for ${leftText(ends - now)}", Modifier.weight(1f))
                            TextButton({ ScreeningGuard.clearEmergencyWindow(context); now = System.currentTimeMillis() }) { Text("Reset") }
                        }
                    }
                }
            }

            item(key = "presets") {
                Text("Quick setups", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { pr -> AssistChip({ presetToApply = pr }, { Text(pr.title) }) }
                }
            }

            item(key = "main") {
                ToggleRow("Silence or block hidden numbers", "Private and withheld callers" + (s.hiddenSchedule?.let { " · ${it.describe()}" } ?: ""), s.blockHidden, enabled = isDefault) { v -> setScreening { it.copy(blockHidden = v) } }
                if (!isDefault) Text("Needs Parley as your phone app: Android never shows hidden callers to screening apps.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                ToggleRow("Only people I know ring", "Strict mode: numbers not in contacts are stopped, unless you allowed them" + (s.nonContactsSchedule?.let { " · ${it.describe()}" } ?: ""), s.blockNonContacts) { v -> setScreening { it.copy(blockNonContacts = v) } }
                ListItem(
                    headlineContent = { Text("When a call is stopped") },
                    supportingContent = { Column { ActionChoice(s.defaultAction, { a -> setScreening { it.copy(defaultAction = a) } }, Modifier.padding(top = 8.dp)) } },
                )
            }

            // ---- Always let through ----
            item(key = "allow") {
                CollapsibleSection(
                    "Always let through", Help.ALLOW,
                    listOfNotNull(
                        "${allowRules.size} allowed".takeIf { allowRules.isNotEmpty() },
                        "Repeat callers".takeIf { settings.repeatCallerRingsThrough },
                        "Numbers you called".takeIf { s.allowDialled },
                        "People you talked to".takeIf { s.allowAnswered },
                    ),
                    "allow" in expanded, { toggle("allow") }, Icons.Rounded.VerifiedUser,
                ) {
                    ToggleRow("Repeat callers", "Someone calling again within ${s.repeatWindowMinutes} min is probably urgent. Redials within ${s.repeatMinIntervalSeconds} s don't count (bots redial instantly). Never overrides your own block rules.", settings.repeatCallerRingsThrough) { v ->
                        scope.launch { vm.c.settings.update { it.copy(repeatCallerRingsThrough = v) } }
                    }
                    if (settings.repeatCallerRingsThrough) {
                        LabeledSlider("Window: ${s.repeatWindowMinutes} min", s.repeatWindowMinutes.toFloat(), 1f..15f, 13) { v -> setScreening { it.copy(repeatWindowMinutes = v.toInt()) } }
                        LabeledSlider("Ignore redials faster than ${s.repeatMinIntervalSeconds} s", s.repeatMinIntervalSeconds.toFloat(), 0f..60f, 11) { v -> setScreening { it.copy(repeatMinIntervalSeconds = v.toInt()) } }
                    }
                    ToggleRow("Numbers you called", "In the last ${s.dialledDays} days (the garage you rang this morning).", s.allowDialled) { v -> setScreening { it.copy(allowDialled = v) } }
                    ToggleRow("People you talked to", "Calls you answered for ${s.answeredMinSeconds} s or more in the last ${s.answeredDays} days.", s.allowAnswered) { v -> setScreening { it.copy(allowAnswered = v) } }
                    TextButton({ open(BlockingRoutes.rule(0, RuleKind.ALLOW)) }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Rounded.Add, null); Text(" Allow a number or range") }
                    allowRules.forEach { r -> RuleRow(vm, r, now) { open(BlockingRoutes.rule(r.id)) } }
                }
            }

            // ---- Block rules ----
            item(key = "block") {
                CollapsibleSection(
                    "Your block rules", Help.BLOCK,
                    listOfNotNull(
                        "${blockRules.count { it.enabled }} on".takeIf { blockRules.isNotEmpty() },
                        "${blockRules.count { it.schedule != null }} scheduled".takeIf { blockRules.any { it.schedule != null } },
                        "${blockRules.sumOf { it.hitCount }} calls stopped".takeIf { blockRules.any { it.hitCount > 0 } },
                    ),
                    "block" in expanded, { toggle("block") }, Icons.Rounded.Rule,
                ) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton({ open(BlockingRoutes.rule(0, RuleKind.BLOCK)) }) { Icon(Icons.Rounded.Add, null); Text(" Add rule") }
                    }
                    if (blockRules.isEmpty()) Text("No rules yet. Example: prefix +1 900, or pattern 0800* for all 0800 numbers.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    blockRules.forEach { r -> RuleRow(vm, r, now) { open(BlockingRoutes.rule(r.id)) } }
                }
            }

            // ---- Spam lists hero card ----
            item(key = "lists") {
                val sum = vm.c.lists.summarize(lists, now)
                val suggestion = BuiltInPacks.suggestedFor(vm.countryIso).firstOrNull { b -> lists.packs.none { it.id == b.id } && b.id !in lists.dismissedSuggestions }
                Card(Modifier.fillMaxWidth().padding(16.dp).clickable { open(BlockingRoutes.LISTS) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, null)
                            Text("  Spam lists", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            if (sum.lists == 0) "No lists yet" else
                                "${sum.lists} ${if (sum.lists == 1) "list" else "lists"} · ${"%,d".format(sum.numbers)} numbers" + (sum.updatedAt?.let { " · updated ${ago(it, now)}" } ?: ""),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (sum.stale > 0) Text("${sum.stale} out of date", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Text(Help.LISTS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (suggestion != null) {
                            Text("Suggested for your SIM: ${suggestion.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Row {
                                TextButton({ scope.launch { vm.c.lists.installBuiltIn(suggestion); vm.toast("Added: ${suggestion.name}") } }) { Text("Add") }
                                TextButton({ scope.launch { vm.c.lists.dismissSuggestion(suggestion.id) } }) { Text("No thanks") }
                            }
                        }
                    }
                }
            }

            // ---- Off hours ----
            item(key = "offhours") {
                val oh = s.offHours
                CollapsibleSection(
                    "Off hours", Help.OFF_HOURS,
                    if (oh.enabled) listOf(oh.schedule.describe(), "Only " + offHoursWho(oh), if (oh.action == BlockAction.SILENCE) "Silence" else "Reject") else listOf("Off"),
                    "offhours" in expanded, { toggle("offhours") }, Icons.Rounded.Bedtime,
                ) {
                    ToggleRow("Use off hours", null, oh.enabled) { v -> setScreening { it.copy(offHours = it.offHours.copy(enabled = v)) } }
                    ScheduleField(oh.schedule, { sc -> setScreening { it.copy(offHours = it.offHours.copy(schedule = sc ?: Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60))) } }, alwaysLabel = "All day")
                    OffHoursWho(vm, oh) { o -> setScreening { it.copy(offHours = o) } }
                    ListItem(headlineContent = { Text("Everyone else") }, supportingContent = { ActionChoice(oh.action, { a -> setScreening { it.copy(offHours = it.offHours.copy(action = a)) } }, Modifier.padding(top = 8.dp)) })
                    ToggleRow("Offer a reply", "When someone you know is silenced, a notification offers to text them \"${s.busyReplyText}\" (your SMS app opens; Parley never sends texts itself).", s.busyReply) { v -> setScreening { it.copy(busyReply = v) } }
                    if (s.busyReply) {
                        var text by remember(s.busyReplyText) { mutableStateOf(s.busyReplyText) }
                        OutlinedTextField(text, { text = it }, label = { Text("Reply text") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            trailingIcon = { if (text != s.busyReplyText && text.isNotBlank()) TextButton({ setScreening { it.copy(busyReplyText = text.trim()) } }) { Text("Save") } })
                    }
                }
            }

            // ---- More checks ----
            item(key = "more") {
                CollapsibleSection(
                    "More checks", Help.MORE,
                    listOfNotNull("Neighbour spoofing".takeIf { s.blockNeighbourSpoofing }, "Caller verification".takeIf { s.blockFailedVerification }, "Invalid numbers".takeIf { s.blockInvalid }),
                    "more" in expanded, { toggle("more") }, Icons.Rounded.Security,
                ) {
                    ToggleRow("Neighbour spoofing", "Unknown numbers that differ from yours only in the last 4 digits", s.blockNeighbourSpoofing) { v ->
                        if (v && !Permissions.has(context, Manifest.permission.READ_PHONE_NUMBERS)) numbersPermission.launch(Manifest.permission.READ_PHONE_NUMBERS)
                        else setScreening { it.copy(blockNeighbourSpoofing = v) }
                    }
                    ToggleRow("Failed caller verification", "The carrier says the caller ID is spoofed (STIR/SHAKEN, mostly US)", s.blockFailedVerification) { v -> setScreening { it.copy(blockFailedVerification = v) } }
                    ToggleRow("Numbers that can't exist", "Wrong length or unassigned for their country. Silenced by default, in case a real caller's number is unusual.", s.blockInvalid) { v -> setScreening { it.copy(blockInvalid = v) } }
                    if (s.blockInvalid) ListItem(headlineContent = { Text("Invalid numbers are") }, supportingContent = { ActionChoice(s.invalidAction, { a -> setScreening { it.copy(invalidAction = a) } }, Modifier.padding(top = 8.dp)) })
                    Text("Active", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    ToggleScheduleRow("Hidden numbers", s.hiddenSchedule) { sc -> setScreening { it.copy(hiddenSchedule = sc) } }
                    ToggleScheduleRow("Only people I know", s.nonContactsSchedule) { sc -> setScreening { it.copy(nonContactsSchedule = sc) } }
                    ToggleScheduleRow("Neighbour spoofing", s.neighbourSchedule) { sc -> setScreening { it.copy(neighbourSchedule = sc) } }
                    ToggleScheduleRow("Caller verification", s.verificationSchedule) { sc -> setScreening { it.copy(verificationSchedule = sc) } }
                    ToggleScheduleRow("Invalid numbers", s.invalidSchedule) { sc -> setScreening { it.copy(invalidSchedule = sc) } }
                }
            }

            // ---- Sounds & notifications ----
            item(key = "sounds") {
                CollapsibleSection(
                    "Sounds & notifications", Help.SOUNDS,
                    listOfNotNull(
                        "Loud for favourites".takeIf { s.ringLoudFavourites }, "Loud for repeat callers".takeIf { s.ringLoudRepeat },
                        "Blocked: ${notifyLabel(s.notifyBlocked).lowercase()}",
                    ),
                    "sounds" in expanded, { toggle("sounds") }, Icons.Rounded.MusicNote,
                ) {
                    SoundsSection(vm, s) { f -> setScreening(f) }
                }
            }

            // ---- Emergency ----
            item(key = "emergency") {
                CollapsibleSection(
                    "Emergency", Help.EMERGENCY, listOfNotNull("${s.emergencyExtras.size} extra numbers".takeIf { s.emergencyExtras.isNotEmpty() }),
                    "emergency" in expanded, { toggle("emergency") }, Icons.Rounded.Emergency,
                ) {
                    EmergencySection(vm, s) { f -> setScreening(f) }
                }
            }

            // ---- Tools ----
            item(key = "tools") {
                CollapsibleSection("Test, import & share", Help.TOOLS, emptyList(), "tools" in expanded, { toggle("tools") }, Icons.Rounded.Build) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(testNumber, { testNumber = it }, label = { Text("Test a number") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton({ BlockingDialogs.show(BlockingDialog.Test(testNumber.trim())) }, enabled = testNumber.isNotBlank()) { Text("Test") }
                    }
                    ListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.DRY_RUN) },
                        leadingContent = { Icon(Icons.Rounded.History, null) },
                        headlineContent = { Text("What would today's rules have done?") },
                        supportingContent = { Text("Replays last week's calls. Nothing is blocked, logged or sent.") },
                    )
                    ListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.TEMPLATES) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, null) },
                        headlineContent = { Text("Templates") },
                        supportingContent = { Text("Ready-made rule sets (country ranges, quiet nights), installed as a group; share yours by file or QR") },
                    )
                    ListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.TRANSFER) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, null) },
                        headlineContent = { Text("Import or share rules") },
                        supportingContent = { Text("Call Blocker, YACB, NoPhoneSpam or any CSV; share yours as a signed list") },
                    )
                }
            }

            // ---- System block list ----
            item(key = "system") {
                CollapsibleSection(
                    "Blocked numbers (system list)", "Android's own list, shared with your other phone apps. Numbers here are rejected before Parley sees them.",
                    listOf("${system.size} numbers"), "system" in expanded, { toggle("system") }, Icons.Rounded.Block,
                ) {
                    TextButton({ addNumber = true }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Rounded.Add, null); Text(" Block a number") }
                    if (!vm.c.blocks.canUseSystemList()) Text("Make Parley your default phone app to manage the system block list.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                    system.forEach { b ->
                        ListItem(
                            leadingContent = { Icon(Icons.Rounded.Block, null) },
                            headlineContent = { Text(Format.number(b.number, vm.countryIso)) },
                            trailingContent = { IconButton({ vm.unblockNumber(b.number) }) { Icon(Icons.Rounded.Delete, "Unblock") } },
                        )
                    }
                }
            }

            // ---- Blocked log ----
            item(key = "log-head") {
                app.parley.ui.contact.Section("Recently stopped by Parley")
                Text(Help.LOG, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (suggestions.isNotEmpty()) {
                    Text("Likely spam for you", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                    suggestions.forEach { sg ->
                        ListItem(
                            headlineContent = { Text(Format.number(sg.number, vm.countryIso)) },
                            supportingContent = { Text(sg.reason() + " · last ${ago(sg.lastAt, now)}") },
                            trailingContent = {
                                Row {
                                    TextButton({ dismissed.value = dismissed.value + sg.number }) { Text("Dismiss") }
                                    TextButton({ scope.launch { BlockingActions.blockNumberRule(vm.c, sg.number, "Likely spam for you"); vm.toast("Blocked") } }) { Text("Block") }
                                }
                            },
                        )
                    }
                }
                if (log.isEmpty()) Text("Nothing stopped yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextButton({ scope.launch { vm.c.blocks.clearBlockedLog() } }, Modifier.padding(horizontal = 8.dp)) { Text("Clear log") }
            }
            items(log.take(100), key = { "l" + it.id }) { e -> BlockedLogRow(vm, e) }
        }
    }

    presetToApply?.let { pr ->
        AlertDialog(
            onDismissRequest = { presetToApply = null },
            title = { Text(pr.title) },
            text = { Text(pr.help) },
            confirmButton = { TextButton({ scope.launch { vm.c.settings.update(pr.apply) }; presetToApply = null }) { Text("Use this") } },
            dismissButton = { TextButton({ presetToApply = null }) { Text("Cancel") } },
        )
    }
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

private fun offHoursWho(o: OffHours) = when (o.allow) {
    OffHoursAllow.CONTACTS -> "contacts"
    OffHoursAllow.FAVOURITES -> "favourites"
    OffHoursAllow.LABEL -> "'${o.labelTitle ?: "label"}'"
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onChange(v) })
    }
}

@Composable
private fun ToggleScheduleRow(title: String, schedule: Schedule?, onChange: (Schedule?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open = !open },
        headlineContent = { Text(title) },
        supportingContent = { Text(schedule?.describe() ?: "Always") },
    )
    if (open) ScheduleField(schedule, onChange)
}

@Composable
private fun OffHoursWho(vm: AppViewModel, oh: OffHours, onChange: (OffHours) -> Unit) {
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { runCatching { vm.c.contacts.groups() }.getOrDefault(emptyList()) } }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Who may still ring", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(oh.allow == OffHoursAllow.CONTACTS, { onChange(oh.copy(allow = OffHoursAllow.CONTACTS)) }, label = { Text("All contacts") })
            FilterChip(oh.allow == OffHoursAllow.FAVOURITES, { onChange(oh.copy(allow = OffHoursAllow.FAVOURITES)) }, label = { Text("Favourites") })
            // By title: the label in every account.
            groups.map { app.parley.common.LabelRefs.key(it.title) }.distinct().forEach { t ->
                val on = oh.allow == OffHoursAllow.LABEL && oh.labelTitle?.let { app.parley.common.LabelRefs.key(it) } == t
                FilterChip(on, { onChange(oh.copy(allow = OffHoursAllow.LABEL, labelId = null, labelTitle = t)) }, label = { Text(t) })
            }
        }
        Text("Repeat callers and numbers you allowed still get through.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SoundsSection(vm: AppViewModel, s: ScreeningSettings, set: ((ScreeningSettings) -> ScreeningSettings) -> Unit) {
    val context = LocalContext.current
    var target by remember { mutableStateOf("") }
    val pick = rememberRingtonePicker { uri -> if (target == "repeat") set { it.copy(repeatRingtone = uri) } else set { it.copy(likelySpamRingtone = uri) } }
    ToggleRow("Ring loud for favourites", "Full volume for starred contacts; your volume is put back afterwards, even if the phone restarts.", s.ringLoudFavourites) { v -> set { it.copy(ringLoudFavourites = v) } }
    ToggleRow("Ring loud for repeat callers", "Full volume when someone calls again within ${s.repeatWindowMinutes} min.", s.ringLoudRepeat) { v -> set { it.copy(ringLoudRepeat = v) } }
    ListItem(
        modifier = Modifier.clickable { target = "repeat"; pick(s.repeatRingtone) },
        headlineContent = { Text("Ringtone for repeat callers") },
        supportingContent = { Text(ringtoneTitle(context, s.repeatRingtone) ?: "Same as usual") },
        trailingContent = { if (s.repeatRingtone != null) TextButton({ set { it.copy(repeatRingtone = null) } }) { Text("Reset") } },
    )
    ListItem(
        modifier = Modifier.clickable { target = "spam"; pick(s.likelySpamRingtone) },
        headlineContent = { Text("Ringtone for likely spam") },
        supportingContent = { Text(ringtoneTitle(context, s.likelySpamRingtone) ?: "Same as usual") },
        trailingContent = { if (s.likelySpamRingtone != null) TextButton({ set { it.copy(likelySpamRingtone = null) } }) { Text("Reset") } },
    )
    Text("Label ringtones: open a label's screening options, or add an \"Always allow\" rule of type Label.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Notifications", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
    listOf(
        Triple("Blocked by your rules", s.notifyBlocked) { n: app.parley.common.NotifyLevel -> set { it.copy(notifyBlocked = n) } },
        Triple("Reported by a spam list", s.notifyReported) { n: app.parley.common.NotifyLevel -> set { it.copy(notifyReported = n) } },
        Triple("Likely spam (rang)", s.notifyLikelySpam) { n: app.parley.common.NotifyLevel -> set { it.copy(notifyLikelySpam = n) } },
    ).forEach { (title, v, change) ->
        ListItem(headlineContent = { Text(title) }, supportingContent = { NotifyChoice(v, allowDefault = false, change) })
    }
    if (s.notifyBlocked == app.parley.common.NotifyLevel.NONE) {
        Text("With blocked-call notifications off you won't notice a mistake until you check the log.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    TextButton({
        BlockingNotifier.channels(context)
        try {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } catch (_: Exception) {
        }
    }, Modifier.padding(horizontal = 8.dp)) { Text("Sound and style per channel…") }
}

@Composable
private fun EmergencySection(vm: AppViewModel, s: ScreeningSettings, set: ((ScreeningSettings) -> ScreeningSettings) -> Unit) {
    var n by remember { mutableStateOf("") }
    Text(
        "Calling one of these numbers also starts the one-hour window (your GP, your child's school), so their call-back always gets through.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
    )
    s.emergencyExtras.forEach { x ->
        ListItem(
            headlineContent = { Text(Format.number(x, vm.countryIso)) },
            trailingContent = { IconButton({ set { it.copy(emergencyExtras = it.emergencyExtras - x) } }) { Icon(Icons.Rounded.Delete, "Remove") } },
        )
    }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(n, { n = it }, label = { Text("Add a number") }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton({
            val clean = RuleTools.check(n, RuleType.EXACT, vm.countryIso)
            if (clean.error == null) set { it.copy(emergencyExtras = (it.emergencyExtras + clean.pattern).distinct()) }
            n = ""
        }, enabled = n.isNotBlank()) { Text("Add") }
    }
}

/** A rule with its hit counter ("5 calls, last 2 days ago") and on/off switch. */
@Composable
private fun RuleRow(vm: AppViewModel, r: BlockRule, now: Long, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val expiresAt = r.expiresAt
    val expired = expiresAt != null && expiresAt <= now
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(if (r.kind == RuleKind.ALLOW) Icons.Rounded.VerifiedUser else Icons.Rounded.Rule, null) },
        headlineContent = { Text(r.title) },
        supportingContent = {
            Text(
                listOfNotNull(
                    RuleTools.describe(r).takeIf { r.note != null || !r.type.isNumberRule },
                    if (r.kind == RuleKind.BLOCK) (if (r.action == BlockAction.SILENCE) "silence" else "reject") else null,
                    r.schedule?.describe(),
                    r.simId?.let { id -> vm.sims.value.firstOrNull { it.id == id }?.label ?: "one SIM" },
                    expiresAt?.let { if (expired) "expired" else "for ${leftText(it - now)}" },
                    if (r.hitCount > 0) "${r.hitCount} ${if (r.hitCount == 1) "call" else "calls"}" + (r.lastHitAt?.let { ", last ${ago(it, now)}" } ?: "") else null,
                ).joinToString(" · "),
            )
        },
        trailingContent = { Switch(r.enabled && !expired, { v -> scope.launch { vm.c.blocks.saveRule(r.copy(enabled = v, expiresAt = if (expired && v) null else r.expiresAt)) } }) },
    )
}

/** Expandable blocked-log row: the stored trace, plus "Not spam", allow for a day, report, delete (B3, B25). */
@Composable
private fun BlockedLogRow(vm: AppViewModel, e: BlockedCallEntity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        ListItem(
            modifier = Modifier.clickable { open = !open },
            headlineContent = { Text((if (e.failedOpen) "! " else "") + (e.number?.let { Format.number(it, vm.countryIso) } ?: "Private number")) },
            supportingContent = {
                Text(listOfNotNull(Format.fullDate(context, e.time), e.verdict ?: legacyReason(e.reason), e.action.lowercase().takeIf { e.action != "ALLOW" }, e.callerName).joinToString(" · "))
            },
        )
        if (open) {
            Column(Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp)) {
                val steps = TraceCodec.decode(e.trace)
                if (steps.isEmpty()) Text("No details were stored for this call (it was blocked by an older version).", style = MaterialTheme.typography.bodySmall)
                else TraceList(steps)
                val n = e.number
                if (n != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ scope.launch { BlockingActions.notSpam(vm.c, n, e.packId); vm.toast("Marked as not spam: it will always ring") } }, { Text("Not spam") })
                        AssistChip({ scope.launch { BlockingActions.allowNumber(vm.c, n, hours = 24); vm.toast("Allowed for 24 h") } }, { Text("Allow for 24 h") })
                        AssistChip({ BlockingDialogs.show(BlockingDialog.Test(n)) }, { Text("Test again") })
                        AssistChip({ BlockingDialogs.show(BlockingDialog.Report(n)) }, { Text("Report") })
                        AssistChip({ scope.launch { vm.c.blocks.deleteScreened(e.id) } }, { Text("Delete") })
                    }
                }
            }
        }
    }
}

private fun legacyReason(r: String) = when (r) {
    "HIDDEN" -> "hidden number"
    "NOT_A_CONTACT" -> "not a contact"
    "NEIGHBOUR_SPOOF" -> "neighbour spoofing"
    "VERIFICATION_FAILED" -> "failed verification"
    "SYSTEM_LIST" -> "block list"
    else -> "rule"
}
