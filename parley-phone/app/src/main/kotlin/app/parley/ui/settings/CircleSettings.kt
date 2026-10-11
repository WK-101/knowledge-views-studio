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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
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
import app.parley.ui.Bidi
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.ui.circle.CircleText
import app.parley.ui.LinkRow
import app.parley.ui.MenuRow
import app.parley.common.circle.PeopleCardChoice
import app.parley.ui.ParleyDialog

/** Settings › Reminders › Birthdays and dates: how early date reminders come, shown while they're on. */
fun SegmentedGroupScope.dateLeadRow(vm: AppViewModel, cfg: CircleConfig, birthdays: Boolean) {
    if (birthdays) item("date_lead") {
        val options = CircleConfig.LEAD_CHOICES.map { d -> if (d == 0) stringResource(R.string.circle_lead_on_day) else pluralStringResource(R.plurals.circle_lead_days, d, d) }
        MenuRow(settingTitle("date_lead"), options, CircleConfig.LEAD_CHOICES.indexOf(cfg.dateLeadDays).coerceAtLeast(0), Icons.Rounded.Event, settingSummary("date_lead")) { i ->
            vm.c.circle.updateConfig { it.copy(dateLeadDays = CircleConfig.LEAD_CHOICES[i]) }
        }
    }
}

/**
 * Settings › Reminders › Keep in touch: how the reminders arrive and the weekly cap. [cfg] is read by the page,
 * so the rows only exist when they apply.
 */
fun SegmentedGroupScope.keepInTouchRows(vm: AppViewModel, cfg: CircleConfig, nudges: Boolean) {
    if (nudges) {
        item("circle_delivery") {
            val options = listOf(stringResource(R.string.circle_delivery_digest), stringResource(R.string.circle_delivery_as_due))
            MenuRow(settingTitle("circle_delivery"), options, cfg.delivery.ordinal, Icons.Rounded.Tune, settingSummary("circle_delivery")) { i ->
                vm.c.circle.updateConfig { it.copy(delivery = ReminderDelivery.entries[i]) }
            }
        }
        if (cfg.delivery == ReminderDelivery.AS_DUE) item("circle_weekly_cap") {
            MenuRow(settingTitle("circle_weekly_cap"), CircleConfig.CAP_CHOICES.map { Bidi.ltr(it.toString()) }, CircleConfig.CAP_CHOICES.indexOf(cfg.weeklyCap).coerceAtLeast(0), Icons.Rounded.Speed, settingSummary("circle_weekly_cap")) { i ->
                vm.c.circle.updateConfig { it.copy(weeklyCap = CircleConfig.CAP_CHOICES[i]) }
            }
        }
    }
}

/** Settings › Contacts › Circle: "Log this?" after a chat or video call Parley opened. */
fun SegmentedGroupScope.logPromptsRow(vm: AppViewModel, cfg: CircleConfig) = item("log_prompts") { LogPromptsRow(vm, cfg) }

/**
 * The People card in Insights, one choice: off, on, or on with "who usually reaches out first" (Settings › Recents &
 * history; the card's own ⋮ changes the same values).
 */
fun SegmentedGroupScope.peopleCardRow(vm: AppViewModel, cfg: CircleConfig) = item("people_card") {
    val choices = listOf(stringResource(R.string.dc_off), stringResource(R.string.dc_on), stringResource(R.string.set_circle_first_mover_title))
    MenuRow(settingTitle("people_card"), choices, PeopleCardChoice.of(cfg).ordinal, Icons.Rounded.Groups, settingSummary("people_card")) { i ->
        vm.c.circle.updateConfig { PeopleCardChoice.entries[i].applyTo(it) }
    }
}

/**
 * "Remember what matters" (Settings › Calls): the memory prompt and the peek. Notes on the lock screen are Privacy ›
 * Caller on the lock screen's "Name and notes".
 */
@Composable
fun MemorySettingsGroup(vm: AppViewModel) {
    val cfg by vm.c.circle.config.collectAsStateWithLifecycle()
    SegmentedGroup(stringResource(R.string.set_circle_group_memory)) {
        switchRow("memory_prompt", cfg.memoryPrompt, Icons.AutoMirrored.Rounded.NoteAdd) { v -> vm.c.circle.updateConfig { it.copy(memoryPrompt = v) } }
        switchRow("pre_call_peek", cfg.preCallPeek, Icons.Rounded.Visibility) { v -> vm.c.circle.updateConfig { it.copy(preCallPeek = v) } }
    }
}

/** Always / Ask / Never per channel. */
@Composable
private fun LogPromptsRow(vm: AppViewModel, cfg: CircleConfig) {
    val res = LocalResources.current
    var open by remember { mutableStateOf(false) }
    LinkRow(settingTitle("log_prompts"), settingSummary("log_prompts"), Icons.Rounded.Handshake) { open = true }
    if (!open) return
    ParleyDialog(
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
        confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.dc_done)) } },
    )
}
