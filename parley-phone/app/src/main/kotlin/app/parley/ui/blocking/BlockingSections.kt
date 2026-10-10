// The screen's shared state and its sections live together.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.blocking

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Emergency
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
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
import app.parley.blocking.ExpectingCallTileService
import app.parley.common.AppSettings
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.CallPolicy
import app.parley.common.RuleKind
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.blocking.PersonalReputation
import app.parley.common.blocking.WhatDecides
import app.parley.common.catching
import app.parley.common.spam.BuiltInPacks
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.common.ux.SalesLines
import app.parley.data.Permissions
import app.parley.data.PhoneEnv
import app.parley.data.calls.ReputationLearner
import app.parley.telecom.ScreeningGuard
import app.parley.ui.Destination
import app.parley.ui.MenuRow
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.SwitchRow
import app.parley.ui.common.Format
import app.parley.ui.settings.ExpectedHintsRow
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle
import app.parley.ui.sync.shared.FamilyShieldTexts
import app.parley.ui.sync.shared.SharedLabelRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What Blocking & screening's sections share: the settings and the clock they read, which sections are open, and the
 * one way to change a setting (in the screen's scope, so leaving the screen doesn't stop a write half way).
 */
@Stable
internal class BlockingState(
    val vm: AppViewModel,
    private val scope: CoroutineScope,
    private val settingsState: State<AppSettings>,
    private val rulesState: State<List<BlockRule>>,
    private val expandedState: MutableState<Set<String>>,
    private val nowState: MutableLongState,
) {
    val settings: AppSettings get() = settingsState.value
    val screening: ScreeningSettings get() = settings.screening

    /** Your block and allow rules, read once for every section. */
    val rules: List<BlockRule> get() = rulesState.value
    var now: Long
        get() = nowState.longValue
        set(v) { nowState.longValue = v }

    fun isOpen(key: String): Boolean = key in expandedState.value

    fun toggle(key: String) {
        val e = expandedState.value
        expandedState.value = if (key in e) e - key else e + key
    }

    fun setScreening(f: (ScreeningSettings) -> ScreeningSettings) = setSettings { it.copy(screening = f(it.screening)) }

    fun setSettings(f: (AppSettings) -> AppSettings) {
        scope.launch { vm.c.settings.update(f) }
    }

    /**
     * Sales lines from your calls: Off · Tag quietly · Tag and silence. Learns at once (or forgets everything), in the
     * app's scope so leaving the screen doesn't stop it.
     */
    fun setSalesLines(v: SalesLines) {
        val c = vm.c
        c.scope.launch {
            c.settings.update { it.copy(screening = it.screening.copy(learnFromCalls = v.learn, silenceSalesLines = v.silence)) }
            catching { ReputationLearner.learn(c) }
        }
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }
}

/** The screen's state, with the clock ticking every half minute (the snooze, the emergency window, "ago" times). */
@Composable
internal fun rememberBlockingState(vm: AppViewModel): BlockingState {
    val scope = rememberCoroutineScope()
    val settings = vm.settings.collectAsStateWithLifecycle()
    val rules = vm.c.blocks.rules.collectAsStateWithLifecycle()
    val expanded = rememberSaveable { mutableStateOf(setOf<String>()) }
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now.longValue = System.currentTimeMillis()
        }
    }
    return remember(vm, scope) { BlockingState(vm, scope, settings, rules, expanded, now) }
}

/**
 * "Expecting a call?" with its durations, or the one running with Stop. It only lets unknown callers through, so it
 * waits, greyed, until unknown callers are silenced ([WhatDecides.silencesUnknown]).
 */
