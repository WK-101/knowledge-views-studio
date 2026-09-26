package app.parley.ui.extras

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ContactSummary
import app.parley.common.extras.LabelPolicies
import app.parley.common.extras.LabelPolicy
import app.parley.ui.contact.Section
import kotlinx.coroutines.launch

/**
 * X3: a label's policies on its page, under the ringtone: the SIM to call its members on (when they have none of
 * their own), the keep-in-touch rhythm offered when a member joins the Circle, and "Allow through Do Not
 * Disturb", which stars the members and points to Android's setting for starred contacts.
 */
@Composable
fun LabelPolicySection(vm: AppViewModel, title: String, members: List<ContactSummary>) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val policies by vm.c.extras.policies.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val p = policies[title] ?: LabelPolicy()
    var pickSim by remember { mutableStateOf(false) }
    var pickRhythm by remember { mutableStateOf(false) }
    var explainDnd by remember { mutableStateOf(false) }
    val circleKeys by produceState(emptySet<String>(), members, p.rhythmDays) { value = vm.c.circle.members().map { it.lookupKey }.toSet() }
    val outside = members.filter { it.lookupKey !in circleKeys }
    val unstarred = members.filter { !it.starred }

    Column {
        Section(stringResource(R.string.x_lp_section))
        if (sims.size >= 2 || p.simId != null) {
            val sim = sims.firstOrNull { it.id == p.simId }
            ListItem(
                modifier = Modifier.clickable { pickSim = true },
                leadingContent = { Icon(Icons.Rounded.SimCard, null) },
                headlineContent = { Text(stringResource(R.string.x_lp_sim)) },
                supportingContent = {
                    Text(
                        when {
                            p.simId == null -> stringResource(R.string.x_lp_sim_none)
                            sim == null -> stringResource(R.string.x_lp_sim_missing)
                            else -> stringResource(R.string.x_lp_sim_on, sim.label)
                        } + "\n" + stringResource(R.string.x_lp_sim_body),
                    )
                },
            )
        }
        ListItem(
            modifier = Modifier.clickable { pickRhythm = true },
            leadingContent = { Icon(Icons.Rounded.Handshake, null) },
            headlineContent = { Text(stringResource(R.string.x_lp_rhythm)) },
            supportingContent = {
                Text(p.rhythmDays?.let { pluralStringResource(R.plurals.circle_every_days, it, it) } ?: stringResource(R.string.x_lp_rhythm_none))
            },
        )
        val days = p.rhythmDays
        if (days != null && outside.isNotEmpty()) {
            TextButton({
                scope.launch {
                    outside.forEach { m -> vm.c.circle.setRhythm(m.lookupKey, m.id, days) }
                    vm.toast(res.getQuantityString(R.plurals.x_lp_added, outside.size, outside.size))
                }
            }, Modifier.padding(start = 56.dp)) { Text(pluralStringResource(R.plurals.x_lp_add_members, outside.size, outside.size)) }
        }
        ListItem(
            modifier = Modifier.clickable { if (p.allowThroughDnd) turnOffDnd(vm, title) { n -> vm.toast(res.getQuantityString(R.plurals.x_lp_unstarred, n, n)) } else explainDnd = true },
            leadingContent = { Icon(Icons.Rounded.DoNotDisturbOn, null) },
            headlineContent = { Text(stringResource(R.string.x_lp_dnd)) },
            supportingContent = { Text(stringResource(R.string.x_lp_dnd_summary)) },
            trailingContent = {
                Switch(p.allowThroughDnd, { on ->
                    if (on) explainDnd = true
                    else turnOffDnd(vm, title) { n -> vm.toast(res.getQuantityString(R.plurals.x_lp_unstarred, n, n)) }
                })
            },
        )
        if (p.allowThroughDnd) {
            if (unstarred.isNotEmpty()) TextButton({ scope.launch { vm.c.extras.starForDnd(title, unstarred) } }, Modifier.padding(start = 56.dp)) {
                Icon(Icons.Rounded.Star, null, Modifier.padding(end = 6.dp))
                Text(pluralStringResource(R.plurals.x_lp_star_new, unstarred.size, unstarred.size))
            }
            TextButton({ openDndSettings(context) }, Modifier.padding(start = 56.dp)) { Text(stringResource(R.string.x_lp_dnd_open)) }
        }
    }

    if (pickSim) AlertDialog(
        onDismissRequest = { pickSim = false },
        title = { Text(stringResource(R.string.x_lp_sim)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.x_lp_sim_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                (listOf<Pair<String?, String>>(null to stringResource(R.string.x_lp_sim_none)) + sims.map { it.id to it.label }).forEach { (id, label) ->
                    ListItem(
                        modifier = Modifier.clickable { vm.c.extras.updatePolicy(title) { it.copy(simId = id) }; pickSim = false },
                        leadingContent = { RadioButton(p.simId == id, { vm.c.extras.updatePolicy(title) { it.copy(simId = id) }; pickSim = false }) },
                        headlineContent = { Text(label) },
                    )
                }
            }
        },
        confirmButton = { TextButton({ pickSim = false }) { Text(stringResource(R.string.dc_cancel)) } },
    )
    if (pickRhythm) AlertDialog(
        onDismissRequest = { pickRhythm = false },
        title = { Text(stringResource(R.string.x_lp_rhythm)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.x_lp_rhythm_body, title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                (listOf<Int?>(null) + LabelPolicies.RHYTHM_CHOICES).forEach { d ->
                    val label = d?.let { pluralStringResource(R.plurals.circle_every_days, it, it) } ?: stringResource(R.string.x_lp_rhythm_none)
                    ListItem(
                        modifier = Modifier.clickable { vm.c.extras.updatePolicy(title) { it.copy(rhythmDays = d) }; pickRhythm = false },
                        leadingContent = { RadioButton(p.rhythmDays == d, { vm.c.extras.updatePolicy(title) { it.copy(rhythmDays = d) }; pickRhythm = false }) },
                        headlineContent = { Text(label) },
                    )
                }
            }
        },
        confirmButton = { TextButton({ pickRhythm = false }) { Text(stringResource(R.string.dc_cancel)) } },
    )
    if (explainDnd) AlertDialog(
        onDismissRequest = { explainDnd = false },
        icon = { Icon(Icons.Rounded.DoNotDisturbOn, null) },
        title = { Text(stringResource(R.string.x_lp_dnd_title, title)) },
        text = { Text(pluralStringResource(R.plurals.x_lp_dnd_explain, unstarred.size, unstarred.size)) },
        confirmButton = {
            TextButton({
                explainDnd = false
                scope.launch {
                    vm.c.extras.updatePolicy(title) { it.copy(allowThroughDnd = true) }
                    // Every member: those Parley starred for another label are recorded under this one too.
                    vm.c.extras.starForDnd(title, members)
                    openDndSettings(context)
                }
            }) { Text(stringResource(R.string.x_lp_dnd_confirm)) }
        },
        dismissButton = { TextButton({ explainDnd = false }) { Text(stringResource(R.string.dc_cancel)) } },
    )
}

