package app.parley.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.CallExtrasConfig
import app.parley.ui.Destination
import app.parley.ui.ParleyDialog
import app.parley.ui.SegmentedGroup
import app.parley.ui.Spacing
import app.parley.ui.SwitchRow
import app.parley.ui.people.PeopleRoutes

/**
 * Settings › Calls › "Know who's calling": auto-answer (off by default; with a headset or Bluetooth, in simple mode, or
 * for people and labels chosen on their pages, after a few seconds with a countdown and Cancel) and where to give a
 * person or a label a vibration of their own.
 */
@Composable
internal fun CallerRingGroup(vm: AppViewModel, open: (Destination) -> Unit) {
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    val answerSub = autoAnswerSummary(cfg)
    val vibrationSub = stringResource(R.string.set_caller_vibration_sub)
    SegmentedGroup(stringResource(R.string.set_group_know_caller)) {
        linkRow("auto_answer", Icons.Rounded.PhoneInTalk, sub = answerSub) { editing = true }
        // Set on a person's page or a label's page; the labels list is one tap from here.
        linkRow("caller_vibration", Icons.Rounded.Vibration, sub = vibrationSub) { open(PeopleRoutes.Labels) }
    }
    if (editing) AutoAnswerDialog(vm, cfg) { editing = false }
}

/** "Off", or what is on: "With a headset · For chosen people · After 5 seconds". */
@Composable
private fun autoAnswerSummary(cfg: CallExtrasConfig): String {
    if (!AutoAnswer.enabled(cfg)) return stringResource(R.string.set_off)
    val parts = listOfNotNull(
        stringResource(R.string.set_auto_answer_headset).takeIf { cfg.autoAnswerHeadset },
        stringResource(R.string.set_auto_answer_simple).takeIf { cfg.autoAnswerSimple },
        stringResource(R.string.set_auto_answer_chosen).takeIf { cfg.autoAnswerChosen },
        pluralStringResource(R.plurals.set_auto_answer_after, cfg.autoAnswerSeconds, cfg.autoAnswerSeconds),
    )
    return parts.joinToString(stringResource(R.string.main_separator))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoAnswerDialog(vm: AppViewModel, cfg: CallExtrasConfig, onDismiss: () -> Unit) {
    fun set(f: (CallExtrasConfig) -> CallExtrasConfig) = vm.c.callExtras.update(transform = f)
    ParleyDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.PhoneInTalk, null) },
        title = { Text(stringResource(R.string.set_auto_answer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.set_auto_answer_body), style = MaterialTheme.typography.bodyMedium)
                SituationRow(
                    Icons.Rounded.Headset,
                    stringResource(R.string.set_auto_answer_headset),
                    stringResource(R.string.set_auto_answer_headset_sub),
                    cfg.autoAnswerHeadset,
                ) { v -> set { it.copy(autoAnswerHeadset = v) } }
                SituationRow(
                    Icons.Rounded.Accessibility,
                    stringResource(R.string.set_auto_answer_simple),
                    stringResource(R.string.set_auto_answer_simple_sub),
                    cfg.autoAnswerSimple,
                ) { v -> set { it.copy(autoAnswerSimple = v) } }
                SituationRow(
                    Icons.Rounded.People,
                    stringResource(R.string.set_auto_answer_chosen),
                    stringResource(R.string.set_auto_answer_chosen_sub),
                    cfg.autoAnswerChosen,
                ) { v -> set { it.copy(autoAnswerChosen = v) } }
                Text(
                    stringResource(R.string.set_auto_answer_delay), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs).semantics { heading() },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    AutoAnswer.SECONDS_CHOICES.forEach { s ->
                        FilterChip(
                            selected = cfg.autoAnswerSeconds == s,
                            onClick = { set { it.copy(autoAnswerSeconds = s) } },
                            label = { Text(pluralStringResource(R.plurals.set_auto_answer_seconds, s, s)) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_done)) } },
    )
}

@Composable
private fun SituationRow(icon: ImageVector, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(title, sub, on, icon = icon, onChange = onChange)
}
