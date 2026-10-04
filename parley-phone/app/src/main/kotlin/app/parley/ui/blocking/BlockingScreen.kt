package app.parley.ui.blocking

import app.parley.ui.Destination
import android.Manifest
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingActions
import app.parley.blocking.BlockingText
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import app.parley.blocking.ExpectingCallTileService
import app.parley.common.BlockAction
import app.parley.common.CallPolicy
import app.parley.common.RuleKind
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.TraceCodec
import app.parley.common.blocking.PersonalReputation
import app.parley.common.blocking.ScreeningPreset
import app.parley.common.blocking.ScreeningWeek
import app.parley.common.blocking.ScreeningWeekly
import app.parley.common.blocking.StoppedCall
import app.parley.common.spam.BuiltInPacks
import app.parley.data.Permissions
import app.parley.data.PhoneEnv
import app.parley.data.db.BlockedCallEntity
import app.parley.telecom.ScreeningGuard
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.calls.RingFactsFor
import app.parley.ui.common.Format
import app.parley.ui.segmentShape
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.SwitchRow
import app.parley.ui.ParleyTopBar
import app.parley.ui.ParleyScaffold
import app.parley.ui.ConfirmDialog
import app.parley.ui.ListSectionHeader

/** Situations, not mechanisms: each setup says what it's for; [ScreeningPreset] holds the few switches it changes. */
@get:StringRes
internal val ScreeningPreset.title: Int
    get() = when (this) {
        ScreeningPreset.KNOWN -> R.string.blk_preset_known
        ScreeningPreset.TELEMARKETERS -> R.string.blk_preset_telemarketers
        ScreeningPreset.NIGHTS -> R.string.blk_preset_nights
        ScreeningPreset.EVERYONE -> R.string.blk_preset_everyone
    }

