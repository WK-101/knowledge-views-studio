package app.parley.ui.blocking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingActions
import app.parley.blocking.BlockingNotifier
import app.parley.blocking.BlockingText
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
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Situations, not mechanisms: each preset says what it's for and changes a few toggles. */
private data class Preset(@StringRes val title: Int, @StringRes val help: Int, val apply: (AppSettings) -> AppSettings)

private val PRESETS = listOf(
    Preset(R.string.blk_preset_known, R.string.blk_preset_known_help) { a ->
        a.copy(repeatCallerRingsThrough = true, screening = a.screening.copy(blockNonContacts = true, blockHidden = true, defaultAction = BlockAction.SILENCE, allowDialled = true, allowAnswered = true))
    },
    Preset(R.string.blk_preset_telemarketers, R.string.blk_preset_telemarketers_help) { a ->
        a.copy(screening = a.screening.copy(blockFailedVerification = true, blockInvalid = true, blockNonContacts = false))
    },
    Preset(R.string.blk_preset_nights, R.string.blk_preset_nights_help) { a ->
        a.copy(screening = a.screening.copy(offHours = OffHours(enabled = true, schedule = Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60), allow = OffHoursAllow.CONTACTS)))
    },
    Preset(R.string.blk_preset_everyone, R.string.blk_preset_everyone_help) { a ->
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
        if (ok) setScreening { it.copy(blockNeighbourSpoofing = true) } else vm.toast(context.getString(R.string.blk_need_own_number))
    }
    val allowRules = rules.filter { it.kind == RuleKind.ALLOW }
    val blockRules = rules.filter { it.kind == RuleKind.BLOCK }
    val dismissed = remember { mutableStateOf(setOf<String>()) }
    val suggestions = remember(calls, rules, s.reputationSuggestions, dismissed.value) {
        if (!s.reputationSuggestions) emptyList() else PersonalReputation.suggestions(calls.orEmpty().take(1500), System.currentTimeMillis(), countryOf = { e -> app.parley.data.PhoneEnv.countryIso(context, e.accountId) }) { n ->
            n in dismissed.value || vm.contactFor(n) != null || rules.any { r -> r.type.isNumberRule && app.parley.common.CallPolicy.ruleMatches(r, n, vm.countryIso) }
        }.take(5)
    }

    // U7: scroll-linked top-bar tint.
    val barScroll = androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.blk_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } }, scrollBehavior = barScroll)
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
                            label = { Text(stringResource(R.string.blk_snooze_active, leftText(context, left))) },
                            leadingIcon = { Icon(Icons.Rounded.HourglassTop, null) },
                            trailingIcon = { Text(stringResource(R.string.blk_stop), fontWeight = FontWeight.Bold) },
                        )
                    } else {
                        Text(stringResource(R.string.blk_expecting_question), style = MaterialTheme.typography.labelLarge)
                        snoozeChoices().forEach { (m, label) ->
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
                            Text("  " + stringResource(R.string.blk_emergency_window, leftText(context, ends - now)), Modifier.weight(1f))
                            TextButton({ ScreeningGuard.clearEmergencyWindow(context); now = System.currentTimeMillis() }) { Text(stringResource(R.string.set_reset)) }
                        }
                    }
                }
            }

            item(key = "presets") {
                Text(stringResource(R.string.blk_quick_setups), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRESETS.forEach { pr -> AssistChip({ presetToApply = pr }, { Text(stringResource(pr.title)) }) }
                }
            }

            item(key = "main") { BlockingCard {
                ToggleRow(stringResource(R.string.blk_hidden), stringResource(R.string.blk_hidden_help) + (s.hiddenSchedule?.let { " · ${BlockingText.schedule(context, it)}" } ?: ""), s.blockHidden, enabled = isDefault) { v -> setScreening { it.copy(blockHidden = v) } }
                if (!isDefault) Text(stringResource(R.string.blk_hidden_needs_default), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                ToggleRow(stringResource(R.string.blk_non_contacts), stringResource(R.string.blk_non_contacts_help) + (s.nonContactsSchedule?.let { " · ${BlockingText.schedule(context, it)}" } ?: ""), s.blockNonContacts) { v -> setScreening { it.copy(blockNonContacts = v) } }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.blk_when_stopped)) },
                    supportingContent = { Column { ActionChoice(s.defaultAction, { a -> setScreening { it.copy(defaultAction = a) } }, Modifier.padding(top = 8.dp)) } },
                )
            } }

            // ---- Always let through ----
            item(key = "allow") {
                CollapsibleSection(
                    stringResource(R.string.blk_allow_section), stringResource(Help.ALLOW),
                    listOfNotNull(
                        pluralStringResource(R.plurals.blk_sum_allowed, allowRules.size, allowRules.size).takeIf { allowRules.isNotEmpty() },
                        stringResource(R.string.blk_repeat_callers).takeIf { settings.repeatCallerRingsThrough },
                        stringResource(R.string.blk_numbers_you_called).takeIf { s.allowDialled },
                        stringResource(R.string.blk_people_you_talked_to).takeIf { s.allowAnswered },
                    ),
                    "allow" in expanded, { toggle("allow") }, Icons.Rounded.VerifiedUser,
                ) {
                    ToggleRow(stringResource(R.string.blk_repeat_callers), stringResource(R.string.blk_repeat_callers_help, s.repeatWindowMinutes, s.repeatMinIntervalSeconds), settings.repeatCallerRingsThrough) { v ->
                        scope.launch { vm.c.settings.update { it.copy(repeatCallerRingsThrough = v) } }
                    }
                    if (settings.repeatCallerRingsThrough) {
                        LabeledSlider(stringResource(R.string.blk_repeat_window, s.repeatWindowMinutes), s.repeatWindowMinutes.toFloat(), 1f..15f, 13) { v -> setScreening { it.copy(repeatWindowMinutes = v.toInt()) } }
                        LabeledSlider(stringResource(R.string.blk_repeat_min_interval, s.repeatMinIntervalSeconds), s.repeatMinIntervalSeconds.toFloat(), 0f..60f, 11) { v -> setScreening { it.copy(repeatMinIntervalSeconds = v.toInt()) } }
                    }
                    ToggleRow(stringResource(R.string.blk_numbers_you_called), pluralStringResource(R.plurals.blk_numbers_you_called_help, s.dialledDays, s.dialledDays), s.allowDialled) { v -> setScreening { it.copy(allowDialled = v) } }
                    ToggleRow(stringResource(R.string.blk_people_you_talked_to), pluralStringResource(R.plurals.blk_people_you_talked_to_help, s.answeredDays, s.answeredMinSeconds, s.answeredDays), s.allowAnswered) { v -> setScreening { it.copy(allowAnswered = v) } }
                    TextButton({ open(BlockingRoutes.rule(0, RuleKind.ALLOW)) }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Rounded.Add, null); Text(" " + stringResource(R.string.blk_allow_number_or_range)) }
                    allowRules.forEach { r -> RuleRow(vm, r, now) { open(BlockingRoutes.rule(r.id)) } }
                }
            }

            // ---- Block rules ----
            item(key = "block") {
                CollapsibleSection(
                    stringResource(R.string.blk_your_rules), stringResource(Help.BLOCK),
                    listOfNotNull(
                        stringResource(R.string.blk_sum_on, blockRules.count { it.enabled }).takeIf { blockRules.isNotEmpty() },
                        stringResource(R.string.blk_sum_scheduled, blockRules.count { it.schedule != null }).takeIf { blockRules.any { it.schedule != null } },
                        blockRules.sumOf { it.hitCount }.let { n -> pluralStringResource(R.plurals.blk_sum_calls_stopped, n, n) }.takeIf { blockRules.any { it.hitCount > 0 } },
                    ),
                    "block" in expanded, { toggle("block") }, Icons.Rounded.Rule,
                ) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton({ open(BlockingRoutes.rule(0, RuleKind.BLOCK)) }) { Icon(Icons.Rounded.Add, null); Text(" " + stringResource(R.string.blk_add_rule)) }
                    }
                    if (blockRules.isEmpty()) Text(stringResource(R.string.blk_no_rules), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Text("  " + settingTitle("spam_lists"), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            if (sum.lists == 0) stringResource(R.string.blk_no_lists) else
                                listOfNotNull(
                                    pluralStringResource(R.plurals.blk_lists_count, sum.lists, sum.lists),
                                    pluralStringResource(R.plurals.blk_numbers_count, sum.numbers.toPluralCount(), "%,d".format(sum.numbers)),
                                    sum.updatedAt?.let { stringResource(R.string.blk_updated_ago, ago(it, now)) },
                                ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (sum.stale > 0) Text(stringResource(R.string.blk_out_of_date_count, sum.stale), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(Help.LISTS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (suggestion != null) {
                            Text(stringResource(R.string.blk_suggested_for_sim, suggestion.name), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Row {
                                TextButton({ scope.launch { vm.c.lists.installBuiltIn(suggestion); vm.toast(context.getString(R.string.blk_added, suggestion.name)) } }) { Text(stringResource(R.string.blk_add)) }
                                TextButton({ scope.launch { vm.c.lists.dismissSuggestion(suggestion.id) } }) { Text(stringResource(R.string.blk_no_thanks)) }
                            }
                        }
                    }
                }
            }

            // ---- Off hours ----
            item(key = "offhours") {
                val oh = s.offHours
                CollapsibleSection(
                    stringResource(R.string.blk_off_hours), stringResource(Help.OFF_HOURS),
                    if (oh.enabled) {
                        listOf(
                            BlockingText.schedule(context, oh.schedule),
                            stringResource(R.string.blk_only_who, offHoursWho(oh)),
                            stringResource(if (oh.action == BlockAction.SILENCE) R.string.blk_action_silence else R.string.blk_action_reject),
                        )
                    } else {
                        listOf(stringResource(R.string.set_off))
                    },
                    "offhours" in expanded, { toggle("offhours") }, Icons.Rounded.Bedtime,
                ) {
                    ToggleRow(stringResource(R.string.blk_use_off_hours), null, oh.enabled) { v -> setScreening { it.copy(offHours = it.offHours.copy(enabled = v)) } }
                    ScheduleField(oh.schedule, { sc -> setScreening { it.copy(offHours = it.offHours.copy(schedule = sc ?: Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60))) } }, alwaysLabel = stringResource(R.string.blk_all_day))
                    OffHoursWho(vm, oh) { o -> setScreening { it.copy(offHours = o) } }
                    ListItem(headlineContent = { Text(stringResource(R.string.blk_everyone_else)) }, supportingContent = { ActionChoice(oh.action, { a -> setScreening { it.copy(offHours = it.offHours.copy(action = a)) } }, Modifier.padding(top = 8.dp)) })
                    ToggleRow(stringResource(R.string.blk_offer_reply), stringResource(R.string.blk_offer_reply_help, s.busyReplyText), s.busyReply) { v -> setScreening { it.copy(busyReply = v) } }
                    if (s.busyReply) {
                        var text by remember(s.busyReplyText) { mutableStateOf(s.busyReplyText) }
                        OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.blk_reply_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            trailingIcon = { if (text != s.busyReplyText && text.isNotBlank()) TextButton({ setScreening { it.copy(busyReplyText = text.trim()) } }) { Text(stringResource(R.string.set_save)) } })
                    }
                }
            }

            // ---- More checks ----
            item(key = "more") {
                CollapsibleSection(
                    stringResource(R.string.blk_more_checks), stringResource(Help.MORE),
                    listOfNotNull(
                        stringResource(R.string.blk_neighbour).takeIf { s.blockNeighbourSpoofing },
                        stringResource(R.string.blk_verification).takeIf { s.blockFailedVerification },
                        stringResource(R.string.blk_invalid_numbers).takeIf { s.blockInvalid },
                    ),
                    "more" in expanded, { toggle("more") }, Icons.Rounded.Security,
                ) {
                    ToggleRow(stringResource(R.string.blk_neighbour), stringResource(R.string.blk_neighbour_help), s.blockNeighbourSpoofing) { v ->
                        if (v && !Permissions.has(context, Manifest.permission.READ_PHONE_NUMBERS)) numbersPermission.launch(Manifest.permission.READ_PHONE_NUMBERS)
                        else setScreening { it.copy(blockNeighbourSpoofing = v) }
                    }
                    ToggleRow(stringResource(R.string.blk_failed_verification), stringResource(R.string.blk_failed_verification_help), s.blockFailedVerification) { v -> setScreening { it.copy(blockFailedVerification = v) } }
                    ToggleRow(stringResource(R.string.blk_cant_exist), stringResource(R.string.blk_cant_exist_help), s.blockInvalid) { v -> setScreening { it.copy(blockInvalid = v) } }
                    if (s.blockInvalid) ListItem(headlineContent = { Text(stringResource(R.string.blk_invalid_are)) }, supportingContent = { ActionChoice(s.invalidAction, { a -> setScreening { it.copy(invalidAction = a) } }, Modifier.padding(top = 8.dp)) })
                    Text(stringResource(R.string.blk_active), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    ToggleScheduleRow(stringResource(R.string.blk_hidden_numbers), s.hiddenSchedule) { sc -> setScreening { it.copy(hiddenSchedule = sc) } }
                    ToggleScheduleRow(stringResource(R.string.blk_preset_known), s.nonContactsSchedule) { sc -> setScreening { it.copy(nonContactsSchedule = sc) } }
                    ToggleScheduleRow(stringResource(R.string.blk_neighbour), s.neighbourSchedule) { sc -> setScreening { it.copy(neighbourSchedule = sc) } }
                    ToggleScheduleRow(stringResource(R.string.blk_verification), s.verificationSchedule) { sc -> setScreening { it.copy(verificationSchedule = sc) } }
                    ToggleScheduleRow(stringResource(R.string.blk_invalid_numbers), s.invalidSchedule) { sc -> setScreening { it.copy(invalidSchedule = sc) } }
                }
            }

            // ---- Sounds & notifications ----
            item(key = "sounds") {
                CollapsibleSection(
                    stringResource(R.string.blk_sounds), stringResource(Help.SOUNDS),
                    listOfNotNull(
                        stringResource(R.string.blk_sum_loud_favourites).takeIf { s.ringLoudFavourites },
                        stringResource(R.string.blk_sum_loud_repeat).takeIf { s.ringLoudRepeat },
                        stringResource(R.string.blk_sum_notify_blocked, notifyLabelInline(s.notifyBlocked)),
                    ),
                    "sounds" in expanded, { toggle("sounds") }, Icons.Rounded.MusicNote,
                ) {
                    SoundsSection(vm, s) { f -> setScreening(f) }
                }
            }

            // ---- Emergency ----
            item(key = "emergency") {
                CollapsibleSection(
                    stringResource(R.string.blk_emergency), stringResource(Help.EMERGENCY),
                    listOfNotNull(pluralStringResource(R.plurals.blk_sum_extra_numbers, s.emergencyExtras.size, s.emergencyExtras.size).takeIf { s.emergencyExtras.isNotEmpty() }),
                    "emergency" in expanded, { toggle("emergency") }, Icons.Rounded.Emergency,
                ) {
                    EmergencySection(vm, s) { f -> setScreening(f) }
                }
            }

            // ---- Tools ----
            item(key = "tools") {
                CollapsibleSection(stringResource(R.string.blk_tools), stringResource(Help.TOOLS), emptyList(), "tools" in expanded, { toggle("tools") }, Icons.Rounded.Build) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(testNumber, { testNumber = it }, label = { Text(stringResource(R.string.blk_test_a_number)) }, singleLine = true, textStyle = LtrText(), modifier = Modifier.weight(1f))
                        TextButton({ BlockingDialogs.show(BlockingDialog.Test(testNumber.trim())) }, enabled = testNumber.isNotBlank()) { Text(stringResource(R.string.blk_test)) }
                    }
                    ListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.DRY_RUN) },
                        leadingContent = { Icon(Icons.Rounded.History, null) },
                        headlineContent = { Text(stringResource(R.string.blk_tools_dry_run)) },
                        supportingContent = { Text(stringResource(R.string.blk_tools_dry_run_help)) },
                    )
                    ListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.TEMPLATES) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, null) },
                        headlineContent = { Text(stringResource(R.string.blk_templates)) },
                        supportingContent = { Text(stringResource(R.string.blk_tools_templates_help)) },
                    )
                    ListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.TRANSFER) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, null) },
                        headlineContent = { Text(stringResource(R.string.blk_tools_transfer)) },
                        supportingContent = { Text(stringResource(R.string.blk_tools_transfer_help)) },
                    )
                }
            }

            // ---- System block list ----
            item(key = "system") {
                CollapsibleSection(
                    stringResource(R.string.blk_system_list), stringResource(R.string.blk_system_list_help),
                    listOf(pluralStringResource(R.plurals.set_numbers_count, system.size, system.size)), "system" in expanded, { toggle("system") }, Icons.Rounded.Block,
                ) {
                    TextButton({ addNumber = true }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Rounded.Add, null); Text(" " + stringResource(R.string.blk_block_a_number)) }
                    if (!vm.c.blocks.canUseSystemList()) Text(stringResource(R.string.blk_need_default), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                    system.forEach { b ->
                        ListItem(
                            leadingContent = { Icon(Icons.Rounded.Block, null) },
                            headlineContent = { Text(bidiLtr(Format.number(b.number, vm.countryIso))) },
                            trailingContent = { IconButton({ vm.unblockNumber(b.number) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.blk_unblock)) } },
                        )
                    }
                }
            }

            // ---- Blocked log ----
            item(key = "log-head") {
                app.parley.ui.contact.Section(stringResource(R.string.blk_recent))
                Text(stringResource(Help.LOG), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (suggestions.isNotEmpty()) {
                    Text(stringResource(R.string.blk_likely_spam_for_you), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
                    suggestions.forEach { sg ->
                        ListItem(
                            headlineContent = { Text(bidiLtr(Format.number(sg.number, vm.countryIso))) },
                            supportingContent = { Text(stringResource(R.string.blk_sugg_line, BlockingText.suggestionReason(context, sg), ago(sg.lastAt, now))) },
                            trailingContent = {
                                Row {
                                    TextButton({ dismissed.value = dismissed.value + sg.number }) { Text(stringResource(R.string.blk_dismiss)) }
                                    TextButton({
                                        scope.launch {
                                            BlockingActions.blockNumberRule(vm.c, sg.number, context.getString(R.string.blk_likely_spam_for_you))
                                            vm.toast(context.getString(R.string.blk_blocked_toast))
                                        }
                                    }) { Text(stringResource(R.string.blk_block)) }
                                }
                            },
                        )
                    }
                }
                if (log.isEmpty()) Text(stringResource(R.string.blk_nothing_yet), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextButton({ scope.launch { vm.c.blocks.clearBlockedLog() } }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.blk_clear_log)) }
            }
            val shown = log.take(100)
            itemsIndexed(shown, key = { _, e -> "l" + e.id }) { i, e ->
                // U2: the log as one segmented group.
                BlockingCard(app.parley.ui.segmentShape(i, shown.size), vertical = 1.dp) { BlockedLogRow(vm, e) }
            }
        }
    }

    presetToApply?.let { pr ->
        AlertDialog(
            onDismissRequest = { presetToApply = null },
            title = { Text(stringResource(pr.title)) },
            text = { Text(stringResource(pr.help)) },
            confirmButton = { TextButton({ scope.launch { vm.c.settings.update(pr.apply) }; presetToApply = null }) { Text(stringResource(R.string.blk_use_this)) } },
            dismissButton = { TextButton({ presetToApply = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
    if (addNumber) {
        var n by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addNumber = false },
            title = { Text(stringResource(R.string.blk_block_a_number)) },
            text = { OutlinedTextField(n, { n = it }, label = { Text(stringResource(R.string.blk_phone_number)) }, singleLine = true, textStyle = LtrText()) },
            confirmButton = { TextButton({ if (n.isNotBlank()) vm.blockNumber(n.trim()); addNumber = false }) { Text(stringResource(R.string.blk_block)) } },
            dismissButton = { TextButton({ addNumber = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}

@Composable
private fun offHoursWho(o: OffHours) = when (o.allow) {
    OffHoursAllow.CONTACTS -> stringResource(R.string.blk_who_contacts)
    OffHoursAllow.FAVOURITES -> stringResource(R.string.blk_who_favourites)
    OffHoursAllow.LABEL -> "'${o.labelTitle ?: stringResource(R.string.blk_who_label)}'"
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
        supportingContent = { Text(schedule?.let { BlockingText.schedule(LocalContext.current, it) } ?: stringResource(R.string.blk_always)) },
    )
    if (open) ScheduleField(schedule, onChange)
}

@Composable
private fun OffHoursWho(vm: AppViewModel, oh: OffHours, onChange: (OffHours) -> Unit) {
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { runCatching { vm.c.contacts.groups() }.getOrDefault(emptyList()) } }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.blk_who_may_ring), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(oh.allow == OffHoursAllow.CONTACTS, { onChange(oh.copy(allow = OffHoursAllow.CONTACTS)) }, label = { Text(stringResource(R.string.blk_all_contacts)) })
            FilterChip(oh.allow == OffHoursAllow.FAVOURITES, { onChange(oh.copy(allow = OffHoursAllow.FAVOURITES)) }, label = { Text(stringResource(R.string.blk_favourites)) })
            // By title: the label in every account.
            groups.map { app.parley.common.LabelRefs.key(it.title) }.distinct().forEach { t ->
                val on = oh.allow == OffHoursAllow.LABEL && oh.labelTitle?.let { app.parley.common.LabelRefs.key(it) } == t
                FilterChip(on, { onChange(oh.copy(allow = OffHoursAllow.LABEL, labelId = null, labelTitle = t)) }, label = { Text(t) })
            }
        }
        Text(stringResource(R.string.blk_off_hours_still_through), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SoundsSection(vm: AppViewModel, s: ScreeningSettings, set: ((ScreeningSettings) -> ScreeningSettings) -> Unit) {
    val context = LocalContext.current
    var target by remember { mutableStateOf("") }
    val pick = rememberRingtonePicker { uri -> if (target == "repeat") set { it.copy(repeatRingtone = uri) } else set { it.copy(likelySpamRingtone = uri) } }
    ToggleRow(stringResource(R.string.blk_loud_favourites), stringResource(R.string.blk_loud_favourites_help), s.ringLoudFavourites) { v -> set { it.copy(ringLoudFavourites = v) } }
    ToggleRow(stringResource(R.string.blk_loud_repeat), stringResource(R.string.blk_loud_repeat_help, s.repeatWindowMinutes), s.ringLoudRepeat) { v -> set { it.copy(ringLoudRepeat = v) } }
    ListItem(
        modifier = Modifier.clickable { target = "repeat"; pick(s.repeatRingtone) },
        headlineContent = { Text(stringResource(R.string.blk_ringtone_repeat)) },
        supportingContent = { Text(ringtoneTitle(context, s.repeatRingtone) ?: stringResource(R.string.set_same_as_usual)) },
        trailingContent = { if (s.repeatRingtone != null) TextButton({ set { it.copy(repeatRingtone = null) } }) { Text(stringResource(R.string.set_reset)) } },
    )
    ListItem(
        modifier = Modifier.clickable { target = "spam"; pick(s.likelySpamRingtone) },
        headlineContent = { Text(stringResource(R.string.blk_ringtone_spam)) },
        supportingContent = { Text(ringtoneTitle(context, s.likelySpamRingtone) ?: stringResource(R.string.set_same_as_usual)) },
        trailingContent = { if (s.likelySpamRingtone != null) TextButton({ set { it.copy(likelySpamRingtone = null) } }) { Text(stringResource(R.string.set_reset)) } },
    )
    Text(stringResource(R.string.blk_label_ringtones_help), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.blk_notifications), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
    listOf(
        Triple(stringResource(R.string.blk_notify_blocked), s.notifyBlocked) { n: app.parley.common.NotifyLevel -> set { it.copy(notifyBlocked = n) } },
        Triple(stringResource(R.string.blk_notify_reported), s.notifyReported) { n: app.parley.common.NotifyLevel -> set { it.copy(notifyReported = n) } },
        Triple(stringResource(R.string.blk_notify_likely), s.notifyLikelySpam) { n: app.parley.common.NotifyLevel -> set { it.copy(notifyLikelySpam = n) } },
    ).forEach { (title, v, change) ->
        ListItem(headlineContent = { Text(title) }, supportingContent = { NotifyChoice(v, allowDefault = false, change) })
    }
    if (s.notifyBlocked == app.parley.common.NotifyLevel.NONE) {
        Text(stringResource(R.string.blk_notify_off_warning), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    TextButton({
        BlockingNotifier.channels(context)
        try {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } catch (_: Exception) {
        }
    }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.blk_channel_settings)) }
}

@Composable
private fun EmergencySection(vm: AppViewModel, s: ScreeningSettings, set: ((ScreeningSettings) -> ScreeningSettings) -> Unit) {
    var n by remember { mutableStateOf("") }
    Text(
        stringResource(R.string.blk_emergency_extras_help),
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
    )
    s.emergencyExtras.forEach { x ->
        ListItem(
            headlineContent = { Text(bidiLtr(Format.number(x, vm.countryIso))) },
            trailingContent = { IconButton({ set { it.copy(emergencyExtras = it.emergencyExtras - x) } }) { Icon(Icons.Rounded.Delete, stringResource(R.string.ct_remove)) } },
        )
    }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(n, { n = it }, label = { Text(stringResource(R.string.blk_add_a_number)) }, singleLine = true, textStyle = LtrText(), modifier = Modifier.weight(1f))
        TextButton({
            val clean = RuleTools.check(n, RuleType.EXACT, vm.countryIso)
            if (clean.error == null) set { it.copy(emergencyExtras = (it.emergencyExtras + clean.pattern).distinct()) }
            n = ""
        }, enabled = n.isNotBlank()) { Text(stringResource(R.string.blk_add)) }
    }
}

/** A rule with its hit counter ("5 calls, last 2 days ago") and on/off switch. */
@Composable
private fun RuleRow(vm: AppViewModel, r: BlockRule, now: Long, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val expiresAt = r.expiresAt
    val expired = expiresAt != null && expiresAt <= now
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(if (r.kind == RuleKind.ALLOW) Icons.Rounded.VerifiedUser else Icons.Rounded.Rule, null) },
        headlineContent = { Text(BlockingText.ruleTitle(context, r).let { if (r.note.isNullOrBlank() && r.type.isNumberRule) bidiLtr(it) else it }) },
        supportingContent = {
            Text(
                listOfNotNull(
                    BlockingText.ruleDescribe(context, r).takeIf { r.note != null || !r.type.isNumberRule },
                    if (r.kind == RuleKind.BLOCK) stringResource(if (r.action == BlockAction.SILENCE) R.string.blk_action_silence_lower else R.string.blk_action_reject_lower) else null,
                    r.schedule?.let { BlockingText.schedule(context, it) },
                    r.simId?.let { id -> vm.sims.value.firstOrNull { it.id == id }?.label ?: stringResource(R.string.blk_one_sim) },
                    expiresAt?.let { if (expired) stringResource(R.string.blk_expired) else stringResource(R.string.blk_for_duration, leftText(context, it - now)) },
                    if (r.hitCount > 0) {
                        pluralStringResource(R.plurals.blk_calls, r.hitCount, r.hitCount) + (r.lastHitAt?.let { ", " + stringResource(R.string.blk_last_ago, ago(it, now)) } ?: "")
                    } else {
                        null
                    },
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
            headlineContent = { Text((if (e.failedOpen) "! " else "") + (e.number?.let { bidiLtr(Format.number(it, vm.countryIso)) } ?: stringResource(R.string.blk_private_number))) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        Format.fullDate(context, e.time),
                        BlockingText.verdict(context, e.verdict) ?: legacyReason(e.reason),
                        BlockingText.actionLower(context, e.action).takeIf { e.action != "ALLOW" },
                        e.callerName,
                    ).joinToString(" · "),
                )
            },
        )
        if (open) {
            Column(Modifier.padding(start = 24.dp, end = 16.dp, bottom = 8.dp)) {
                val steps = TraceCodec.decode(e.trace)
                if (steps.isEmpty()) Text(stringResource(R.string.blk_no_details), style = MaterialTheme.typography.bodySmall)
                else TraceList(steps)
                e.number?.let { app.parley.ui.calls.RingFactsFor(vm, it, e.time, Modifier.padding(top = 8.dp)) }
                val n = e.number
                if (n != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ scope.launch { BlockingActions.notSpam(vm.c, n, e.packId); vm.toast(context.getString(R.string.blk_marked_not_spam)) } }, { Text(stringResource(R.string.blk_not_spam)) })
                        AssistChip({ scope.launch { BlockingActions.allowNumber(vm.c, n, hours = 24); vm.toast(context.getString(R.string.blk_allowed_24h)) } }, { Text(stringResource(R.string.blk_allow_24h)) })
                        AssistChip({ BlockingDialogs.show(BlockingDialog.Test(n)) }, { Text(stringResource(R.string.blk_test_again)) })
                        AssistChip({ BlockingDialogs.show(BlockingDialog.Report(n)) }, { Text(stringResource(R.string.blk_report)) })
                        AssistChip({ scope.launch { vm.c.blocks.deleteScreened(e.id) } }, { Text(stringResource(R.string.blk_delete)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun legacyReason(r: String) = stringResource(
    when (r) {
        "HIDDEN" -> R.string.blk_reason_hidden
        "NOT_A_CONTACT" -> R.string.blk_reason_not_contact
        "NEIGHBOUR_SPOOF" -> R.string.blk_reason_neighbour
        "VERIFICATION_FAILED" -> R.string.blk_legacy_verification
        "SYSTEM_LIST" -> R.string.blk_legacy_block_list
        else -> R.string.blk_legacy_rule
    },
)
