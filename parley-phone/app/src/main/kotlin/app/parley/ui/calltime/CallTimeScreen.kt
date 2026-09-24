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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.calltime.CallTimePlanner
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope
import app.parley.data.GroupInfo
import app.parley.security.AppLock
import app.parley.ui.contact.Section
import app.parley.ui.settings.SwitchRow
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
    val config by vm.c.calling.config.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    var editing by remember { mutableStateOf<Pair<String, LimitRule>?>(null) }
    var pickLabel by remember { mutableStateOf(false) }
    var noLock by remember { mutableStateOf(false) }
    val gate = rememberSupervisedGate(config)
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { runCatching { vm.c.contacts.groups() }.getOrDefault(emptyList()) } }

    fun set(f: (CallingConfig) -> CallingConfig) = vm.c.calling.update(f)
    fun limits(f: (CallingConfig) -> CallingConfig) = gate("Unlock to change call limits") { set(f) }
    fun edit(title: String, rule: LimitRule) = gate("Unlock to change call limits") { editing = title to rule }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Call time") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        LazyColumn(Modifier.padding(p)) {
            item { Section("Talk-time reminders") }
            item {
                Help("A soft beep in the earpiece and a vibration during long calls, for example every 30 minutes. It never ends a call.")
                val choices = CallingConfig.REMINDER_CHOICES
                ChoiceRow("Remind me", choices.map { reminderText(it) }, choices.indexOf(config.reminders.everyMinutes).coerceAtLeast(0)) { i ->
                    set { it.copy(reminders = it.reminders.copy(everyMinutes = choices[i])) }
                }
                SwitchRow("Beep in the earpiece", "Only you hear it", config.reminders.beep) { v -> set { it.copy(reminders = it.reminders.copy(beep = v)) } }
                SwitchRow("Vibrate", "Not in silent mode", config.reminders.vibrate) { v -> set { it.copy(reminders = it.reminders.copy(vibrate = v)) } }
                if (config.reminders.perContact.isNotEmpty()) {
                    Help("${config.reminders.perContact.size} contacts have their own reminder, set on their contact page.")
                }
            }

            item { Section("Call time limits") }
            item {
                Help(
                    "Off unless you set one. A limit warns you ${warnText(config.warnSeconds)} before, then ends that call only. " +
                        "Allowances per day or week are counted from your call history and never cut a call. Emergency calls are never limited.",
                )
                val global = config.rule(LimitScope.GLOBAL, "") ?: LimitRule(LimitScope.GLOBAL, title = "All calls")
                RuleRow(Icons.Rounded.Public, "All calls", global) { edit("All calls", global) }
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
                RuleRow(Icons.AutoMirrored.Rounded.Label, "Label: $name", r) { edit("Label: $name", r.copy(title = name)) }
            }
            item {
                if (groups.isNotEmpty()) {
                    ListItem(
                        leadingContent = { Icon(Icons.Rounded.Add, null) },
                        headlineContent = { Text("Add a limit for a label") },
                        modifier = Modifier.clickable { gate("Unlock to change call limits") { pickLabel = true } },
                    )
                }
            }
            val contactRules = config.rules.filter { it.scope == LimitScope.CONTACT }
            items(contactRules, key = { it.id }) { r ->
                RuleRow(Icons.Rounded.Person, r.title.ifBlank { "Contact" }, r) { edit(r.title.ifBlank { "Contact" }, r) }
            }
            item {
                Help("Set a limit for one person from their contact page (“Call time limit”). A person's own limit replaces label, SIM and all-call limits.")
                val warn = CallingConfig.WARN_CHOICES
                ChoiceRow("Warn before ending", warn.map { warnText(it).replaceFirstChar { c -> c.uppercase() } }, warn.indexOf(config.warnSeconds).coerceAtLeast(0)) { i ->
                    limits { it.copy(warnSeconds = warn[i]) }
                }
                SwitchRow("Silence incoming calls over the allowance", "They still ring silently and show on screen", config.silenceIncomingOverQuota) { v ->
                    limits { it.copy(silenceIncomingOverQuota = v) }
                }
            }

            item { Section("Supervised mode") }
            item {
                Help("For a relative's phone or for self-control: changing limits needs your fingerprint, face or screen lock, and calls can't be extended with “+5 min” or “Don't end”.")
                ListItem(
                    leadingContent = { Icon(Icons.Rounded.Lock, null) },
                    headlineContent = { Text("Supervised mode") },
                    supportingContent = { Text(if (config.supervised) "On: limits are protected" else "Off") },
                    trailingContent = {
                        Switch(config.supervised, { v ->
                            val act = context as? FragmentActivity ?: return@Switch
                            if (v && !AppLock.canAuthenticate(act)) {
                                noLock = true
                                return@Switch
                            }
                            AppLock.authenticate(act, if (v) "Turn on supervised mode" else "Turn off supervised mode") { ok -> if (ok) set { it.copy(supervised = v) } }
                        })
                    },
                )
            }
            val favourites = contacts.orEmpty().filter { it.starred }
            if (favourites.isNotEmpty()) {
                item { Section("Never limit") }
                item { Help("Favourites switched on here are never limited or silenced, like emergency numbers.") }
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
            title = { Text("Limit calls with a label") },
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
                                editing = "Label: $title" to r
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ pickLabel = false }) { Text("Cancel") } },
        )
    }
    if (noLock) {
        AlertDialog(
            onDismissRequest = { noLock = false },
            title = { Text("Set up a screen lock first") },
            text = { Text("Supervised mode protects limits with your fingerprint, face or screen lock. This phone has none set up.") },
            confirmButton = { TextButton({ noLock = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun RuleRow(icon: ImageVector, title: String, rule: LimitRule, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(if (rule.isEmpty) "No limit" else CallTimePlanner.allowanceText(rule)) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

private fun warnText(sec: Int): String = if (sec % 60 == 0) "${sec / 60} min" else "$sec s"