@Composable
internal fun ExpectingCallRow(st: BlockingState) {
    val context = LocalContext.current
    val s = st.screening
    val left = s.snoozeUntil - st.now
    val matters = WhatDecides.silencesUnknown(s)
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (left > 0) {
                InputChip(
                    selected = true, onClick = { st.launch { BlockingActions.snooze(st.vm.c, 0); ExpectingCallTileService.refresh(context) } },
                    label = { Text(stringResource(R.string.blk_snooze_active, leftText(context, left))) },
                    leadingIcon = { Icon(Icons.Rounded.HourglassTop, null) },
                    trailingIcon = { Text(stringResource(R.string.blk_stop), fontWeight = FontWeight.Bold) },
                )
            } else {
                Text(stringResource(R.string.blk_expecting_question), style = MaterialTheme.typography.labelLarge)
                snoozeChoices().forEach { (m, label) ->
                    AssistChip(
                        { st.launch { BlockingActions.snooze(st.vm.c, m); ExpectingCallTileService.refresh(context) } },
                        { Text(label) },
                        enabled = matters,
                    )
                }
            }
        }
        if (!matters && left <= 0) MattersOnceSilenced(Modifier.padding(horizontal = 16.dp))
    }
}

/** "Matters once unknown callers are silenced", under a row that waits for that. */
@Composable
private fun MattersOnceSilenced(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.blk_matters_once_silenced), modifier,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The emergency window's countdown, while one runs, with Reset. */
@Composable
internal fun EmergencyWindowCard(st: BlockingState) {
    val context = LocalContext.current
    val ends = remember(st.now) { ScreeningGuard.emergencyWindowEndsAt(context) } ?: return
    Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Emergency, null)
            Text("  " + stringResource(R.string.blk_emergency_window, leftText(context, ends - st.now)), Modifier.weight(1f))
            TextButton({ ScreeningGuard.clearEmergencyWindow(context); st.now = System.currentTimeMillis() }) {
                Text(stringResource(R.string.contact_page_reset))
            }
        }
    }
}

/** Hidden numbers, numbers not in your contacts, and what happens to a stopped call. */
@Composable
internal fun MainSwitches(st: BlockingState) {
    val context = LocalContext.current
    val s = st.screening
    val isDefault by st.vm.isDefaultDialer.collectAsStateWithLifecycle()
    BlockingCard {
        SwitchRow(
            stringResource(R.string.blk_hidden),
            stringResource(R.string.blk_hidden_help) + (s.hiddenSchedule?.let { " · ${BlockingText.schedule(context, it)}" } ?: ""),
            s.blockHidden,
            enabled = isDefault,
        ) { v -> st.setScreening { it.copy(blockHidden = v) } }
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
        ) { v -> st.setScreening { it.copy(blockNonContacts = v) } }
        ParleyListItem(
            headlineContent = { Text(stringResource(R.string.blk_when_stopped)) },
            supportingContent = {
                Column { ActionChoice(s.defaultAction, { a -> st.setScreening { it.copy(defaultAction = a) } }, Modifier.padding(top = 8.dp)) }
            },
        )
    }
}

/** The spam lists' hero card: how many, how fresh, and a list suggested for this SIM's country. */
@Composable
internal fun SpamListsCard(st: BlockingState, open: (Destination) -> Unit) {
    val vm = st.vm
    val res = LocalResources.current
    val lists by vm.c.lists.state.collectAsStateWithLifecycle()
    val sum = vm.c.lists.summarize(lists, st.now)
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
                        sum.updatedAt?.let { stringResource(R.string.blk_updated_ago, ago(it, st.now)) },
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
                    stringResource(R.string.blk_suggested_for_sim, suggestion.name), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row {
                    TextButton({ st.launch { vm.c.lists.installBuiltIn(suggestion); vm.toast(res.getString(R.string.blk_added, suggestion.name)) } }) {
                        Text(stringResource(R.string.agenda_add_save))
                    }
                    TextButton({ st.launch { vm.c.lists.dismissSuggestion(suggestion.id) } }) { Text(stringResource(R.string.blk_no_thanks)) }
                }
            }
        }
    }
}

/**
 * "What decides": the four sources a verdict can come from, each with its state, read-only (each is set where it
 * lives). The spam lists and a label's shield open their pages.
 */
