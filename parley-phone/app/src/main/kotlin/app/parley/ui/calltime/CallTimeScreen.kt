package app.parley.ui.calltime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.calltime.CallTimePlanner
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope
import app.parley.data.GroupInfo
import app.parley.security.AppLock
import app.parley.ui.contact.Section
import app.parley.ui.settings.SwitchRow
import app.parley.ui.settings.bidiLtr
import app.parley.ui.settings.settingTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings › Calls › Call time: talk-time reminders (T1), hard limits and allowances per contact, label, SIM
 * or all calls (T5, T6), and supervised mode (T7).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallTimeScreen(vm: AppViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var editing by remember { mutableStateOf<Pair<String, LimitRule>?>(null) }
    var pickLabel by remember { mutableStateOf(false) }
    var noLock by remember { mutableStateOf(false) }
    val gate = rememberSupervisedGate(config)
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { runCatching { vm.c.contacts.groups() }.getOrDefault(emptyList()) } }

    val unlockReason = stringResource(R.string.ct_unlock_limits)
    val allCalls = stringResource(R.string.ct_all_calls)
    val contactFallback = stringResource(R.string.ct_contact)
    fun set(f: (CallingConfig) -> CallingConfig) = vm.c.calling.update(f)
    fun limits(f: (CallingConfig) -> CallingConfig) = gate(unlockReason) { set(f) }
    fun edit(title: String, rule: LimitRule) = gate(unlockReason) { editing = title to rule }
    fun labelTitle(name: String) = res.getString(R.string.ct_label_title, name)

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.ct_title)) }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item { Section(stringResource(R.string.ct_section_reminders)) }
            item {
                Help(stringResource(R.string.ct_reminders_help))
                val choices = CallingConfig.REMINDER_CHOICES
                ChoiceRow(stringResource(R.string.ct_remind_me), choices.map { reminderText(context, it) }, choices.indexOf(config.reminders.everyMinutes).coerceAtLeast(0)) { i ->
                    set { it.copy(reminders = it.reminders.copy(everyMinutes = choices[i])) }
                }
                SwitchRow(stringResource(R.string.ct_beep), stringResource(R.string.ct_beep_body), config.reminders.beep) { v -> set { it.copy(reminders = it.reminders.copy(beep = v)) } }
                SwitchRow(stringResource(R.string.ct_vibrate), stringResource(R.string.ct_vibrate_body), config.reminders.vibrate) { v -> set { it.copy(reminders = it.reminders.copy(vibrate = v)) } }
                if (config.reminders.perContact.isNotEmpty()) {
                    Help(pluralStringResource(R.plurals.ct_contacts_own_reminder, config.reminders.perContact.size, config.reminders.perContact.size))
                }
            }

            item { Section(stringResource(R.string.ct_section_limits)) }
            item {
                Help(stringResource(R.string.ct_limits_help, warnText(context, config.warnSeconds)))
                val global = config.rule(LimitScope.GLOBAL, "") ?: LimitRule(LimitScope.GLOBAL, title = "All calls")
                RuleRow(Icons.Rounded.Public, allCalls, global) { edit(allCalls, global) }
                if (sims.size >= 2) {
                    sims.forEach { sim ->
                        val r = config.rule(LimitScope.SIM, sim.id) ?: LimitRule(LimitScope.SIM, sim.id, sim.label)
                        RuleRow(Icons.Rounded.SimCard, sim.label, r) { edit(sim.label, r.copy(title = sim.label)) }
                    }
                }
            }
            val labelRules = config.rules.filter { it.scope == LimitScope.LABEL }
            items(labelRules, key = { it.id }) { r ->
                val name = app.parley.common.LabelRefs.limitTitle(r)
                RuleRow(Icons.AutoMirrored.Rounded.Label, labelTitle(name), r) { edit(labelTitle(name), r.copy(title = name)) }
            }
            item {
                if (groups.isNotEmpty()) {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.Add, null) },
                        headlineContent = { Text(stringResource(R.string.ct_add_label_limit)) },
                        modifier = Modifier.clickable { gate(unlockReason) { pickLabel = true } },
                    )
                }
            }
            val contactRules = config.rules.filter { it.scope == LimitScope.CONTACT }
            items(contactRules, key = { it.id }) { r ->
                RuleRow(Icons.Rounded.Person, r.title.ifBlank { contactFallback }, r) { edit(r.title.ifBlank { contactFallback }, r) }
            }
            item {
                Help(stringResource(R.string.ct_contact_limit_help))
                val warn = CallingConfig.WARN_CHOICES
                ChoiceRow(stringResource(R.string.ct_warn_before), warn.map { warnText(context, it) }, warn.indexOf(config.warnSeconds).coerceAtLeast(0)) { i ->
                    limits { it.copy(warnSeconds = warn[i]) }
                }
                SwitchRow(stringResource(R.string.ct_silence_over), stringResource(R.string.ct_silence_over_body), config.silenceIncomingOverQuota) { v ->
                    limits { it.copy(silenceIncomingOverQuota = v) }
                }
            }

            item { Section(stringResource(R.string.ct_supervised)) }
            item {
                Help(stringResource(R.string.ct_supervised_help))
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Lock, null) },
                    headlineContent = { Text(stringResource(R.string.ct_supervised)) },
                    supportingContent = { Text(stringResource(if (config.supervised) R.string.ct_supervised_on else R.string.set_off)) },
                    trailingContent = {
                        Switch(config.supervised, { v ->
                            val act = context as? FragmentActivity ?: return@Switch
                            if (v && !AppLock.canAuthenticate(act)) {
                                noLock = true
                                return@Switch
                            }
                            AppLock.authenticate(act, res.getString(if (v) R.string.ct_supervised_turn_on else R.string.ct_supervised_turn_off)) { ok -> if (ok) set { it.copy(supervised = v) } }
                        })
                    },
                )
            }
            val favourites = contacts.orEmpty().filter { it.starred }
            if (favourites.isNotEmpty()) {
                item { Section(stringResource(R.string.ct_never_limit)) }
                item { Help(stringResource(R.string.ct_never_limit_help)) }
                items(favourites, key = { "fav" + it.id }) { fav ->
                    val on = fav.lookupKey in config.neverLimit
                    SwitchRow(fav.displayName, null, on) { v ->
                        limits { c -> c.copy(neverLimit = if (v) c.neverLimit + fav.lookupKey else c.neverLimit - fav.lookupKey) }
                    }
                }
            }
        }
    }

    editing?.let { (title, rule) ->
        LimitRuleDialog(title, rule, onSave = { r -> set { it.withRule(r) } }, onDismiss = { editing = null })
    }
    if (pickLabel) {
        AlertDialog(
            onDismissRequest = { pickLabel = false },
            title = { Text(stringResource(R.string.ct_limit_label_title)) },
            text = {
                Column {
                    // One entry per label title: the limit covers that label in every account.
                    groups.groupBy { app.parley.common.LabelRefs.key(it.title) }.forEach { (title, gs) ->
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = { Text(gs.map { it.account.displayLabel }.distinct().joinToString(", ")) },
                            modifier = Modifier.clickable {
                                pickLabel = false
                                val r = config.rules.firstOrNull { it.scope == LimitScope.LABEL && app.parley.common.LabelRefs.limitTitle(it) == title }
                                    ?: LimitRule(LimitScope.LABEL, title, title)
                                editing = labelTitle(title) to r
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ pickLabel = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
    if (noLock) {
        AlertDialog(
            onDismissRequest = { noLock = false },
            title = { Text(stringResource(R.string.ct_no_lock_title)) },
            text = { Text(stringResource(R.string.ct_no_lock_body)) },
            confirmButton = { TextButton({ noLock = false }) { Text(stringResource(R.string.set_ok)) } },
        )
    }
}

@Composable
private fun RuleRow(icon: ImageVector, title: String, rule: LimitRule, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(if (rule.isEmpty) stringResource(R.string.ct_no_limit) else CallTimePlanner.allowanceText(LocalContext.current, rule)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

private fun warnText(context: android.content.Context, sec: Int): String =
    if (sec % 60 == 0) context.getString(R.string.ct_minutes_short, sec / 60) else context.getString(R.string.ct_seconds_short, sec)