@get:StringRes
internal val ScreeningPreset.help: Int
    get() = when (this) {
        ScreeningPreset.KNOWN -> R.string.blk_preset_known_help
        ScreeningPreset.TELEMARKETERS -> R.string.blk_preset_telemarketers_help
        ScreeningPreset.NIGHTS -> R.string.blk_preset_nights_help
        ScreeningPreset.EVERYONE -> R.string.blk_preset_everyone_help
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit = {}) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    val system by vm.c.blocks.systemList.collectAsStateWithLifecycle()
    val log by vm.c.blocks.blockedCalls.collectAsStateWithLifecycle(emptyList())
    val lists by vm.c.lists.state.collectAsStateWithLifecycle()
    val calls by vm.c.history.calls.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val s = settings.screening
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggle(k: String) {
        expanded = if (k in expanded) expanded - k else expanded + k
    }
    var addNumber by rememberSaveable { mutableStateOf(false) }
    var presetToApply by remember { mutableStateOf<ScreeningPreset?>(null) }
    var testNumber by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    fun setScreening(f: (ScreeningSettings) -> ScreeningSettings) = scope.launch { vm.c.settings.update { it.copy(screening = f(it.screening)) } }

    val numbersPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) setScreening { it.copy(blockNeighbourSpoofing = true) } else vm.toast(res.getString(R.string.blk_need_own_number))
    }
    val allowRules = rules.filter { it.kind == RuleKind.ALLOW }
    val blockRules = rules.filter { it.kind == RuleKind.BLOCK }
    val dismissed = remember { mutableStateOf(setOf<String>()) }
    val suggestions = remember(calls, rules, s.reputationSuggestions, dismissed.value) {
        if (!s.reputationSuggestions) emptyList() else PersonalReputation.suggestions(
            calls.orEmpty().take(1500), System.currentTimeMillis(), countryOf = { e -> PhoneEnv.countryIso(context, e.accountId) },
        ) { n ->
            n in dismissed.value || vm.contactFor(n) != null || rules.any { r -> r.type.isNumberRule && CallPolicy.ruleMatches(r, n, vm.countryIso) }
        }.take(5)
    }

    // Scroll-linked top-bar tint.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection), topBar = {
        ParleyTopBar(stringResource(R.string.blk_title), onBack = back, scrollBehavior = barScroll)
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            // P9: the setups first, with the one you're on named and what the last week looked like.
            item(key = "presets") {
                // L5: every stopped call of the week (the log shows only the latest 500), contacts counted as people.
                val shown = remember(log, now) { weekOf(log, now) { vm.contactFor(it)?.lookupKey } }
                val week by produceState(shown, log.firstOrNull()?.id, log.size, now) {
                    value = withContext(Dispatchers.IO) {
                        val all = runCatching { vm.c.blocks.blockedSince(now - ScreeningWeekly.WEEK_MS) }.getOrDefault(log)
                        weekOf(all, now) { vm.contactFor(it)?.lookupKey }
                    }
                }
                PresetHeader(ScreeningPreset.current(s), week) { presetToApply = it }
            }

            item(key = "status") { ScreeningStatusCard(vm) }

            // "Expecting a call" chip in the header.
            item(key = "snooze") {
                val left = s.snoozeUntil - now
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

            // Visible emergency countdown.
            item(key = "emergency-live") {
                val ends = remember(now) { ScreeningGuard.emergencyWindowEndsAt(context) }
                if (ends != null) {
                    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Emergency, null)
                            Text("  " + stringResource(R.string.blk_emergency_window, leftText(context, ends - now)), Modifier.weight(1f))
                            TextButton({ ScreeningGuard.clearEmergencyWindow(context); now = System.currentTimeMillis() }) {
                                Text(stringResource(R.string.set_reset))
                            }
                        }
                    }
                }
            }

            item(key = "main") { BlockingCard {
                SwitchRow(
                    stringResource(R.string.blk_hidden),
                    stringResource(R.string.blk_hidden_help) + (s.hiddenSchedule?.let { " · ${BlockingText.schedule(context, it)}" } ?: ""),
                    s.blockHidden,
                    enabled = isDefault,
                ) { v -> setScreening { it.copy(blockHidden = v) } }
                if (!isDefault) Text(
                    stringResource(R.string.blk_hidden_needs_default),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                SwitchRow(
                    stringResource(R.string.blk_non_contacts),
                    stringResource(R.string.blk_non_contacts_help) + (s.nonContactsSchedule?.let { " · ${BlockingText.schedule(context, it)}" } ?: ""),
                    s.blockNonContacts,
                ) { v -> setScreening { it.copy(blockNonContacts = v) } }
                ParleyListItem(
                    headlineContent = { Text(stringResource(R.string.blk_when_stopped)) },
                    supportingContent = {
                        Column { ActionChoice(s.defaultAction, { a -> setScreening { it.copy(defaultAction = a) } }, Modifier.padding(top = 8.dp)) }
                    },
                )
            } }

            // ---- Spam lists hero card ----
            item(key = "lists") {
                val sum = vm.c.lists.summarize(lists, now)
                val suggestion = BuiltInPacks.suggestedFor(vm.countryIso).firstOrNull { b ->
                    lists.packs.none { it.id == b.id } && b.id !in lists.dismissedSuggestions
                }
                Card(Modifier.fillMaxWidth().padding(16.dp).clickable { open(BlockingRoutes.Lists) }) {
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
                        if (sum.stale > 0) Text(
                            pluralStringResource(R.plurals.blk_out_of_date_count, sum.stale, sum.stale),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(stringResource(Help.LISTS), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (suggestion != null) {
                            Text(
                                stringResource(R.string.blk_suggested_for_sim, suggestion.name),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Row {
                                TextButton({ scope.launch { vm.c.lists.installBuiltIn(suggestion); vm.toast(res.getString(R.string.blk_added, suggestion.name)) } }) { Text(stringResource(R.string.blk_add)) }
                                TextButton({ scope.launch { vm.c.lists.dismissSuggestion(suggestion.id) } }) { Text(stringResource(R.string.blk_no_thanks)) }
                            }
                        }
                    }
                }
            }

            // The rule lists and fine-tuning, under the setups and the spam lists.
            item(key = "advanced") { ListSectionHeader(stringResource(R.string.blk_advanced), top = 16.dp, bottom = 0.dp) }

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
                    SwitchRow(
                        stringResource(R.string.blk_repeat_callers),
                        stringResource(R.string.blk_repeat_callers_help, s.repeatWindowMinutes, s.repeatMinIntervalSeconds),
                        settings.repeatCallerRingsThrough,
                    ) { v ->
                        scope.launch { vm.c.settings.update { it.copy(repeatCallerRingsThrough = v) } }
                    }
                    if (settings.repeatCallerRingsThrough) {
                        LabeledSlider(
                            stringResource(R.string.blk_repeat_window, s.repeatWindowMinutes), s.repeatWindowMinutes.toFloat(), 1f..15f, 13,
                        ) { v -> setScreening { it.copy(repeatWindowMinutes = v.toInt()) } }
                        LabeledSlider(
                            stringResource(R.string.blk_repeat_min_interval, s.repeatMinIntervalSeconds), s.repeatMinIntervalSeconds.toFloat(), 0f..60f, 11,
                        ) { v -> setScreening { it.copy(repeatMinIntervalSeconds = v.toInt()) } }
                    }
                    SwitchRow(
                        stringResource(R.string.blk_numbers_you_called),
                        pluralStringResource(R.plurals.blk_numbers_you_called_help, s.dialledDays, s.dialledDays),
                        s.allowDialled,
                    ) { v -> setScreening { it.copy(allowDialled = v) } }
                    SwitchRow(
                        stringResource(R.string.blk_people_you_talked_to),
                        pluralStringResource(R.plurals.blk_people_you_talked_to_help, s.answeredDays, s.answeredMinSeconds, s.answeredDays),
                        s.allowAnswered,
                    ) { v -> setScreening { it.copy(allowAnswered = v) } }
                    TextButton(
                        { open(BlockingRoutes.rule(0, RuleKind.ALLOW)) }, Modifier.padding(horizontal = 8.dp),
                    ) { Icon(Icons.Rounded.Add, null); Text(" " + stringResource(R.string.blk_allow_number_or_range)) }
                    allowRules.forEach { r -> RuleRow(vm, r, now) { open(BlockingRoutes.rule(r.id)) } }
                }
            }

            // ---- Block rules ----
            item(key = "block") {
                CollapsibleSection(
                    stringResource(R.string.blk_your_rules), stringResource(Help.BLOCK),
                    listOfNotNull(
                        stringResource(R.string.blk_sum_on, blockRules.count { it.enabled }).takeIf { blockRules.isNotEmpty() },
                        blockRules.count { it.schedule != null }.let { n -> pluralStringResource(R.plurals.blk_sum_scheduled, n, n) }.takeIf {
                            blockRules.any { it.schedule != null }
                        },
                        blockRules.sumOf { it.hitCount }.let { n -> pluralStringResource(R.plurals.blk_sum_calls_stopped, n, n) }.takeIf {
                            blockRules.any { it.hitCount > 0 }
                        },
                    ),
                    "block" in expanded, { toggle("block") }, Icons.Rounded.Rule,
                ) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton({ open(BlockingRoutes.rule(0, RuleKind.BLOCK)) }) {
                            Icon(Icons.Rounded.Add, null)
                            Text(" " + stringResource(R.string.blk_add_rule))
                        }
                    }
                    if (blockRules.isEmpty()) Text(
                        stringResource(R.string.blk_no_rules), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    blockRules.forEach { r -> RuleRow(vm, r, now) { open(BlockingRoutes.rule(r.id)) } }
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
                    SwitchRow(
                        stringResource(R.string.blk_use_off_hours),
                        null,
                        oh.enabled,
                    ) { v -> setScreening { it.copy(offHours = it.offHours.copy(enabled = v)) } }
                    ScheduleField(
                        oh.schedule,
                        { sc -> setScreening { it.copy(offHours = it.offHours.copy(schedule = sc ?: Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60))) } },
                        alwaysLabel = stringResource(R.string.blk_all_day),
                    )
                    OffHoursWho(vm, oh) { o -> setScreening { it.copy(offHours = o) } }
                    ParleyListItem(
                        headlineContent = { Text(stringResource(R.string.blk_everyone_else)) },
                        supportingContent = {
                            ActionChoice(oh.action, { a -> setScreening { it.copy(offHours = it.offHours.copy(action = a)) } }, Modifier.padding(top = 8.dp))
                        },
                    )
                    SwitchRow(
                        stringResource(R.string.blk_offer_reply),
                        stringResource(R.string.blk_offer_reply_help, s.busyReplyText),
                        s.busyReply,
                    ) { v -> setScreening { it.copy(busyReply = v) } }
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
                    SwitchRow(stringResource(R.string.blk_neighbour), stringResource(R.string.blk_neighbour_help), s.blockNeighbourSpoofing) { v ->
                        if (v && !Permissions.has(context, Manifest.permission.READ_PHONE_NUMBERS)) numbersPermission.launch(
                            Manifest.permission.READ_PHONE_NUMBERS,
                        )
                        else setScreening { it.copy(blockNeighbourSpoofing = v) }
                    }
                    SwitchRow(
                        stringResource(R.string.blk_failed_verification),
                        stringResource(R.string.blk_failed_verification_help),
                        s.blockFailedVerification,
                    ) { v -> setScreening { it.copy(blockFailedVerification = v) } }
                    SwitchRow(
                        stringResource(R.string.blk_cant_exist),
                        stringResource(R.string.blk_cant_exist_help),
                        s.blockInvalid,
                    ) { v -> setScreening { it.copy(blockInvalid = v) } }
                    if (s.blockInvalid) ParleyListItem(
                        headlineContent = { Text(stringResource(R.string.blk_invalid_are)) },
                        supportingContent = {
                            ActionChoice(s.invalidAction, { a -> setScreening { it.copy(invalidAction = a) } }, Modifier.padding(top = 8.dp))
                        },
                    )
                    Text(
                        stringResource(R.string.blk_active),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                    ToggleScheduleRow(stringResource(R.string.blk_hidden_numbers), s.hiddenSchedule) { sc -> setScreening { it.copy(hiddenSchedule = sc) } }
                    ToggleScheduleRow(
                        stringResource(R.string.blk_preset_known), s.nonContactsSchedule,
                    ) { sc -> setScreening { it.copy(nonContactsSchedule = sc) } }
                    ToggleScheduleRow(stringResource(R.string.blk_neighbour), s.neighbourSchedule) { sc -> setScreening { it.copy(neighbourSchedule = sc) } }
                    ToggleScheduleRow(
                        stringResource(R.string.blk_verification), s.verificationSchedule,
                    ) { sc -> setScreening { it.copy(verificationSchedule = sc) } }
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
                    SoundsSection(s) { f -> setScreening(f) }
                }
            }

            // ---- Emergency ----
            item(key = "emergency") {
                CollapsibleSection(
                    stringResource(R.string.blk_emergency), stringResource(Help.EMERGENCY),
                    listOfNotNull(
                        pluralStringResource(
                            R.plurals.blk_sum_extra_numbers, s.emergencyExtras.size, s.emergencyExtras.size,
                        ).takeIf { s.emergencyExtras.isNotEmpty() },
                    ),
                    "emergency" in expanded, { toggle("emergency") }, Icons.Rounded.Emergency,
                ) {
                    EmergencySection(vm, s) { f -> setScreening(f) }
                }
            }

            // ---- Tools ----
            item(key = "tools") {
                CollapsibleSection(
                    stringResource(R.string.blk_tools), stringResource(Help.TOOLS), emptyList(), "tools" in expanded, { toggle("tools") }, Icons.Rounded.Build,
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            testNumber,
                            { testNumber = it },
                            label = { Text(stringResource(R.string.blk_test_a_number)) },
                            singleLine = true,
                            textStyle = ltrTextStyle(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            { BlockingDialogs.show(BlockingDialog.Test(testNumber.trim())) }, enabled = testNumber.isNotBlank(),
                        ) { Text(stringResource(R.string.blk_test)) }
                    }
                    ParleyListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.DryRun) },
                        leadingContent = { Icon(Icons.Rounded.History, null) },
                        headlineContent = { Text(stringResource(R.string.blk_tools_dry_run)) },
                        supportingContent = { Text(stringResource(R.string.blk_tools_dry_run_help)) },
                    )
                    ParleyListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.Templates) },
                        leadingContent = { Icon(Icons.AutoMirrored.Rounded.PlaylistAddCheck, null) },
                        headlineContent = { Text(stringResource(R.string.blk_templates)) },
                        supportingContent = { Text(stringResource(R.string.blk_tools_templates_help)) },
                    )
                    ParleyListItem(
                        modifier = Modifier.clickable { open(BlockingRoutes.Transfer) },
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
                    listOf(
                        pluralStringResource(R.plurals.set_numbers_count, system.size, system.size),
                    ), "system" in expanded, { toggle("system") }, Icons.Rounded.Block,
                ) {
                    TextButton(
                        { addNumber = true }, Modifier.padding(horizontal = 8.dp),
                    ) { Icon(Icons.Rounded.Add, null); Text(" " + stringResource(R.string.blk_block_a_number)) }
                    if (!vm.c.blocks.canUseSystemList()) Text(
                        stringResource(R.string.blk_need_default), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
                    )
                    system.forEach { b ->
                        ParleyListItem(
                            leadingContent = { Icon(Icons.Rounded.Block, null) },
                            headlineContent = { Text(bidiLtr(Format.number(b.number, vm.countryIso))) },
                            trailingContent = {
                                IconButton({ vm.unblockNumber(b.number) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.blk_unblock)) }
                            },
                        )
                    }
                }
            }

            // ---- Blocked log ----
            item(key = "log-head") {
                Section(stringResource(R.string.blk_recent))
                Text(
                    stringResource(Help.LOG),
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (suggestions.isNotEmpty()) {
                    Text(
                        stringResource(R.string.blk_likely_spam_for_you),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
                    )
                    suggestions.forEach { sg ->
                        ParleyListItem(
                            headlineContent = { Text(bidiLtr(Format.number(sg.number, vm.countryIso))) },
                            supportingContent = {
                                Text(stringResource(R.string.blk_sugg_line, BlockingText.suggestionReason(context, sg), ago(sg.lastAt, now)))
                            },
                            trailingContent = {
                                Row {
                                    TextButton({ dismissed.value = dismissed.value + sg.number }) { Text(stringResource(R.string.blk_dismiss)) }
                                    TextButton({
                                        askToBlock(listOf(sg.number), note = res.getString(R.string.blk_likely_spam_for_you))
                                    }) { Text(stringResource(R.string.blk_block)) }
                                }
                            },
                        )
                    }
                }
                if (log.isEmpty()) Text(stringResource(R.string.blk_nothing_yet), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextButton(
                    { scope.launch { vm.c.blocks.clearBlockedLog() } }, Modifier.padding(horizontal = 8.dp),
                ) { Text(stringResource(R.string.blk_clear_log)) }
            }
            val shown = log.take(100)
            itemsIndexed(shown, key = { _, e -> "l" + e.id }) { i, e ->
                // The log as one segmented group.
                BlockingCard(segmentShape(i, shown.size), vertical = 1.dp) { BlockedLogRow(vm, e) }
            }
        }
    }

    presetToApply?.let { pr ->
        // M9: what the setup would replace is said here, and can be kept.
        val removed = pr.removedSchedule(s)
        var keep by remember(pr) { mutableStateOf(false) }
        ConfirmDialog(
            title = stringResource(pr.title),
            text = stringResource(pr.help),
            confirmLabel = stringResource(R.string.blk_use_this),
            onConfirm = { scope.launch { vm.c.settings.update { pr.apply(it, keepSchedule = keep) } }; presetToApply = null },
            onDismiss = { presetToApply = null },
            dismissLabel = stringResource(R.string.set_cancel),
            content = removed?.let { sch ->
                {
                    Text(stringResource(R.string.blk_known_removes_schedule, BlockingText.schedule(context, sch)), style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.fillMaxWidth().toggleable(keep, role = Role.Checkbox) { keep = it }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(keep, null)
                        Text(stringResource(R.string.blk_known_keep_schedule), Modifier.padding(start = 8.dp))
                    }
                }
            },
        )
    }
    if (addNumber) {
        var n by rememberSaveable { mutableStateOf("") }
        ConfirmDialog(
            title = stringResource(R.string.blk_block_a_number),
            text = null,
            confirmLabel = stringResource(R.string.blk_block),
            onConfirm = {
                if (n.isNotBlank()) vm.blockNumber(n.trim())
                addNumber = false
            },
            onDismiss = { addNumber = false },
            dismissLabel = stringResource(R.string.set_cancel),
            content = { OutlinedTextField(
                n,
                { n = it },
                label = { Text(stringResource(R.string.blk_phone_number)) },
                singleLine = true,
                textStyle = ltrTextStyle(),
            ) },
        )
    }
}

