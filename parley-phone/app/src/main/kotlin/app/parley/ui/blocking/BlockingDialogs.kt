package app.parley.ui.blocking

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import app.parley.common.BlockRule
import app.parley.common.OffHoursAllow
import app.parley.common.PhoneNumbers
import app.parley.common.RuleKind
import app.parley.common.RuleType
import app.parley.common.ScreeningResult
import app.parley.common.TraceCodec
import app.parley.common.TraceMark
import app.parley.common.TraceStep
import app.parley.data.db.BlockedCallEntity
import app.parley.ui.common.Format
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Dialogs any screen can open (Recents, number history, contact and label menus, notifications). */
sealed interface BlockingDialog {
    /** "Why did this ring? / Why was this blocked?": the stored trace, or a live test when none was stored. */
    data class Why(val number: String) : BlockingDialog

    /** "Test this call" (B13): today's rules, no side effects. */
    data class Test(val number: String) : BlockingDialog
    data class WebSearch(val number: String, val contactName: String?) : BlockingDialog
    data class Report(val number: String) : BlockingDialog
    data class PrefixAllow(val name: String?, val numbers: List<String>) : BlockingDialog
    /** Screening for a label, by title (the label in every account). */
    data class LabelRule(val title: String) : BlockingDialog
    data object Snooze : BlockingDialog
}

object BlockingDialogs {
    val request = MutableStateFlow<BlockingDialog?>(null)
    fun show(d: BlockingDialog) {
        request.value = d
    }
}

/** Hosted once, at the app root, next to the call dialogs. */
@Composable
fun BlockingDialogHost(vm: AppViewModel) {
    val d by BlockingDialogs.request.collectAsStateWithLifecycle()
    val dismiss = { BlockingDialogs.request.value = null }
    when (val x = d) {
        null -> Unit
        is BlockingDialog.Why -> WhyDialog(vm, x.number, live = false, dismiss)
        is BlockingDialog.Test -> WhyDialog(vm, x.number, live = true, dismiss)
        is BlockingDialog.WebSearch -> WebSearchDialog(vm, x, dismiss)
        is BlockingDialog.Report -> ReportDialog(vm, x.number, dismiss)
        is BlockingDialog.PrefixAllow -> PrefixAllowDialog(vm, x, dismiss)
        is BlockingDialog.LabelRule -> LabelRuleDialog(vm, x, dismiss)
        BlockingDialog.Snooze -> SnoozeDialog(vm, dismiss)
    }
}