@Composable
internal fun WhatDecidesSection(st: BlockingState, open: (Destination) -> Unit) {
    val vm = st.vm
    val res = LocalResources.current
    val rules = st.rules
    val lists by vm.c.lists.state.collectAsStateWithLifecycle()
    val shared by vm.c.sharedLabels.states.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.c.sharedLabels.load() }
    val shielded = shared.filter { it.shieldOn && SharedLabelMembership.syncs(it.membership) }
    val rows = WhatDecides.rows(
        blockRulesOn = rules.count { it.kind == RuleKind.BLOCK && it.enabled },
        allowRules = rules.count { it.kind == RuleKind.ALLOW },
        lists = lists.packs.size,
        settings = st.screening,
        shields = shielded.map { WhatDecides.Shield(it.title, it.shieldMode) },
    )
    val states = rows.map { r -> decidesState(res, r) }
    CollapsibleSection(
        stringResource(R.string.blk_decides_title), stringResource(R.string.blk_decides_help),
        rows.zip(states).filter { it.first.on }.map { (r, _) -> decidesName(res, r.source) },
        st.isOpen("decides"), { st.toggle("decides") }, Icons.Rounded.Rule,
    ) {
        rows.forEachIndexed { i, r ->
            val target: Destination? = when (r.source) {
                WhatDecides.Source.SPAM_LISTS -> BlockingRoutes.Lists
                WhatDecides.Source.FAMILY_SHIELD -> shielded.singleOrNull()?.let { SharedLabelRoutes.Shield(it.labelId) }
                else -> null
            }
            ParleyListItem(
                modifier = if (target != null) Modifier.clickable { open(target) } else Modifier,
                headlineContent = { Text(decidesName(res, r.source)) },
                supportingContent = { Text(states[i]) },
            )
        }
    }
}

private fun decidesName(res: android.content.res.Resources, s: WhatDecides.Source): String = res.getString(
    when (s) {
        WhatDecides.Source.YOUR_RULES -> R.string.blk_decides_rules
        WhatDecides.Source.SPAM_LISTS -> R.string.blk_check_spam_lists
        WhatDecides.Source.SALES_LINES -> R.string.blk_decides_sales
        WhatDecides.Source.FAMILY_SHIELD -> R.string.blk_decides_shield
    },
)

/** One "What decides" row's state, in words: "2 block rules, 1 allow rule", "Off", "On in Family (Warn)"… */
private fun decidesState(res: android.content.res.Resources, r: WhatDecides.Row): String {
    if (!r.on) return res.getString(R.string.dc_off)
    return when (r.source) {
        WhatDecides.Source.YOUR_RULES -> listOfNotNull(
            res.getQuantityString(R.plurals.blk_decides_block_rules, r.count, r.count).takeIf { r.count > 0 },
            res.getQuantityString(R.plurals.blk_decides_allow_rules, r.allowCount, r.allowCount).takeIf { r.allowCount > 0 },
        ).joinToString(res.getString(R.string.main_separator))
        WhatDecides.Source.SPAM_LISTS -> res.getQuantityString(R.plurals.blk_lists_count, r.count, r.count)
        WhatDecides.Source.SALES_LINES ->
            res.getString(if (r.sales == SalesLines.TAG_AND_SILENCE) R.string.set_sales_lines_silence else R.string.set_sales_lines_tag)
        WhatDecides.Source.FAMILY_SHIELD -> r.shields.joinToString(res.getString(R.string.main_separator)) { sh ->
            res.getString(R.string.blk_decides_shield_on, sh.label, FamilyShieldTexts.mode(res, sh.mode))
        }
    }
}

/**
 * "Always let through": repeat callers (greyed until unknown callers are silenced, the only time they matter),
 * numbers you called, people you talked to, and your allow rules.
 */
