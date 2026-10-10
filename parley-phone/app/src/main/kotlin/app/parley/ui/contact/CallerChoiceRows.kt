package app.parley.ui.contact

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.CallerHaptics
import app.parley.common.calls.CallerHaptics.Preset
import app.parley.common.extras.CallerChoice
import app.parley.ui.ParleyDialog
import app.parley.ui.ParleyListItem
import app.parley.ui.Spacing
import app.parley.ui.SwitchRow
import kotlinx.coroutines.launch

/**
 * "Settings for this contact": the person's haptic caller ID (a vibration of their own, so the phone in a pocket says
 * who is calling), a ringtone made from their name ([onTune] sets it; null where the contact can't have one) and,
 * when Settings › Calls › Answer automatically › "For chosen people and labels" is on, whether their calls are
 * answered on their own; and "They never call me", which keeps "This number never calls you" on for their numbers. [key] is the Parley key: a private contact's choices are sealed in its
 * caller-ID copy (read while the phone is locked), a device contact's kept by Parley ([app.parley.data.extras.ExtrasStore]).
 */
@Composable
internal fun CallerChoiceRows(vm: AppViewModel, key: String, name: String, onTune: ((Uri) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val extras = vm.c.extras
    // Re-read when the device contacts' map changes; a private contact's are read from the vault each time.
    val deviceMap by extras.callerChoices.collectAsStateWithLifecycle()
    var round by rememberSaveable { mutableStateOf(0) }
    val choice by produceState(CallerChoice(), key, deviceMap, round) { value = extras.choiceFor(key) }
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    fun set(f: (CallerChoice) -> CallerChoice) = scope.launch {
        extras.setChoice(key, f)
        round++
    }

    Column {
        VibrationRow(choice.vibration, name, onClick = { picking = true })
        // Sonic caller ID: a ringtone made from the name (set where the page sets ringtones, so private ones too).
        if (onTune != null) CallerTuneRow(name, stringResource(R.string.caller_tune_summary), onTune)
        if (cfg.autoAnswerChosen) {
            AutoAnswerRow(choice.autoAnswer, stringResource(R.string.caller_auto_answer_summary)) { v -> set { it.copy(autoAnswer = v) } }
        }
        // A bank or a clinic that only ever takes your calls: a call "from" them is worth checking, every time.
        SwitchRow(
            stringResource(R.string.caller_never_calls), stringResource(R.string.caller_never_calls_summary), choice.neverCalls,
            icon = Icons.Rounded.Shield,
        ) { v -> set { it.copy(neverCalls = v) } }
    }
    if (picking) {
        VibrationPatternDialog(
            current = choice.vibration, name = name, seedKey = key,
            onPick = { spec -> picking = false; set { it.copy(vibration = spec) } },
            onDismiss = { picking = false },
        )
    }
}

/** The "Vibration" row: the chosen pattern's name, or the phone's usual vibration. */
@Composable
internal fun VibrationRow(spec: String?, name: String, onClick: () -> Unit) {
    val pattern = CallerHaptics.decode(spec)
    ParleyListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.Vibration, null) },
        headlineContent = { Text(stringResource(R.string.caller_vibration)) },
        supportingContent = {
            Text(if (pattern == null) stringResource(R.string.caller_vibration_usual) else patternName(pattern.preset, name))
        },
    )
}

/** "Answer automatically" for a person or a label (only shown while that option is on in Settings › Calls). */
@Composable
internal fun AutoAnswerRow(on: Boolean, summary: String, onChange: (Boolean) -> Unit) {
    SwitchRow(stringResource(R.string.caller_auto_answer), summary, on, icon = Icons.Rounded.PhoneInTalk, onChange = onChange)
}

@Composable
private fun patternName(preset: Preset, name: String): String = when (preset) {
    Preset.GENERATED -> stringResource(R.string.caller_vibration_generated)
    Preset.HEARTBEAT -> stringResource(R.string.caller_vibration_heartbeat)
    Preset.DOUBLE -> stringResource(R.string.caller_vibration_double)
    Preset.LONG -> stringResource(R.string.caller_vibration_long)
    Preset.MORSE -> CallerHaptics.morseLetter(name)?.let { stringResource(R.string.caller_vibration_morse_letter, it.toString()) }
        ?: stringResource(R.string.caller_vibration_morse)
}

/**
 * Choose a pattern: the phone's usual vibration, a rhythm of their own (made from [seedKey], so it stays the same), or
 * a preset. Each choice can be felt first with its play button.
 */
@Composable
internal fun VibrationPatternDialog(current: String?, name: String, seedKey: String, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val currentPattern = CallerHaptics.decode(current)
    // A generated pattern keeps the seed it was made with; a new one is made from the key.
    val generated = currentPattern?.takeIf { it.preset == Preset.GENERATED } ?: CallerHaptics.generatedFor(seedKey)
    val options: List<CallerHaptics.Pattern?> = listOf(null, generated) + Preset.entries.filter { it != Preset.GENERATED }.map { CallerHaptics.Pattern(it) }
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.caller_vibration)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.caller_vibration_body), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
                options.forEach { p ->
                    val selected = p?.preset == currentPattern?.preset
                    val label = if (p == null) stringResource(R.string.caller_vibration_usual) else patternName(p.preset, name)
                    val pick = { onPick(p?.let(CallerHaptics::encode)) }
                    ParleyListItem(
                        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = pick),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { RadioButton(selected, onClick = null) },
                        headlineContent = { Text(label) },
                        trailingContent = if (p == null) null else ({
                            IconButton({ HapticPreview.play(context, p, name) }) {
                                Icon(Icons.Rounded.PlayArrow, stringResource(R.string.caller_vibration_preview, label))
                            }
                        }),
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

/** Plays a pattern once, so it can be felt before it is chosen (the ring repeats it with a pause). */
internal object HapticPreview {
    fun play(context: Context, p: CallerHaptics.Pattern, name: String?) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createWaveform(CallerHaptics.timings(p, name), -1)) }
    }

    private fun vibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") // VibratorManager needs Android 12; this is the older path.
        context.getSystemService(Vibrator::class.java)
    }
}
