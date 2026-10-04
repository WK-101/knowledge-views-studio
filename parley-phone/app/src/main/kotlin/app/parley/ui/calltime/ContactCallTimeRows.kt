package app.parley.ui.calltime

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.ContactRef
import app.parley.calltime.CallTimePlanner
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope
import app.parley.ui.MenuRow
import app.parley.ui.ParleyListItem
import app.parley.ui.SwitchRow

/**
 * Contact page rows "Talk-time reminder" and "Call time limit". A favourite can also be marked
 * "Never limit".
 */
@Composable
fun ContactCallTimeRows(vm: AppViewModel, lookupKey: String, name: String, starred: Boolean) {
    if (lookupKey.isBlank()) return
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    var editLimit by remember { mutableStateOf(false) }
    val gate = rememberSupervisedGate(config)
    val own = config.reminders.perContact[lookupKey]
    val choices = listOf(-1) + CallingConfig.REMINDER_CHOICES
    val context = LocalContext.current
    val labels = choices.map {
        if (it < 0) stringResource(R.string.ct_reminder_default, reminderTextInline(context, config.reminders.everyMinutes)) else reminderText(context, it)
    }
    val unlockReason = stringResource(R.string.ct_unlock_limits)
    Column {
        MenuRow(
            stringResource(R.string.ct_talk_time_reminder), labels, choices.indexOf(own ?: -1).coerceAtLeast(0),
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
        ParleyListItem(
            leadingContent = { Icon(Icons.Rounded.HourglassBottom, null) },
            headlineContent = { Text(stringResource(R.string.ct_call_time_limit)) },
            supportingContent = {
                Text(
                    when {
                        exempt -> stringResource(R.string.ct_never_limited)
                        rule != null -> CallTimePlanner.allowanceText(context, rule)
                        config.rules.any { it.scope != LimitScope.CONTACT } -> stringResource(R.string.ct_other_limits_apply)
                        else -> stringResource(R.string.ct_no_limit)
                    },
                )
            },
            modifier = Modifier.clickable { gate(unlockReason) { editLimit = true } },
        )
    }
    val storedName = limitName(lookupKey, name)
    if (editLimit) {
        var never by remember { mutableStateOf(lookupKey in config.neverLimit) }
        LimitRuleDialog(
            title = stringResource(R.string.ct_call_time_with, name),
            // A private contact's rule keeps no name: it would be the only copy of it outside the vault.
            rule = config.rule(LimitScope.CONTACT, lookupKey)?.copy(title = storedName) ?: LimitRule(LimitScope.CONTACT, lookupKey, storedName),
            onSave = { r ->
                vm.c.calling.update { c ->
                    c.withRule(r).copy(neverLimit = if (never) c.neverLimit + lookupKey else c.neverLimit - lookupKey)
                }
            },
            onDismiss = { editLimit = false },
            extra = if (starred || never) {
                { SwitchRow(stringResource(R.string.ct_never_limit), stringResource(R.string.ct_never_limit_body), never) { never = it } }
            } else {
                null
            },
        )
    }
}

/** The name a contact's limit is stored with: none for a private contact (it would be the only copy outside the vault). */
private fun limitName(lookupKey: String, name: String): String = if (ContactRef.isPrivateKey(lookupKey)) "" else name