@Composable
internal fun AllowSection(st: BlockingState, open: (Destination) -> Unit) {
    val vm = st.vm
    val settings = st.settings
    val s = st.screening
    val rules = st.rules
    val allowRules = rules.filter { it.kind == RuleKind.ALLOW }
    val matters = WhatDecides.silencesUnknown(s)
    CollapsibleSection(
        stringResource(R.string.blk_allow_section), stringResource(Help.ALLOW),
        listOfNotNull(
            pluralStringResource(R.plurals.blk_sum_allowed, allowRules.size, allowRules.size).takeIf { allowRules.isNotEmpty() },
            stringResource(R.string.blk_repeat_callers).takeIf { settings.repeatCallerRingsThrough },
            stringResource(R.string.blk_numbers_you_called).takeIf { s.allowDialled },
            stringResource(R.string.blk_people_you_talked_to).takeIf { s.allowAnswered },
        ),
        st.isOpen("allow"), { st.toggle("allow") }, Icons.Rounded.VerifiedUser,
    ) {
        SwitchRow(
            stringResource(R.string.blk_repeat_callers),
            if (matters) {
                stringResource(R.string.blk_repeat_callers_help, s.repeatWindowMinutes, s.repeatMinIntervalSeconds)
            } else {
                stringResource(R.string.blk_matters_once_silenced)
            },
            settings.repeatCallerRingsThrough,
            enabled = matters,
        ) { v -> st.setSettings { it.copy(repeatCallerRingsThrough = v) } }
        if (matters && settings.repeatCallerRingsThrough) {
            LabeledSlider(
                stringResource(R.string.blk_repeat_window, s.repeatWindowMinutes), s.repeatWindowMinutes.toFloat(), 1f..15f, 13,
            ) { v -> st.setScreening { it.copy(repeatWindowMinutes = v.toInt()) } }
            LabeledSlider(
                stringResource(R.string.blk_repeat_min_interval, s.repeatMinIntervalSeconds), s.repeatMinIntervalSeconds.toFloat(), 0f..60f, 11,
            ) { v -> st.setScreening { it.copy(repeatMinIntervalSeconds = v.toInt()) } }
        }
        SwitchRow(
            stringResource(R.string.blk_numbers_you_called),
            pluralStringResource(R.plurals.blk_numbers_you_called_help, s.dialledDays, s.dialledDays),
            s.allowDialled,
        ) { v -> st.setScreening { it.copy(allowDialled = v) } }
        SwitchRow(
            stringResource(R.string.blk_people_you_talked_to),
            pluralStringResource(R.plurals.blk_people_you_talked_to_help, s.answeredDays, s.answeredMinSeconds, s.answeredDays),
            s.allowAnswered,
        ) { v -> st.setScreening { it.copy(allowAnswered = v) } }
        TextButton(
            { open(BlockingRoutes.rule(0, RuleKind.ALLOW)) }, Modifier.padding(horizontal = 8.dp),
        ) { Icon(Icons.Rounded.Add, null); Text(" " + stringResource(R.string.blk_allow_number_or_range)) }
        allowRules.forEach { r -> RuleRow(vm, r, st.now) { open(BlockingRoutes.rule(r.id)) } }
    }
}

/** Your block rules: how many are on, scheduled and how many calls they stopped; the rules themselves. */
@Composable
internal fun BlockRulesSection(st: BlockingState, open: (Destination) -> Unit) {
    val rules = st.rules
    val blockRules = rules.filter { it.kind == RuleKind.BLOCK }
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
        st.isOpen("block"), { st.toggle("block") }, Icons.Rounded.Rule,
    ) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton({ open(BlockingRoutes.rule(0, RuleKind.BLOCK)) }) {
                Icon(Icons.Rounded.Add, null)
                Text(" " + stringResource(R.string.blk_add_rule))
            }
        }
        if (blockRules.isEmpty()) Text(stringResource(R.string.blk_no_rules), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        blockRules.forEach { r -> RuleRow(st.vm, r, st.now) { open(BlockingRoutes.rule(r.id)) } }
    }
}