/** Expandable blocked-log row: the stored trace, plus "Not spam", allow for a day, report, delete. */
@Composable
private fun BlockedLogRow(vm: AppViewModel, e: BlockedCallEntity) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        ParleyListItem(
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
                e.number?.let { RingFactsFor(vm, it, e.time, Modifier.padding(top = 8.dp)) }
                val n = e.number
                if (n != null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            { scope.launch { BlockingActions.notSpam(vm.c, n, e.packId); vm.toast(res.getString(R.string.blk_marked_not_spam)) } },
                            { Text(stringResource(R.string.blk_not_spam)) },
                        )
                        AssistChip(
                            { scope.launch { BlockingActions.allowNumber(vm.c, n, hours = 24); vm.toast(res.getString(R.string.blk_allowed_24h)) } },
                            { Text(stringResource(R.string.blk_allow_24h)) },
                        )
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

/**
 * The stopped calls of the last week, with whether each came from a contact (read from its stored trace) and which
 * one ([person]: its key, so one person calling from two numbers counts once).
 */
private fun weekOf(log: List<BlockedCallEntity>, now: Long, person: (String) -> String?): ScreeningWeek = ScreeningWeekly.summarize(
    log.filter { it.time >= now - ScreeningWeekly.WEEK_MS }.map { e ->
        val contact = ScreeningWeekly.fromContact(TraceCodec.decode(e.trace))
        StoppedCall(
            e.time, e.number, silenced = e.action != BlockAction.REJECT.name, fromContact = contact,
            person = if (contact) e.number?.let(person) else null,
        )
    },
    now,
)
