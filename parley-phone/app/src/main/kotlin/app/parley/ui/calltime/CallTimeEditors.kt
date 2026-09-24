package app.parley.ui.calltime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.parley.R
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.security.AppLock
import app.parley.security.VaultSession
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle

/**
 * Supervised mode (T7): limits can only be changed after proving presence with the app lock (fingerprint,
 * face or screen lock). One unlock is good for a couple of minutes.
 */
@Composable
fun rememberSupervisedGate(config: CallingConfig): (String, () -> Unit) -> Unit {
    val activity = androidx.activity.compose.LocalActivity.current as? FragmentActivity
    return { why, action ->
        if (!config.supervised || VaultSession.recentlyAuthenticated(SUPERVISED_WINDOW_MS)) {
            action()
        } else if (activity != null) {
            AppLock.authenticate(activity, why) { ok -> if (ok) action() }
        }
    }
}

private const val SUPERVISED_WINDOW_MS = 2 * 60_000L

/** Editor for one limit rule: per call, per day, per week; incoming and/or outgoing (T5, T6). */
@Composable
fun LimitRuleDialog(
    title: String,
    rule: LimitRule,
    onSave: (LimitRule) -> Unit,
    onDismiss: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var perCall by remember { mutableStateOf(rule.perCallMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
    var daily by remember { mutableStateOf(rule.dailyMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
    var weekly by remember { mutableStateOf(rule.weeklyMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
    var incoming by remember { mutableStateOf(rule.incoming) }
    var outgoing by remember { mutableStateOf(rule.outgoing) }
    fun minutes(s: String) = s.trim().toIntOrNull()?.coerceIn(0, MAX_MINUTES) ?: 0
    AlertDialog(
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
                CheckRow(stringResource(R.string.ct_incoming_calls), incoming) { incoming = it; if (!it && !outgoing) outgoing = true }
                CheckRow(stringResource(R.string.ct_outgoing_calls), outgoing) { outgoing = it; if (!it && !incoming) incoming = true }
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton({
                onSave(rule.copy(perCallMinutes = minutes(perCall), dailyMinutes = minutes(daily), weeklyMinutes = minutes(weekly), incoming = incoming, outgoing = outgoing))
                onDismiss()
            }) { Text(stringResource(R.string.set_save)) }
        },
        dismissButton = {
            Row {
                if (!rule.isEmpty) TextButton({ onSave(rule.copy(perCallMinutes = 0, dailyMinutes = 0, weeklyMinutes = 0)); onDismiss() }) { Text(stringResource(R.string.ct_remove)) }
                TextButton(onDismiss) { Text(stringResource(R.string.set_cancel)) }
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
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
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

/** A row with a switch inside a dialog. */
@Composable
fun DialogSwitch(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = sub?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

/** A list row that opens a dropdown of [options]. */
@Composable
fun ChoiceRow(title: String, options: List<String>, selected: Int, leading: (@Composable () -> Unit)? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open = true },
        leadingContent = leading,
        headlineContent = { Text(title) },
        supportingContent = { Text(options.getOrElse(selected) { "" }) },
        trailingContent = {
            DropdownMenu(open, { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, onClick = { open = false; onPick(i) }) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** "Every 15 min" / "Off". */
fun reminderText(context: android.content.Context, minutes: Int): String =
    if (minutes <= 0) context.getString(R.string.set_off) else context.getString(R.string.ct_every_min, minutes)

/** "every 15 min" / "off", inside a sentence. */
fun reminderTextInline(context: android.content.Context, minutes: Int): String =
    if (minutes <= 0) context.getString(R.string.ct_off_inline) else context.getString(R.string.ct_every_min_inline, minutes)
