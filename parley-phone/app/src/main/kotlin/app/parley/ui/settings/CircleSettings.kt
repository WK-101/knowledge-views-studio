package app.parley.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.circle.CircleConfig
import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.LogMode
import app.parley.common.circle.ReminderDelivery
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.ui.circle.CircleText

/**
 * Settings › Contacts › Birthdays & dates: the Circle's rows (R3 "Log this?", R4 delivery and weekly cap, R5 lead
 * time). [cfg] is read by the page, so the rows only exist when they apply.
 */
fun SegmentedGroupScope.circleSettingRows(vm: AppViewModel, cfg: CircleConfig, birthdays: Boolean, nudges: Boolean) {
    if (birthdays) item("date_lead") {
        val options = CircleConfig.LEAD_CHOICES.map { d -> if (d == 0) stringResource(R.string.circle_lead_on_day) else pluralStringResource(R.plurals.circle_lead_days, d, d) }
        MenuRow(settingTitle("date_lead"), options, CircleConfig.LEAD_CHOICES.indexOf(cfg.dateLeadDays).coerceAtLeast(0), Icons.Rounded.Event, settingSummary("date_lead")) { i ->
            vm.c.circle.updateConfig { it.copy(dateLeadDays = CircleConfig.LEAD_CHOICES[i]) }
        }
    }
    if (nudges) {
        item("circle_delivery") {
            val options = listOf(stringResource(R.string.circle_delivery_digest), stringResource(R.string.circle_delivery_as_due))
            MenuRow(settingTitle("circle_delivery"), options, cfg.delivery.ordinal, Icons.Rounded.Tune, settingSummary("circle_delivery")) { i ->
                vm.c.circle.updateConfig { it.copy(delivery = ReminderDelivery.entries[i]) }
            }
        }
        if (cfg.delivery == ReminderDelivery.AS_DUE) item("circle_weekly_cap") {
            MenuRow(settingTitle("circle_weekly_cap"), CircleConfig.CAP_CHOICES.map { app.parley.ui.Bidi.ltr(it.toString()) }, CircleConfig.CAP_CHOICES.indexOf(cfg.weeklyCap).coerceAtLeast(0), Icons.Rounded.Speed, settingSummary("circle_weekly_cap")) { i ->
                vm.c.circle.updateConfig { it.copy(weeklyCap = CircleConfig.CAP_CHOICES[i]) }
            }
        }
    }
    item("log_prompts") { LogPromptsRow(vm, cfg) }
}

/** R6: the People card in Insights and its "who reaches out first" part (Settings › Recents & history). */
fun SegmentedGroupScope.peopleCardRows(vm: AppViewModel, cfg: CircleConfig) {
    switchRow("people_card", cfg.peopleCard, Icons.Rounded.Groups) { v -> vm.c.circle.updateConfig { it.copy(peopleCard = v) } }
    if (cfg.peopleCard) switchRow("first_mover", cfg.firstMover, Icons.Rounded.SwapHoriz) { v -> vm.c.circle.updateConfig { it.copy(firstMover = v) } }
}

/** R8/R9/X1: "Remember what matters" (Settings › Calls): the memory prompt, notes on the lock screen, the peek. */
@Composable
fun MemorySettingsGroup(vm: AppViewModel) {
    val cfg by vm.c.circle.config.collectAsStateWithLifecycle()
    SegmentedGroup(stringResource(R.string.c2_set_group_memory)) {
        switchRow("memory_prompt", cfg.memoryPrompt, Icons.AutoMirrored.Rounded.NoteAdd) { v -> vm.c.circle.updateConfig { it.copy(memoryPrompt = v) } }
        switchRow("memory_lock_screen", cfg.memoryOnLockScreen, Icons.Rounded.Lock) { v -> vm.c.circle.updateConfig { it.copy(memoryOnLockScreen = v) } }
        switchRow("pre_call_peek", cfg.preCallPeek, Icons.Rounded.Visibility) { v -> vm.c.circle.updateConfig { it.copy(preCallPeek = v) } }
    }
}

/** R3: Always / Ask / Never per channel. */
@Composable
private fun LogPromptsRow(vm: AppViewModel, cfg: CircleConfig) {
    val res = LocalResources.current
    var open by remember { mutableStateOf(false) }
    LinkRow(settingTitle("log_prompts"), settingSummary("log_prompts"), Icons.Rounded.Handshake) { open = true }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(settingTitle("log_prompts")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.circle_log_prompts_help), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                InteractionChannel.entries.forEach { ch ->
                    Text(CircleText.channel(res, ch), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 4.dp)) {
                        LogMode.entries.forEach { m ->
                            FilterChip(
                                cfg.logMode(ch) == m, { vm.c.circle.updateConfig { it.withLogMode(ch, m) } },
                                label = { Text(CircleText.mode(res, m)) }, modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.main_done)) } },
    )
}
