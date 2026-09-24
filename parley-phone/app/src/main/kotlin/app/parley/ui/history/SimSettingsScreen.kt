package app.parley.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.history.BillingIncrement
import app.parley.common.history.PlanConfig
import app.parley.common.history.PlanUsage
import app.parley.ui.contact.Section
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.parley.R
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings › SIMs: one row per SIM, with its plan meter when set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimListScreen(vm: AppViewModel, back: () -> Unit, open: (String) -> Unit) {
    val sims by vm.sims.collectAsStateWithLifecycle()
    val usage by vm.c.history.planUsage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.hist_sims_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            if (sims.isEmpty()) item { Text(stringResource(R.string.hist_sims_empty), Modifier.padding(16.dp)) }
            sims.forEach { sim ->
                item(key = sim.id) {
                    ListItem(
                        modifier = Modifier.clickable { open(HistoryRoutes.sim(sim.id)) },
                        leadingContent = {
                            SimPlanBadge(vm, sim.id) { Icon(Icons.Rounded.SimCard, null, tint = if (sim.color != 0) Color(sim.color) else Color.Unspecified) }
                        },
                        headlineContent = { Text(sim.label) },
                        supportingContent = { Text(usage[sim.id]?.let { HistoryText.planSummary(context, it) } ?: listOfNotNull(sim.subtitle, stringResource(R.string.hist_no_plan)).joinToString(" · ")) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) },
                    )
                }
            }
        }
    }
}

/** Per-SIM settings page. T8: the plan meter (allowance, cycle, billing increment, counted numbers). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimSettingsScreen(vm: AppViewModel, simId: String, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val plans by vm.c.history.plans.collectAsStateWithLifecycle()
    val usage by vm.c.history.planUsage.collectAsStateWithLifecycle()
    val sim = sims.firstOrNull { it.id == simId }
    val plan = plans.firstOrNull { it.simId == simId }
    fun save(p: PlanConfig) = scope.launch { vm.c.history.savePlan(p) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(sim?.label ?: stringResource(R.string.hist_filter_sim)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.dc_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item { Section(stringResource(R.string.hist_plan_section)) }
            item {
                ListItem(
                    modifier = Modifier.clickable { save((plan ?: PlanConfig(simId)).copy(enabled = plan?.enabled != true)) },
                    headlineContent = { Text(stringResource(R.string.hist_plan_track)) },
                    supportingContent = { Text(stringResource(R.string.hist_plan_track_summary)) },
                    trailingContent = { Switch(plan?.enabled == true, { v -> save((plan ?: PlanConfig(simId)).copy(enabled = v)) }) },
                )
            }
            if (plan != null && plan.enabled) {
                usage[simId]?.let { u -> item { UsageCard(u) } }
                item { PlanEditor(plan) { save(it) } }
            }
        }
    }
}

@Composable
private fun UsageCard(u: PlanUsage) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (u.isNear) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(HistoryText.planSummary(LocalContext.current, u), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { u.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Text(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).let { df ->
                    pluralStringResource(
                        R.plurals.hist_plan_detail, u.callsCounted, u.callsCounted, HistoryFormat.talk(u.talkSec), u.usedMinutes.toInt(),
                        df.format(u.cycleStart), df.format(u.cycleEnd.minusDays(1)),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanEditor(plan: PlanConfig, save: (PlanConfig) -> Unit) {
    Column {
        var minutes by remember(plan.simId) { mutableStateOf(plan.allowanceMinutes.toString()) }
        OutlinedTextField(
            minutes,
            { v ->
                minutes = v.filter { it.isDigit() }.take(6)
                minutes.toIntOrNull()?.takeIf { it > 0 }?.let { save(plan.copy(allowanceMinutes = it)) }
            },
            label = { Text(stringResource(R.string.hist_plan_minutes)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = minutes.toIntOrNull()?.let { it <= 0 } ?: true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        var dayMenu by remember { mutableStateOf(false) }
        ListItem(
            modifier = Modifier.clickable { dayMenu = true },
            headlineContent = { Text(stringResource(R.string.hist_plan_renews)) },
            supportingContent = { Text(stringResource(if (plan.cycleStartDay > 28) R.string.hist_plan_renews_day_last else R.string.hist_plan_renews_day, plan.cycleStartDay)) },
            trailingContent = {
                DropdownMenu(dayMenu, { dayMenu = false }) {
                    (1..31).forEach { d -> DropdownMenuItem({ Text(stringResource(R.string.hist_plan_day, d)) }, onClick = { dayMenu = false; save(plan.copy(cycleStartDay = d)) }) }
                }
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.hist_plan_billing)) },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.hist_plan_billing_summary))
                    SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                        BillingIncrement.entries.forEachIndexed { i, b ->
                            SegmentedButton(plan.increment == b, { save(plan.copy(increment = b)) }, SegmentedButtonDefaults.itemShape(i, BillingIncrement.entries.size)) {
                                Text(stringResource(HistoryText.increment(b)))
                            }
                        }
                    }
                }
            },
        )
        Section(stringResource(R.string.hist_plan_counted))
        Toggle(stringResource(R.string.hist_category_mobile), null, plan.countMobile) { save(plan.copy(countMobile = it)) }
        Toggle(stringResource(R.string.hist_category_landline), null, plan.countLandline) { save(plan.copy(countLandline = it)) }
        Toggle(stringResource(R.string.hist_plan_international), stringResource(R.string.hist_plan_international_summary), plan.countInternational) { save(plan.copy(countInternational = it)) }
        Toggle(stringResource(R.string.hist_plan_other), stringResource(R.string.hist_plan_other_summary), plan.countOther) { save(plan.copy(countOther = it)) }
        Toggle(stringResource(R.string.hist_plan_incoming), stringResource(R.string.hist_plan_incoming_summary), plan.countIncoming) { save(plan.copy(countIncoming = it)) }
        Text(
            stringResource(R.string.hist_plan_toll_free_note, plan.warnAtPercent),
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var warnMenu by remember { mutableStateOf(false) }
        ListItem(
            modifier = Modifier.clickable { warnMenu = true },
            headlineContent = { Text(stringResource(R.string.hist_plan_warn_at)) },
            supportingContent = { Text(stringResource(R.string.hist_plan_warn_at_summary, plan.warnAtPercent)) },
            trailingContent = {
                DropdownMenu(warnMenu, { warnMenu = false }) {
                    listOf(50, 70, 80, 90, 100).forEach { pc -> DropdownMenuItem({ Text(stringResource(R.string.hist_percent, pc)) }, onClick = { warnMenu = false; save(plan.copy(warnAtPercent = pc)) }) }
                }
            },
        )
    }
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

/** A dot on a SIM chip or button when that SIM's plan is near or over its allowance. */
@Composable
fun SimPlanBadge(vm: AppViewModel, simId: String, content: @Composable () -> Unit) {
    val usage by vm.c.history.planUsage.collectAsStateWithLifecycle()
    val u = usage[simId]
    if (u != null && u.isNear) {
        BadgedBox(badge = { Badge() }, modifier = Modifier) { content() }
    } else {
        content()
    }
}

/** "212 of 300 min used · 9 days left" for a SIM, or null when it has no plan. */
@Composable
fun simPlanSummary(vm: AppViewModel, simId: String): String? {
    val usage by vm.c.history.planUsage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    return usage[simId]?.let { HistoryText.planSummary(context, it) }
}