/** Off hours: when, who may still ring, what happens to everyone else, and the reply offered to them. */
@Composable
internal fun OffHoursSection(st: BlockingState) {
    val context = LocalContext.current
    val s = st.screening
    val oh = s.offHours
    CollapsibleSection(
        stringResource(R.string.blk_check_off_hours), stringResource(Help.OFF_HOURS),
        if (oh.enabled) {
            listOf(
                BlockingText.schedule(context, oh.schedule),
                stringResource(R.string.blk_only_who, offHoursWho(oh)),
                stringResource(if (oh.action == BlockAction.SILENCE) R.string.blk_action_silence else R.string.blk_action_reject),
            )
        } else {
            listOf(stringResource(R.string.dc_off))
        },
        st.isOpen("offhours"), { st.toggle("offhours") }, Icons.Rounded.Bedtime,
    ) {
        SwitchRow(stringResource(R.string.blk_use_off_hours), null, oh.enabled) { v -> st.setScreening { it.copy(offHours = it.offHours.copy(enabled = v)) } }
        ScheduleField(
            oh.schedule,
            { sc -> st.setScreening { it.copy(offHours = it.offHours.copy(schedule = sc ?: Schedule(Schedule.ALL_DAYS, 22 * 60, 7 * 60))) } },
            alwaysLabel = stringResource(R.string.blk_all_day),
        )
        OffHoursWho(st.vm, oh) { o -> st.setScreening { it.copy(offHours = o) } }
        ParleyListItem(
            headlineContent = { Text(stringResource(R.string.blk_everyone_else)) },
            supportingContent = {
                ActionChoice(oh.action, { a -> st.setScreening { it.copy(offHours = it.offHours.copy(action = a)) } }, Modifier.padding(top = 8.dp))
            },
        )
        SwitchRow(stringResource(R.string.blk_offer_reply), stringResource(R.string.blk_offer_reply_help, s.busyReplyText), s.busyReply) { v ->
            st.setScreening { it.copy(busyReply = v) }
        }
        if (s.busyReply) BusyReplyField(st)
    }
}