/**
 * Switches the policy off: every contact Parley starred for this label (found by its record, not by today's members)
 * is unstarred, unless another label that lets people through still asks for it.
 */
private fun turnOffDnd(vm: AppViewModel, title: String, done: (Int) -> Unit) {
    vm.c.scope.launch { done(vm.c.extras.dndOff(title)) }
}

/** Android's "people who can interrupt" (priority) page; the general Do Not Disturb page or sound settings as fallbacks. */
fun openDndSettings(context: android.content.Context) {
    val tries = listOf(Intent(ACTION_ZEN_PRIORITY), Intent(ACTION_ZEN), Intent(Settings.ACTION_SOUND_SETTINGS))
    for (i in tries) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

/** Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS and the Do Not Disturb page (the latter isn't public API everywhere). */
private const val ACTION_ZEN_PRIORITY = "android.settings.ZEN_MODE_PRIORITY_SETTINGS"
private const val ACTION_ZEN = "android.settings.ZEN_MODE_SETTINGS"

/** X3: in "Stay in touch", the rhythm one of the person's labels asks for, offered first. Nothing when none does. */
@Composable
fun LabelRhythmSuggestion(vm: AppViewModel, contactId: Long, pick: (Int) -> Unit) {
    val suggestion by produceState<Pair<String, Int>?>(null, contactId) { value = runCatching { vm.c.extras.labelRhythmFor(contactId) }.getOrNull() }
    val (label, days) = suggestion ?: return
    ListItem(
        modifier = Modifier.clickable { pick(days) },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { Icon(Icons.AutoMirrored.Rounded.Label, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(R.string.x_lp_rhythm_suggest, label, pluralStringResource(R.plurals.circle_every_days, days, days))) },
    )
}