/** One trace step per line, with an icon for "matched", "skipped" and "failed open" (!). */
@Composable
fun TraceList(steps: List<TraceStep>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { s ->
            Row(verticalAlignment = Alignment.Top) {
                val (icon, tint) = when (s.mark) {
                    TraceMark.MATCH -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
                    TraceMark.FAILED_OPEN -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
                    TraceMark.SKIPPED -> Icons.Rounded.RemoveCircleOutline to MaterialTheme.colorScheme.outline
                    TraceMark.PASS -> Icons.Rounded.RemoveCircleOutline to Color.Transparent
                }
                val context = LocalContext.current
                val cd = when (s.mark) {
                    TraceMark.FAILED_OPEN -> stringResource(R.string.blk_trace_failed_open)
                    TraceMark.MATCH -> stringResource(R.string.blk_trace_matched)
                    else -> null
                }
                Icon(icon, cd, tint = tint, modifier = Modifier.padding(end = 6.dp, top = 2.dp))
                Column {
                    Text((if (s.mark == TraceMark.FAILED_OPEN) "! " else "") + BlockingText.check(context, s.check), style = MaterialTheme.typography.labelLarge)
                    Text(BlockingText.result(context, s.check, s.result), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun WhyDialog(vm: AppViewModel, number: String, live: Boolean, onDismiss: () -> Unit) {
    var stored by remember { mutableStateOf<BlockedCallEntity?>(null) }
    var test by remember { mutableStateOf<ScreeningResult?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(number, live) {
        if (!live) {
            stored = vm.c.blocks.screenedCalls.first().firstOrNull { it.number != null && PhoneNumbers.same(it.number, number, vm.countryIso) }
        }
        if (live || stored == null) test = runCatching { vm.c.screener.test(number) }.getOrNull()
        loaded = true
    }
    val blocked = stored?.let { !it.allowed } ?: test?.blocked ?: false
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (live) R.string.blk_why_test else if (blocked) R.string.blk_why_blocked else R.string.blk_why_rang)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(bidiLtr(Format.number(number, vm.countryIso)), style = MaterialTheme.typography.titleSmall)
                when {
                    !loaded -> Text(stringResource(R.string.blk_checking))
                    stored != null -> {
                        val e = stored!!
                        val v = BlockingText.verdict(context, e.verdict) ?: stringResource(if (e.allowed) R.string.blk_rang else R.string.blk_blocked)
                        Text(stringResource(R.string.blk_joined, Format.fullDate(context, e.time), v), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        TraceList(TraceCodec.decode(e.trace))
                        app.parley.ui.calls.RingFactsFor(vm, number, e.time, Modifier.padding(top = 8.dp))
                        if (e.failedOpen) Text(stringResource(R.string.blk_failed_open_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                    test != null -> {
                        val t = test!!
                        val outcome = stringResource(if (t.blocked) R.string.blk_outcome_blocked else R.string.blk_outcome_ring)
                        val line = stringResource(if (live) R.string.blk_if_called_now else R.string.blk_no_decision_stored, outcome)
                        Text(
                            line + (t.verdict?.let { " · " + BlockingText.verdict(context, it.text) } ?: ""),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                        )
                        TraceList(t.trace)
                        Text(stringResource(R.string.blk_only_a_test), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    else -> Text(stringResource(R.string.blk_couldnt_check))
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ct_close)) } },
        dismissButton = if (!live && stored != null) ({ TextButton({ BlockingDialogs.show(BlockingDialog.Test(number)) }) { Text(stringResource(R.string.blk_test_today)) } }) else null,
    )
}

@Composable
private fun WebSearchDialog(vm: AppViewModel, d: BlockingDialog.WebSearch, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url = vm.settings.collectAsStateWithLifecycle().value.screening.webSearchUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blk_web_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.blk_web_body, bidiLtr(Format.number(d.number, vm.countryIso))))
                if (d.contactName != null) {
                    Text(
                        stringResource(R.string.blk_web_contact_warning, d.contactName),
                        color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = { TextButton({ onDismiss(); BlockingActions.searchWeb(context, d.number, url) }) { Text(stringResource(if (d.contactName != null) R.string.blk_search_anyway else R.string.blk_search)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}

@Composable
private fun ReportDialog(vm: AppViewModel, number: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val regulator = remember { BlockingActions.regulatorFor(vm.countryIso) }
    var confirmRegulator by remember { mutableStateOf(false) }
    if (confirmRegulator && regulator != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.blk_open_regulator, regulator.name)) },
            text = { Text(stringResource(R.string.blk_open_regulator_body)) },
            confirmButton = { TextButton({ onDismiss(); BlockingActions.openRegulator(context, regulator, number) }) { Text(stringResource(R.string.blk_open)) } },
            dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blk_report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.blk_report_body))
                Text(
                    stringResource(R.string.blk_report_carrier, bidiLtr(BlockingActions.CARRIER_SPAM_SHORT_CODE)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (regulator != null) Text(stringResource(R.string.blk_report_regulator, regulator.name), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                if (regulator != null) TextButton({ confirmRegulator = true }) { Text(stringResource(R.string.blk_regulator)) }
                TextButton({ onDismiss(); BlockingActions.reportToCarrier(context, number) }) { Text(stringResource(R.string.blk_text_code, bidiLtr(BlockingActions.CARRIER_SPAM_SHORT_CODE))) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}

@Composable
private fun PrefixAllowDialog(vm: AppViewModel, d: BlockingDialog.PrefixAllow, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(d.numbers.firstOrNull().orEmpty()) }
    var drop by remember { mutableIntStateOf(2) }
    val e164 = remember(chosen) { PhoneNumbers.toE164(chosen, vm.countryIso) ?: PhoneNumbers.clean(chosen) }
    val prefix = e164.dropLast(drop)
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blk_prefix_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (d.name != null) stringResource(R.string.blk_prefix_body_named, d.name) else stringResource(R.string.blk_prefix_body))
                if (d.numbers.size > 1) d.numbers.forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = n }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen == n, { chosen = n })
                        Text(bidiLtr(Format.number(n, vm.countryIso)))
                    }
                }
                Text(stringResource(R.string.blk_prefix_digits), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 4).forEach { n -> FilterChip(drop == n, { drop = n }, label = { Text("$n") }) }
                }
                Text(stringResource(R.string.blk_prefix_will_allow, bidiLtr(prefix + "X".repeat(drop))), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    BlockingActions.allowPrefix(vm.c, chosen, drop, d.name)
                    vm.toast(if (d.name != null) res.getString(R.string.blk_prefix_done_named, d.name) else res.getString(R.string.blk_prefix_done))
                }
                onDismiss()
            }, enabled = chosen.isNotBlank() && prefix.count { it.isDigit() } >= 4) { Text(stringResource(R.string.blk_allow)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}

@Composable
private fun LabelRuleDialog(vm: AppViewModel, d: BlockingDialog.LabelRule, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choice by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val options = listOf(
        stringResource(R.string.blk_label_block_all, d.title) to stringResource(R.string.blk_label_block_all_help),
        stringResource(R.string.blk_label_only_off_hours, d.title) to stringResource(R.string.blk_label_only_off_hours_help),
        stringResource(R.string.blk_label_ringtone, d.title) to stringResource(R.string.blk_label_ringtone_help),
    )
    // One store for label ringtones: the label page's.
    val people by vm.people.settings.collectAsStateWithLifecycle()
    var pickedTone by remember { mutableStateOf<String?>(null) }
    val tone = pickedTone ?: people.labelRingtones[d.title]
    val pickTone = rememberRingtonePicker { pickedTone = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blk_label_title, d.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEachIndexed { i, (t, help) ->
                    Row(Modifier.fillMaxWidth().clickable { choice = i }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(choice == i, { choice = i })
                        Column {
                            Text(t)
                            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (choice == 2) TextButton({ pickTone(tone) }) { Text(ringtoneTitle(LocalContext.current, tone) ?: stringResource(R.string.blk_choose_ringtone)) }
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    when (choice) {
                        0 -> vm.c.blocks.saveRule(BlockRule(pattern = d.title, type = RuleType.LABEL, label = d.title, kind = RuleKind.BLOCK))
                        1 -> vm.c.settings.update {
                            it.copy(screening = it.screening.copy(offHours = it.screening.offHours.copy(enabled = true, allow = OffHoursAllow.LABEL, labelId = null, labelTitle = d.title)))
                        }
                        // Only the ringtone: no allow rule (which would also let the label ring through off hours).
                        2 -> pickedTone?.let { t -> vm.people.update { s -> s.copy(labelRingtones = s.labelRingtones + (d.title to t)) } }
                    }
                    vm.toast(res.getString(R.string.blk_saved))
                }
                onDismiss()
            }, enabled = choice != 2 || tone != null) { Text(stringResource(R.string.set_save)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}

@Composable
private fun SnoozeDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blk_expecting_question)) },
        text = { Text(stringResource(R.string.blk_snooze_body)) },
        confirmButton = {
            Row {
                snoozeChoices().forEach { (m, label) ->
                    TextButton({
                        scope.launch {
                            BlockingActions.snooze(vm.c, m)
                            ExpectingCallTileService.refresh(context)
                            vm.toast(res.getString(R.string.blk_snooze_toast, label))
                        }
                        onDismiss()
                    }) { Text(label) }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}