/** The reply's text, saved with its own button so half a sentence is never sent. */
@Composable
private fun BusyReplyField(st: BlockingState) {
    val saved = st.screening.busyReplyText
    var text by remember(saved) { mutableStateOf(saved) }
    OutlinedTextField(
        text, { text = it }, label = { Text(stringResource(R.string.blk_reply_text)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        trailingIcon = {
            if (text != saved && text.isNotBlank()) {
                TextButton({ st.setScreening { it.copy(busyReplyText = text.trim()) } }) { Text(stringResource(R.string.pin_save)) }
            }
        },
    )
}

/** More checks: spoofed neighbours, failed verification, numbers that can't exist, and when each is active. */
@Composable
internal fun MoreChecksSection(st: BlockingState) {
    val context = LocalContext.current
    val res = LocalResources.current
    val s = st.screening
    val numbersPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) st.setScreening { it.copy(blockNeighbourSpoofing = true) } else st.vm.toast(res.getString(R.string.blk_need_own_number))
    }
    CollapsibleSection(
        stringResource(R.string.blk_more_checks), stringResource(Help.MORE),
        listOfNotNull(
            stringResource(R.string.blk_check_neighbour).takeIf { s.blockNeighbourSpoofing },
            stringResource(R.string.blk_check_verification).takeIf { s.blockFailedVerification },
            stringResource(R.string.blk_invalid_numbers).takeIf { s.blockInvalid },
        ),
        st.isOpen("more"), { st.toggle("more") }, Icons.Rounded.Security,
    ) {
        SwitchRow(stringResource(R.string.blk_check_neighbour), stringResource(R.string.blk_neighbour_help), s.blockNeighbourSpoofing) { v ->
            if (v && !Permissions.has(context, Manifest.permission.READ_PHONE_NUMBERS)) numbersPermission.launch(Manifest.permission.READ_PHONE_NUMBERS)
            else st.setScreening { it.copy(blockNeighbourSpoofing = v) }
        }
        SwitchRow(stringResource(R.string.blk_failed_verification), stringResource(R.string.blk_failed_verification_help), s.blockFailedVerification) { v ->
            st.setScreening { it.copy(blockFailedVerification = v) }
        }
        SwitchRow(stringResource(R.string.blk_cant_exist), stringResource(R.string.blk_cant_exist_help), s.blockInvalid) { v ->
            st.setScreening { it.copy(blockInvalid = v) }
        }
        if (s.blockInvalid) ParleyListItem(
            headlineContent = { Text(stringResource(R.string.blk_invalid_are)) },
            supportingContent = { ActionChoice(s.invalidAction, { a -> st.setScreening { it.copy(invalidAction = a) } }, Modifier.padding(top = 8.dp)) },
        )
        Text(stringResource(R.string.blk_active), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        ToggleScheduleRow(stringResource(R.string.blk_hidden_numbers), s.hiddenSchedule) { sc -> st.setScreening { it.copy(hiddenSchedule = sc) } }
        ToggleScheduleRow(stringResource(R.string.blk_preset_known), s.nonContactsSchedule) { sc -> st.setScreening { it.copy(nonContactsSchedule = sc) } }
        ToggleScheduleRow(stringResource(R.string.blk_check_neighbour), s.neighbourSchedule) { sc -> st.setScreening { it.copy(neighbourSchedule = sc) } }
        ToggleScheduleRow(stringResource(R.string.blk_check_verification), s.verificationSchedule) { sc -> st.setScreening { it.copy(verificationSchedule = sc) } }
        ToggleScheduleRow(stringResource(R.string.blk_invalid_numbers), s.invalidSchedule) { sc -> st.setScreening { it.copy(invalidSchedule = sc) } }
    }
}

/**
 * From your calls and notes: sales lines your own calls tag (Off · Tag quietly · Tag and silence), and "Expecting a
 * call" turned on by notes, To call items and delivery QR codes. Settings' Blocking & spam page held these.
 */
@Composable
internal fun LearnedSection(st: BlockingState) {
    val vm = st.vm
    val s = st.screening
    val sales = SalesLines.of(s.learnFromCalls, s.silenceSalesLines)
    val salesChoices = listOf(stringResource(R.string.dc_off), stringResource(R.string.set_sales_lines_tag), stringResource(R.string.set_sales_lines_silence))
    CollapsibleSection(
        stringResource(R.string.blk_learned_title), stringResource(R.string.blk_learned_help),
        listOf(salesChoices[sales.ordinal]),
        st.isOpen("learned"), { st.toggle("learned") }, Icons.Rounded.Storefront,
    ) {
        val salesSub = if (sales == SalesLines.TAG_AND_SILENCE) stringResource(R.string.set_sales_lines_silence_sub) else null
        MenuRow(settingTitle("learn_from_calls"), salesChoices, sales.ordinal, Icons.Rounded.Storefront, salesSub) { i ->
            st.setSalesLines(SalesLines.entries[i])
        }
        // Notes, To call items and delivery QR codes turning "Expecting a call" on (off until accepted).
        ExpectedHintsRow(vm)
    }
}

@Composable
internal fun SoundsItem(st: BlockingState) {
    val s = st.screening
    CollapsibleSection(
        stringResource(R.string.blk_sounds), stringResource(Help.SOUNDS),
        listOfNotNull(
            stringResource(R.string.blk_sum_loud_favourites).takeIf { s.ringLoudFavourites },
            stringResource(R.string.blk_sum_loud_repeat).takeIf { s.ringLoudRepeat },
            stringResource(R.string.blk_verdict_blocked_reason, notifyLabelInline(s.notifyBlocked)),
        ),
        st.isOpen("sounds"), { st.toggle("sounds") }, Icons.Rounded.MusicNote,
    ) { SoundsSection(s) { f -> st.setScreening(f) } }
}

@Composable
internal fun EmergencyItem(st: BlockingState) {
    val s = st.screening
    CollapsibleSection(
        stringResource(R.string.blk_check_emergency), stringResource(Help.EMERGENCY),
        listOfNotNull(
            pluralStringResource(R.plurals.blk_sum_extra_numbers, s.emergencyExtras.size, s.emergencyExtras.size).takeIf { s.emergencyExtras.isNotEmpty() },
        ),
        st.isOpen("emergency"), { st.toggle("emergency") }, Icons.Rounded.Emergency,
    ) { EmergencySection(st.vm, s) { f -> st.setScreening(f) } }
}

/** Test a number, try your rules on past calls, rule templates, and importing or sharing rules. */
@Composable
internal fun ToolsSection(st: BlockingState, open: (Destination) -> Unit) {
    var testNumber by rememberSaveable { mutableStateOf("") }
    CollapsibleSection(
        stringResource(R.string.blk_tools), stringResource(Help.TOOLS), emptyList(), st.isOpen("tools"), { st.toggle("tools") }, Icons.Rounded.Build,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                testNumber, { testNumber = it }, label = { Text(stringResource(R.string.blk_test_a_number)) }, singleLine = true,
                textStyle = ltrTextStyle(), modifier = Modifier.weight(1f),
            )
            TextButton({ BlockingDialogs.show(BlockingDialog.Test(testNumber.trim())) }, enabled = testNumber.isNotBlank()) {
                Text(stringResource(R.string.blk_test))
            }
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

/** The phone's own block list (Android's), with Block a number and Unblock. */
@Composable
internal fun SystemListSection(st: BlockingState, onAdd: () -> Unit) {
    val vm = st.vm
    val system by vm.c.blocks.systemList.collectAsStateWithLifecycle()
    CollapsibleSection(
        stringResource(R.string.blk_system_list), stringResource(R.string.blk_system_list_help),
        listOf(pluralStringResource(R.plurals.set_numbers_count, system.size, system.size)), st.isOpen("system"), { st.toggle("system") }, Icons.Rounded.Block,
    ) {
        TextButton(onAdd, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Rounded.Add, null); Text(" " + stringResource(R.string.blk_block_a_number)) }
        if (!vm.c.blocks.canUseSystemList()) Text(
            stringResource(R.string.blk_need_default), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error,
        )
        system.forEach { b ->
            ParleyListItem(
                leadingContent = { Icon(Icons.Rounded.Block, null) },
                headlineContent = { Text(bidiLtr(Format.number(b.number, vm.countryIso))) },
                trailingContent = { IconButton({ vm.unblockNumber(b.number) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.blk_unblock)) } },
            )
        }
    }
}

