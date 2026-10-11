package app.parley.ui.calltime

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import app.parley.R
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.security.AppLock
import app.parley.security.VaultSession
import app.parley.ui.ParleyDialog

/**
 * Supervised mode: limits can only be changed after proving presence with the app lock (fingerprint,
 * face or screen lock). One unlock is good for a couple of minutes.
 */
@Composable
fun rememberSupervisedGate(config: CallingConfig): (String, () -> Unit) -> Unit {
    val activity = LocalActivity.current as? ComponentActivity
    return { why, action ->
        if (!config.supervised || VaultSession.recentlyAuthenticated(SUPERVISED_WINDOW_MS)) {
            action()
        } else if (activity != null) {
            AppLock.confirm(activity, why) { ok -> if (ok) action() }
        }
    }
}

private const val SUPERVISED_WINDOW_MS = 2 * 60_000L

/** Editor for one limit rule: per call, per day, per week; incoming and/or outgoing. */
@Composable
fun LimitRuleDialog(
    title: String,
    rule: LimitRule,
    onSave: (LimitRule) -> Unit,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var perCall by rememberSaveable { mutableStateOf(rule.perCallMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
    var daily by rememberSaveable { mutableStateOf(rule.dailyMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
    var weekly by rememberSaveable { mutableStateOf(rule.weeklyMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
    var incoming by rememberSaveable { mutableStateOf(rule.incoming) }
    var outgoing by rememberSaveable { mutableStateOf(rule.outgoing) }
    fun minutes(s: String) = s.trim().toIntOrNull()?.coerceIn(0, MAX_MINUTES) ?: 0
    ParleyDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ct_editor_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MinutesField(stringResource(R.string.ct_minutes_per_call), perCall) { perCall = it }
                MinutesField(stringResource(R.string.ct_minutes_per_day), daily) { daily = it }
                MinutesField(stringResource(R.string.ct_minutes_per_week), weekly) { weekly = it }
                Text(
                    stringResource(R.string.ct_editor_allowance_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CheckRow(stringResource(R.string.set_group_incoming), incoming) { incoming = it; if (!it && !outgoing) outgoing = true }
                CheckRow(stringResource(R.string.ct_outgoing_calls), outgoing) { outgoing = it; if (!it && !incoming) incoming = true }
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton({
                onSave(
                    rule.copy(
                        perCallMinutes = minutes(perCall),
                        dailyMinutes = minutes(daily),
                        weeklyMinutes = minutes(weekly),
                        incoming = incoming,
                        outgoing = outgoing,
                    ),
                )
                onDismiss()
            }) { Text(stringResource(R.string.pin_save)) }
        },
        dismissButton = {
            Row {
                if (!rule.isEmpty) TextButton({ onSave(rule.copy(perCallMinutes = 0, dailyMinutes = 0, weeklyMinutes = 0)); onDismiss() }) {
                    Text(stringResource(R.string.jr_remove))
                }
                TextButton(onDismiss) { Text(stringResource(R.string.dc_cancel)) }
            }
        },
    )
}

private const val MAX_MINUTES = 7 * 24 * 60

@Composable
private fun MinutesField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(5)) },
        label = { Text(label) },
        singleLine = true,
        // Digits stay left-to-right in Arabic and Urdu.
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label)
    }
}

/** "Every 15 min" / "Off". */
fun reminderText(context: Context, minutes: Int): String =
    if (minutes <= 0) context.getString(R.string.dc_off) else context.getString(R.string.ct_every_min, minutes)

/** "every 15 min" / "off", inside a sentence. */
fun reminderTextInline(context: Context, minutes: Int): String =
    if (minutes <= 0) context.getString(R.string.ct_off_inline) else context.getString(R.string.ct_every_min_inline, minutes)
