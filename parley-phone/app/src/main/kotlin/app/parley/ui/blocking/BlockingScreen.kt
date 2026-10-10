package app.parley.ui.blocking

import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.parley.common.catching
import app.parley.ui.Destination
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingActions
import app.parley.blocking.BlockingText
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Checkbox
import app.parley.common.BlockAction
import app.parley.common.TraceCodec
import app.parley.common.blocking.ScreeningPreset
import app.parley.common.blocking.ScreeningWeek
import app.parley.common.blocking.ScreeningWeekly
import app.parley.common.blocking.StoppedCall
import app.parley.data.db.BlockedCallEntity
import app.parley.ui.ParleyListItem
import app.parley.ui.calls.RingFactsFor
import app.parley.ui.common.Format
import app.parley.ui.segmentShape
import app.parley.ui.settings.bidiLtr
import kotlinx.coroutines.launch
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

/**
 * Blocking & screening: the setups and the week, the switches used most, the spam lists, then the rule lists and
 * fine-tuning under Advanced, and the log. Settings › Blocking & spam opens this screen itself. Each part is its own
 * composable (BlockingSections.kt); they share [BlockingState].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingScreen(vm: AppViewModel, back: () -> Unit, open: (Destination) -> Unit = {}) {
    val st = rememberBlockingState(vm)
    val log by vm.c.blocks.blockedCalls.collectAsStateWithLifecycle(emptyList())
    var presetToApply by remember { mutableStateOf<ScreeningPreset?>(null) }
    var addNumber by rememberSaveable { mutableStateOf(false) }

    // Scroll-linked top-bar tint.
    val barScroll = TopAppBarDefaults.pinnedScrollBehavior()
    ParleyScaffold(modifier = Modifier.nestedScroll(barScroll.nestedScrollConnection), topBar = {
        ParleyTopBar(stringResource(R.string.blk_title), onBack = back, scrollBehavior = barScroll)
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            // The setups first, with the one you're on named and what the last week looked like.
            item(key = "presets") { PresetsItem(st, log) { presetToApply = it } }
            item(key = "status") { ScreeningStatusCard(vm) }
            // "Expecting a call" chip in the header.
            item(key = "snooze") { ExpectingCallRow(st) }
            // Visible emergency countdown.
            item(key = "emergency-live") { EmergencyWindowCard(st) }
            item(key = "main") { MainSwitches(st) }
            item(key = "lists") { SpamListsCard(st, open) }

            // The rule lists and fine-tuning, under the setups and the spam lists.
            item(key = "advanced") { ListSectionHeader(stringResource(R.string.blk_advanced), top = 16.dp, bottom = 0.dp) }
            // Where a verdict can come from, side by side: your rules, spam lists, sales lines, the family shield.
            item(key = "decides") { WhatDecidesSection(st, open) }
            item(key = "allow") { AllowSection(st, open) }
            item(key = "block") { BlockRulesSection(st, open) }
            item(key = "offhours") { OffHoursSection(st) }
            item(key = "more") { MoreChecksSection(st) }
            // Sales lines learnt from your calls, and "Expecting a call" from notes (they were on Settings' page).
            item(key = "learned") { LearnedSection(st) }
            item(key = "sounds") { SoundsItem(st) }
            item(key = "emergency") { EmergencyItem(st) }
            item(key = "tools") { ToolsSection(st, open) }
            item(key = "system") { SystemListSection(st) { addNumber = true } }

            // ---- Blocked log ----
            item(key = "log-head") { BlockedLogHead(st, logEmpty = log.isEmpty()) }
            val shown = log.take(100)
            itemsIndexed(shown, key = { _, e -> "l" + e.id }) { i, e ->
                // The log as one segmented group.
                BlockingCard(segmentShape(i, shown.size), vertical = 1.dp) { BlockedLogRow(vm, e, networkNames = st.settings.rememberNetworkNames) }
            }
        }
    }

    presetToApply?.let { pr -> PresetDialog(st, pr) { presetToApply = null } }
    if (addNumber) AddNumberDialog(vm) { addNumber = false }
}

/** The setups, the one you're on named, and what the last week looked like. */
@Composable
private fun PresetsItem(st: BlockingState, log: List<BlockedCallEntity>, pick: (ScreeningPreset) -> Unit) {
    val vm = st.vm
    val now = st.now
    // Every stopped call of the week (the log shows only the latest 500), contacts counted as people.
    val shown = remember(log, now) { weekOf(log, now) { vm.contactFor(it)?.lookupKey } }
    val week by produceState(shown, log.firstOrNull()?.id, log.size, now) {
        value = withContext(Dispatchers.IO) {
            val all = catching { vm.c.blocks.blockedSince(now - ScreeningWeekly.WEEK_MS) }.getOrDefault(log)
            weekOf(all, now) { vm.contactFor(it)?.lookupKey }
        }
    }
    PresetHeader(ScreeningPreset.current(st.screening), week, pick)
}

/** "Use this setup?": what the setup would replace is said here, and can be kept. */
@Composable
private fun PresetDialog(st: BlockingState, pr: ScreeningPreset, close: () -> Unit) {
    val context = LocalContext.current
    val removed = pr.removedSchedule(st.screening)
    var keep by remember(pr) { mutableStateOf(false) }
    ConfirmDialog(
        title = stringResource(pr.title),
        text = stringResource(pr.help),
        confirmLabel = stringResource(R.string.blk_use_this),
        onConfirm = { st.setSettings { pr.apply(it, keepSchedule = keep) }; close() },
        onDismiss = close,
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

/** "Block a number" for the phone's own block list. */
@Composable
private fun AddNumberDialog(vm: AppViewModel, close: () -> Unit) {
    var n by rememberSaveable { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(R.string.blk_block_a_number),
        text = null,
        confirmLabel = stringResource(R.string.blk_block),
        onConfirm = {
            if (n.isNotBlank()) vm.blockNumber(n.trim())
            close()
        },
        onDismiss = close,
        dismissLabel = stringResource(R.string.set_cancel),
        content = {
            OutlinedTextField(n, { n = it }, label = { Text(stringResource(R.string.blk_phone_number)) }, singleLine = true, textStyle = ltrTextStyle())
        },
    )
}

/** Expandable blocked-log row: the stored trace, plus "Not spam", allow for a day, report, delete. */
@Composable
internal fun BlockedLogRow(vm: AppViewModel, e: BlockedCallEntity, networkNames: Boolean) {
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
                        // The network's name, kept with the entry, shows only while names from the network are remembered.
                        e.callerName.takeIf { networkNames },
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
internal fun weekOf(log: List<BlockedCallEntity>, now: Long, person: (String) -> String?): ScreeningWeek = ScreeningWeekly.summarize(
    log.filter { it.time >= now - ScreeningWeekly.WEEK_MS }.map { e ->
        val contact = ScreeningWeekly.fromContact(TraceCodec.decode(e.trace))
        StoppedCall(
            e.time, e.number, silenced = e.action != BlockAction.REJECT.name, fromContact = contact,
            person = if (contact) e.number?.let(person) else null,
        )
    },
    now,
)
