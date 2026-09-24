package app.parley.ui.blocking

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.blocking.BlockingActions
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
                Icon(icon, when (s.mark) { TraceMark.FAILED_OPEN -> "Couldn't check"; TraceMark.MATCH -> "Matched"; else -> null }, tint = tint, modifier = Modifier.padding(end = 6.dp, top = 2.dp))
                Column {
                    Text((if (s.mark == TraceMark.FAILED_OPEN) "! " else "") + s.check, style = MaterialTheme.typography.labelLarge)
                    Text(s.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (live) "Test this call" else if (blocked) "Why was this blocked?" else "Why did this ring?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(Format.number(number, vm.countryIso), style = MaterialTheme.typography.titleSmall)
                when {
                    !loaded -> Text("Checking…")
                    stored != null -> {
                        val e = stored!!
                        Text("${Format.fullDate(LocalContext.current, e.time)} · ${e.verdict ?: if (e.allowed) "Rang" else "Blocked"}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                        TraceList(TraceCodec.decode(e.trace))
                        app.parley.ui.calls.RingFactsFor(vm, number, e.time, Modifier.padding(top = 8.dp))
                        if (e.failedOpen) Text("! Something couldn't be checked, so Parley let the call ring rather than risk blocking someone you know.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                    test != null -> {
                        val t = test!!
                        Text(
                            (if (live) "If this number called now: " else "No decision was stored for this call. With today's rules: ") + (if (t.blocked) "blocked" else "it would ring") + (t.verdict?.let { " · ${it.text}" } ?: ""),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                        )
                        TraceList(t.trace)
                        Text("Nothing was logged, counted or sent: this is only a test.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    else -> Text("Couldn't check this number.")
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        dismissButton = if (!live && stored != null) ({ TextButton({ BlockingDialogs.show(BlockingDialog.Test(number)) }) { Text("Test with today's rules") } }) else null,
    )
}

@Composable
private fun WebSearchDialog(vm: AppViewModel, d: BlockingDialog.WebSearch, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val url = vm.settings.collectAsStateWithLifecycle().value.screening.webSearchUrl
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search this number on the web?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your browser opens a search for ${Format.number(d.number, vm.countryIso)}. The search site will see the number; Parley itself sends nothing.")
                if (d.contactName != null) {
                    Text(
                        "This is ${d.contactName}'s number. Searching it tells the search site about one of your contacts.",
                        color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = { TextButton({ onDismiss(); BlockingActions.searchWeb(context, d.number, url) }) { Text(if (d.contactName != null) "Search anyway" else "Search") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
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
            title = { Text("Open ${regulator.name}?") },
            text = { Text("Your browser opens the official complaint page. The number is copied so you can paste it; Parley sends nothing itself.") },
            confirmButton = { TextButton({ onDismiss(); BlockingActions.openRegulator(context, regulator, number) }) { Text("Open") } },
            dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report this number") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Parley can't report anything by itself (it has no internet access). These open the right app with the details filled in.")
                Text(
                    "To your carrier: a text to ${BlockingActions.CARRIER_SPAM_SHORT_CODE} (US, UK, Canada, Ireland and others). Your SMS app opens; you press Send.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (regulator != null) Text("To ${regulator.name}: opens their complaint page in your browser.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                if (regulator != null) TextButton({ confirmRegulator = true }) { Text("Regulator") }
                TextButton({ onDismiss(); BlockingActions.reportToCarrier(context, number) }) { Text("Text ${BlockingActions.CARRIER_SPAM_SHORT_CODE}") }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PrefixAllowDialog(vm: AppViewModel, d: BlockingDialog.PrefixAllow, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(d.numbers.firstOrNull().orEmpty()) }
    var drop by remember { mutableIntStateOf(2) }
    val e164 = remember(chosen) { PhoneNumbers.toE164(chosen, vm.countryIso) ?: PhoneNumbers.clean(chosen) }
    val prefix = e164.dropLast(drop)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Also allow this office's other lines") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Calls from numbers that differ from ${d.name ?: "this contact"}'s number only in the last digits will always ring (a clinic's or a school's other lines).")
                if (d.numbers.size > 1) d.numbers.forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = n }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen == n, { chosen = n })
                        Text(Format.number(n, vm.countryIso))
                    }
                }
                Text("Digits that may differ", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 4).forEach { n -> FilterChip(drop == n, { drop = n }, label = { Text("$n") }) }
                }
                Text("Will allow $prefix" + "X".repeat(drop), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    BlockingActions.allowPrefix(vm.c, chosen, drop, d.name)
                    vm.toast("Other lines of ${d.name ?: "this office"} will ring")
                }
                onDismiss()
            }, enabled = chosen.isNotBlank() && prefix.count { it.isDigit() } >= 4) { Text("Allow") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LabelRuleDialog(vm: AppViewModel, d: BlockingDialog.LabelRule, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choice by remember { mutableIntStateOf(0) }
    val options = listOf(
        "Block everyone in '${d.title}'" to "Their calls are rejected. The label syncs across your phones with your contacts account.",
        "Only '${d.title}' rings during off hours" to "At night or at weekends (set the hours in Blocking › Off hours), everyone else is silenced.",
        "Ringtone for '${d.title}'" to "Parley's ringer plays a tone you choose for these people (unless they have their own). The same tone as on the label's page.",
    )
    // One store for label ringtones: the label page's.
    val people by vm.people.settings.collectAsStateWithLifecycle()
    var pickedTone by remember { mutableStateOf<String?>(null) }
    val tone = pickedTone ?: people.labelRingtones[d.title]
    val pickTone = rememberRingtonePicker { pickedTone = it }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Screening for '${d.title}'") },
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
                if (choice == 2) TextButton({ pickTone(tone) }) { Text(ringtoneTitle(LocalContext.current, tone) ?: "Choose ringtone") }
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
                    vm.toast("Saved")
                }
                onDismiss()
            }, enabled = choice != 2 || tone != null) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SnoozeDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expecting a call?") },
        text = { Text("Unknown numbers ring through your screening rules for a while (a delivery driver, a doctor's office). It switches itself off.") },
        confirmButton = {
            Row {
                listOf(30 to "30 min", 60 to "1 h", 120 to "2 h").forEach { (m, label) ->
                    TextButton({
                        scope.launch {
                            BlockingActions.snooze(vm.c, m)
                            ExpectingCallTileService.refresh(context)
                            vm.toast("Unknown callers will ring for $label")
                        }
                        onDismiss()
                    }) { Text(label) }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