/** The log's head: what it is, numbers likely spam for you (from your own calls), and Clear. */
@Composable
internal fun BlockedLogHead(st: BlockingState, logEmpty: Boolean) {
    val vm = st.vm
    val context = LocalContext.current
    val res = LocalResources.current
    val s = st.screening
    val rules = st.rules
    val calls by vm.c.history.calls.collectAsStateWithLifecycle()
    val dismissed = remember { mutableStateOf(setOf<String>()) }
    val suggestions = remember(calls, rules, s.reputationSuggestions, dismissed.value) {
        if (!s.reputationSuggestions) emptyList() else PersonalReputation.suggestions(
            calls.orEmpty().take(1500), System.currentTimeMillis(), countryOf = { e -> PhoneEnv.countryIso(context, e.accountId) },
        ) { n ->
            n in dismissed.value || vm.contactFor(n) != null || rules.any { r -> r.type.isNumberRule && CallPolicy.ruleMatches(r, n, vm.countryIso) }
        }.take(5)
    }
    Section(stringResource(R.string.blk_recent))
    Text(
        stringResource(Help.LOG), Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (suggestions.isNotEmpty()) {
        Text(
            stringResource(R.string.blk_likely_spam_for_you), style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
        suggestions.forEach { sg ->
            ParleyListItem(
                headlineContent = { Text(bidiLtr(Format.number(sg.number, vm.countryIso))) },
                supportingContent = { Text(stringResource(R.string.blk_sugg_line, BlockingText.suggestionReason(context, sg), ago(sg.lastAt, st.now))) },
                trailingContent = {
                    Row {
                        TextButton({ dismissed.value = dismissed.value + sg.number }) { Text(stringResource(R.string.blk_dismiss)) }
                        TextButton({ askToBlock(listOf(sg.number), note = res.getString(R.string.blk_likely_spam_for_you)) }) {
                            Text(stringResource(R.string.blk_block))
                        }
                    }
                },
            )
        }
    }
    if (logEmpty) Text(stringResource(R.string.blk_nothing_yet), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    else TextButton({ st.launch { vm.c.blocks.clearBlockedLog() } }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.blk_clear_log)) }
}
