package app.parley.ui.calltime

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.calltime.CallTimePlanner
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope

/**
 * Contact page rows "Talk-time reminder" (T1) and "Call time limit" (T5, T6). A favourite can also be marked
 * "Never limit" (T7).
 */
@Composable
fun ContactCallTimeRows(vm: AppViewModel, lookupKey: String, name: String, starred: Boolean) {
    if (lookupKey.isBlank()) return
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    var editLimit by remember { mutableStateOf(false) }
    val gate = rememberSupervisedGate(config)
    val own = config.reminders.perContact[lookupKey]
    val choices = listOf(-1) + CallingConfig.REMINDER_CHOICES
    val labels = choices.map { if (it < 0) "Default (${reminderText(config.reminders.everyMinutes).lowercase()})" else reminderText(it) }
    Column {
        ChoiceRow(
            "Talk-time reminder", labels, choices.indexOf(own ?: -1).coerceAtLeast(0),
            leading = { Icon(Icons.Rounded.Timer, null) },
        ) { i ->
            val m = choices[i]
            vm.c.calling.update { c ->
                val per = if (m < 0) c.reminders.perContact - lookupKey else c.reminders.perContact + (lookupKey to m)
                c.copy(reminders = c.reminders.copy(perContact = per))
            }
        }
        val rule = config.rule(LimitScope.CONTACT, lookupKey)
        val exempt = lookupKey in config.neverLimit
        ListItem(
            leadingContent = { Icon(Icons.Rounded.HourglassBottom, null) },
            headlineContent = { Text("Call time limit") },
            supportingContent = {
                Text(
                    when {
                        exempt -> "Never limited"
                        rule != null -> CallTimePlanner.allowanceText(rule)
                        config.rules.any { it.scope != LimitScope.CONTACT } -> "Label, SIM or all-call limits apply"
                        else -> "No limit"
                    },
                )
            },
            modifier = Modifier.clickable { gate("Unlock to change call limits") { editLimit = true } },
        )
    }
    if (editLimit) {
        var never by remember { mutableStateOf(lookupKey in config.neverLimit) }
        LimitRuleDialog(
            title = "Call time with $name",
            rule = config.rule(LimitScope.CONTACT, lookupKey)?.copy(title = name) ?: LimitRule(LimitScope.CONTACT, lookupKey, name),
            onSave = { r ->
                vm.c.calling.update { c ->
                    c.withRule(r).copy(neverLimit = if (never) c.neverLimit + lookupKey else c.neverLimit - lookupKey)
                }
            },
            onDismiss = { editLimit = false },
            extra = if (starred || never) {
                { DialogSwitch("Never limit", "Like emergency numbers: no limit, no allowance, never silenced", never) { never = it } }
            } else {
                null
            },
        )
    }
}
