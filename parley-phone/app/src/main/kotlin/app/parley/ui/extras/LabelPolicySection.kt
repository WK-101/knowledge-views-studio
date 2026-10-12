package app.parley.ui.extras

import android.content.Context
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
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.parley.ui.ParleyListItem
import app.parley.ui.Section
import app.parley.ui.contact.AutoAnswerRow
import app.parley.ui.contact.VibrationPatternDialog
import app.parley.ui.contact.VibrationRow
import app.parley.ui.startOrSay
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import app.parley.ui.ParleyDialog
import app.parley.ui.ConfirmDialog

/**
 * A label's policies on its page, under the ringtone: the SIM to call its members on (when they have none of
 * their own), "Keep in touch with everyone here" (a rhythm, and a switch that puts every member in the Circle with it),
 * "Add everyone to Favourites", and "Allow through Do Not Disturb". Android only lets starred contacts through, so that works by starring the members, which also puts them
 * in Favourites: the confirmation lists exactly who will be starred before anything changes.
 */
@Composable
fun LabelPolicySection(vm: AppViewModel, title: String, members: List<ContactSummary>) {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val extras = vm.c.extras
    val circle = vm.c.circle
    val policies by extras.policies.collectAsStateWithLifecycle()
    val chapters by extras.chapters.collectAsStateWithLifecycle()
    val dndStars by extras.dndStars.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val p = policies[title] ?: LabelPolicy()
    var pickSim by rememberSaveable { mutableStateOf(false) }
    var pickRhythm by rememberSaveable { mutableStateOf(false) }
    // The switch was turned on before a rhythm was chosen: the pick turns it on.
    var feedAfterPick by rememberSaveable { mutableStateOf(false) }
    var confirmFavourites by rememberSaveable { mutableStateOf(false) }
    var explainDnd by rememberSaveable { mutableStateOf(false) }
    var pickVibration by rememberSaveable { mutableStateOf(false) }
    val callCfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    // Only the members not starred yet: "Star N new members" confirms with the same list.
    var starNewOnly by rememberSaveable { mutableStateOf(false) }
    val circleKeys by produceState(emptySet<String>(), members, p) { value = circle.members().map { it.lookupKey }.toSet() }
    // "Add everyone to Favourites": who isn't a favourite you chose yet (not starred, or starred only for a label's
    // Do Not Disturb, which then becomes your own star).
    val toFavourite = members.filter { !it.starred || (it.id > 0 && it.lookupKey in dndStars) }
    val outside = members.filter { it.lookupKey !in circleKeys }
    // Only address-book contacts can be let through Do Not Disturb (Android decides, and never sees private ones).
    val unstarred = members.filter { !it.starred && it.id > 0 }
    val privateMembers = members.count { it.id < 0 }

    Column {
        Section(stringResource(R.string.label_policy_section))
        if (sims.size >= 2 || p.simId != null) {
            val sim = sims.firstOrNull { it.id == p.simId }
            ParleyListItem(
                modifier = Modifier.clickable { pickSim = true },
                leadingContent = { Icon(Icons.Rounded.SimCard, null) },
                headlineContent = { Text(stringResource(R.string.label_sim_for_calls)) },
                supportingContent = {
                    Text(
                        when {
                            p.simId == null -> stringResource(R.string.label_policy_sim_none)
                            sim == null -> stringResource(R.string.label_policy_sim_missing)
                            else -> stringResource(R.string.label_policy_sim_on, sim.label)
                        } + "\n" + stringResource(R.string.label_policy_sim_body),
                    )
                },
            )
        }
        // "Keep in touch with everyone here": the rhythm, and the switch that puts everyone here in the Circle with it
        // (off: today's suggestion when someone joins). Never on by itself; switching it off takes out only those
        // who were in through this label.
        val running = chapters[title]?.let { System.currentTimeMillis() < it.endsAt } == true
        ParleyListItem(
            modifier = Modifier.clickable { pickRhythm = true },
            leadingContent = { Icon(Icons.Rounded.Handshake, null) },
            headlineContent = { Text(stringResource(R.string.label_policy_rhythm)) },
            supportingContent = {
                val every = p.rhythmDays?.let { pluralStringResource(R.plurals.circle_every_days, it, it) }
                Text(
                    when {
                        every == null -> stringResource(R.string.label_not_set)
                        p.circleFromLabel && running -> stringResource(R.string.label_policy_circle_chapter, every)
                        p.circleFromLabel -> stringResource(R.string.label_policy_circle_on, every)
                        else -> stringResource(R.string.label_policy_circle_off, every)
                    },
                )
            },
            trailingContent = {
                val label = stringResource(R.string.label_policy_circle_switch)
                Switch(
                    p.feedsCircle,
                    onCheckedChange = { on ->
                        when {
                            !on -> extras.updatePolicy(title) { it.copy(circleFromLabel = false) }
                            p.rhythmDays == null -> { feedAfterPick = true; pickRhythm = true }
                            else -> extras.updatePolicy(title) { it.copy(circleFromLabel = true) }
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = label },
                )
            },
        )
        val days = p.rhythmDays
        if (days != null && !p.circleFromLabel && outside.isNotEmpty()) {
            TextButton({
                scope.launch {
                    outside.forEach { m -> circle.setRhythm(m.lookupKey, m.id, days) }
                    vm.toast(res.getQuantityString(R.plurals.label_policy_added, outside.size, outside.size))
                }
            }, Modifier.padding(start = 56.dp)) { Text(pluralStringResource(R.plurals.label_policy_add_members, outside.size, outside.size)) }
        }
        if (toFavourite.isNotEmpty()) ParleyListItem(
            modifier = Modifier.clickable { confirmFavourites = true },
            leadingContent = { Icon(Icons.Rounded.Star, null) },
            headlineContent = { Text(stringResource(R.string.label_policy_favourites)) },
        )
        ParleyListItem(
            modifier = Modifier.toggleable(p.allowThroughDnd, role = Role.Switch) { on ->
                if (on) explainDnd = true
                else turnOffDnd(vm, title) { n -> vm.toast(res.getQuantityString(R.plurals.label_policy_unstarred, n, n)) }
            },
            leadingContent = { Icon(Icons.Rounded.DoNotDisturbOn, null) },
            headlineContent = { Text(stringResource(R.string.label_policy_dnd)) },
            supportingContent = {
                Text(
                    stringResource(R.string.label_policy_dnd_summary) +
                        if (privateMembers > 0) "\n" + pluralStringResource(R.plurals.label_policy_dnd_private, privateMembers, privateMembers) else "",
                )
            },
            trailingContent = { Switch(p.allowThroughDnd, onCheckedChange = null) },
        )
        if (p.allowThroughDnd) {
            if (unstarred.isNotEmpty()) TextButton({ starNewOnly = true; explainDnd = true }, Modifier.padding(start = 56.dp)) {
                Icon(Icons.Rounded.Star, null, Modifier.padding(end = 6.dp))
                Text(pluralStringResource(R.plurals.label_policy_star_new, unstarred.size, unstarred.size))
            }
            TextButton({ openDndSettings(context) }, Modifier.padding(start = 56.dp)) { Text(stringResource(R.string.label_policy_dnd_open)) }
        }
        // Haptic caller ID for members without one of their own, and auto-answer for the label (when that option is on).
        VibrationRow(p.vibration, title) { pickVibration = true }
        if (callCfg.autoAnswerChosen) {
            AutoAnswerRow(p.autoAnswer, stringResource(R.string.label_policy_auto_answer_summary)) { v ->
                extras.updatePolicy(title) { it.copy(autoAnswer = v) }
            }
        }
    }
    if (pickVibration) VibrationPatternDialog(
        current = p.vibration, name = title, seedKey = "label:$title",
        onPick = { spec -> pickVibration = false; extras.updatePolicy(title) { it.copy(vibration = spec) } },
        onDismiss = { pickVibration = false },
    )

    if (pickSim) ParleyDialog(
        onDismissRequest = { pickSim = false },
        title = { Text(stringResource(R.string.label_sim_for_calls)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.label_policy_sim_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                val choices = listOf<Pair<String?, String>>(null to stringResource(R.string.label_policy_sim_none)) + sims.map { it.id to it.label }
                choices.forEach { (id, label) ->
                    ParleyListItem(
                        modifier = Modifier.clickable { extras.updatePolicy(title) { it.copy(simId = id) }; pickSim = false },
                        leadingContent = { RadioButton(p.simId == id, { extras.updatePolicy(title) { it.copy(simId = id) }; pickSim = false }) },
                        headlineContent = { Text(label) },
                    )
                }
            }
        },
        confirmButton = { TextButton({ pickSim = false }) { Text(stringResource(R.string.dc_cancel)) } },
    )
    if (pickRhythm) ParleyDialog(
        onDismissRequest = { pickRhythm = false; feedAfterPick = false },
        title = { Text(stringResource(R.string.label_policy_rhythm)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.label_policy_rhythm_body, title), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                (listOf<Int?>(null) + LabelPolicies.RHYTHM_CHOICES).forEach { d ->
                    val label = d?.let { pluralStringResource(R.plurals.circle_every_days, it, it) } ?: stringResource(R.string.label_not_set)
                    val pick = {
                        val feed = feedAfterPick && d != null
                        extras.updatePolicy(title) { it.copy(rhythmDays = d, circleFromLabel = (it.circleFromLabel || feed) && d != null) }
                        feedAfterPick = false
                        pickRhythm = false
                    }
                    ParleyListItem(
                        modifier = Modifier.clickable(onClick = pick),
                        leadingContent = { RadioButton(p.rhythmDays == d, pick) },
                        headlineContent = { Text(label) },
                    )
                }
            }
        },
        confirmButton = { TextButton({ pickRhythm = false; feedAfterPick = false }) { Text(stringResource(R.string.dc_cancel)) } },
    )
    if (confirmFavourites) ConfirmDialog(
        title = stringResource(R.string.label_policy_favourites_title, title),
        text = null,
        confirmLabel = pluralStringResource(R.plurals.label_policy_favourites_confirm, toFavourite.size, toFavourite.size),
        onConfirm = {
            confirmFavourites = false
            val who = toFavourite
            scope.launch {
                val done = extras.addToFavourites(who)
                if (done.count > 0) {
                    vm.offerUndo(res.getQuantityString(R.plurals.label_policy_favourites_done, done.count, done.count)) { extras.undoFavourites(done) }
                }
            }
        },
        onDismiss = { confirmFavourites = false },
        dismissLabel = stringResource(R.string.dc_cancel),
        icon = Icons.Rounded.Star,
        content = {
            // Exactly who, before anything changes.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(pluralStringResource(R.plurals.label_policy_favourites_preview, toFavourite.size, toFavourite.size), style = MaterialTheme.typography.bodyMedium)
                toFavourite.forEach { m ->
                    ParleyListItem(
                        leadingContent = { Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(m.displayName) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                Text(stringResource(R.string.label_policy_favourites_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
    )
    if (explainDnd) ConfirmDialog(
        title = stringResource(R.string.label_policy_dnd_title, title),
        text = null,
        confirmLabel = when {
            unstarred.isEmpty() -> stringResource(R.string.label_policy_dnd_open)
            else -> pluralStringResource(R.plurals.label_policy_dnd_star_confirm, unstarred.size, unstarred.size)
        },
        onConfirm = {
            val newOnly = starNewOnly
            explainDnd = false
            starNewOnly = false
            scope.launch {
                if (newOnly) {
                    extras.starForDnd(title, unstarred)
                } else {
                    extras.updatePolicy(title) { it.copy(allowThroughDnd = true) }
                    // Every member: those Parley starred for another label are recorded under this one too.
                    extras.starForDnd(title, members)
                    openDndSettings(context)
                }
            }
        },
        onDismiss = { explainDnd = false; starNewOnly = false },
        dismissLabel = stringResource(R.string.dc_cancel),
        icon = Icons.Rounded.DoNotDisturbOn,
        content = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (unstarred.isEmpty()) {
                    Text(stringResource(R.string.label_policy_dnd_all_starred), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(pluralStringResource(R.plurals.label_policy_dnd_preview, unstarred.size, unstarred.size), style = MaterialTheme.typography.bodyMedium)
                    unstarred.forEach { m ->
                        ParleyListItem(
                            leadingContent = { Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(m.displayName) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
                Text(
                    stringResource(R.string.label_policy_dnd_everyone_note), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
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
fun openDndSettings(context: Context) {
    val tries = listOf(Intent(ACTION_ZEN_PRIORITY), Intent(ACTION_ZEN), Intent(Settings.ACTION_SOUND_SETTINGS))
    for (i in tries) {
        if (context.startOrSay(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
    }
}

/** Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS and the Do Not Disturb page (the latter isn't public API everywhere). */
private const val ACTION_ZEN_PRIORITY = "android.settings.ZEN_MODE_PRIORITY_SETTINGS"
private const val ACTION_ZEN = "android.settings.ZEN_MODE_SETTINGS"

/** In "Stay in touch", the rhythm one of the person's labels asks for, offered first. Nothing when none does. */
@Composable
fun LabelRhythmSuggestion(vm: AppViewModel, contactId: Long, pick: (Int) -> Unit) {
    val suggestion by produceState<Pair<String, Int>?>(null, contactId) { value = runCatching { vm.c.extras.labelRhythmFor(contactId) }.getOrNull() }
    val (label, days) = suggestion ?: return
    ParleyListItem(
        modifier = Modifier.clickable { pick(days) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.AutoMirrored.Rounded.Label, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(stringResource(R.string.label_policy_rhythm_suggest, label, pluralStringResource(R.plurals.circle_every_days, days, days))) },
    )
}
