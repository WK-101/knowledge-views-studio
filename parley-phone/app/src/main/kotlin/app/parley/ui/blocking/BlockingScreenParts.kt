package app.parley.ui.blocking

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingNotifier
import app.parley.blocking.BlockingText
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.LabelRefs
import app.parley.common.NotifyLevel
import app.parley.common.OffHours
import app.parley.common.OffHoursAllow
import app.parley.common.RuleKind
import app.parley.common.RuleTools
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.ScreeningSettings
import app.parley.common.blocking.ScreeningPreset
import app.parley.common.blocking.ScreeningWeek
import app.parley.data.GroupInfo
import app.parley.ui.ParleyListItem
import app.parley.ui.common.Format
import app.parley.ui.settings.bidiLtr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.parley.ui.SwitchRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun offHoursWho(o: OffHours) = when (o.allow) {
    OffHoursAllow.CONTACTS -> stringResource(R.string.blk_who_contacts)
    OffHoursAllow.FAVOURITES -> stringResource(R.string.blk_who_favourites)
    OffHoursAllow.LABEL -> "'${o.labelTitle ?: stringResource(R.string.blk_who_label)}'"
}

@Composable
internal fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onChange(v) })
    }
}

@Composable
internal fun ToggleScheduleRow(title: String, schedule: Schedule?, onChange: (Schedule?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ParleyListItem(
        modifier = Modifier.clickable { open = !open },
        headlineContent = { Text(title) },
        supportingContent = { Text(schedule?.let { BlockingText.schedule(LocalContext.current, it) } ?: stringResource(R.string.blk_always)) },
    )
    if (open) ScheduleField(schedule, onChange)
}

@Composable
internal fun OffHoursWho(vm: AppViewModel, oh: OffHours, onChange: (OffHours) -> Unit) {
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { runCatching { vm.c.contacts.groups() }.getOrDefault(emptyList()) } }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.blk_who_may_ring), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                oh.allow == OffHoursAllow.CONTACTS,
                { onChange(oh.copy(allow = OffHoursAllow.CONTACTS)) },
                label = { Text(stringResource(R.string.blk_all_contacts)) },
            )
            FilterChip(
                oh.allow == OffHoursAllow.FAVOURITES,
                { onChange(oh.copy(allow = OffHoursAllow.FAVOURITES)) },
                label = { Text(stringResource(R.string.blk_favourites)) },
            )
            // By title: the label in every account.
            groups.map { LabelRefs.key(it.title) }.distinct().forEach { t ->
                val on = oh.allow == OffHoursAllow.LABEL && oh.labelTitle?.let { LabelRefs.key(it) } == t
                FilterChip(on, { onChange(oh.copy(allow = OffHoursAllow.LABEL, labelId = null, labelTitle = t)) }, label = { Text(t) })
            }
        }
        Text(
            stringResource(R.string.blk_off_hours_still_through),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SoundsSection(s: ScreeningSettings, set: ((ScreeningSettings) -> ScreeningSettings) -> Unit) {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf("") }
    val pick = rememberRingtonePicker { uri -> if (target == "repeat") set { it.copy(repeatRingtone = uri) } else set { it.copy(likelySpamRingtone = uri) } }
    SwitchRow(
        stringResource(R.string.blk_loud_favourites),
        stringResource(R.string.blk_loud_favourites_help),
        s.ringLoudFavourites,
    ) { v -> set { it.copy(ringLoudFavourites = v) } }
    SwitchRow(
        stringResource(R.string.blk_loud_repeat),
        stringResource(R.string.blk_loud_repeat_help, s.repeatWindowMinutes),
        s.ringLoudRepeat,
    ) { v -> set { it.copy(ringLoudRepeat = v) } }
    ParleyListItem(
        modifier = Modifier.clickable { target = "repeat"; pick(s.repeatRingtone) },
        headlineContent = { Text(stringResource(R.string.blk_ringtone_repeat)) },
        supportingContent = { Text(ringtoneTitle(context, s.repeatRingtone) ?: stringResource(R.string.set_same_as_usual)) },
        trailingContent = { if (s.repeatRingtone != null) TextButton({ set { it.copy(repeatRingtone = null) } }) { Text(stringResource(R.string.set_reset)) } },
    )
    ParleyListItem(
        modifier = Modifier.clickable { target = "spam"; pick(s.likelySpamRingtone) },
        headlineContent = { Text(stringResource(R.string.blk_ringtone_spam)) },
        supportingContent = { Text(ringtoneTitle(context, s.likelySpamRingtone) ?: stringResource(R.string.set_same_as_usual)) },
        trailingContent = {
            if (s.likelySpamRingtone != null) TextButton({ set { it.copy(likelySpamRingtone = null) } }) { Text(stringResource(R.string.set_reset)) }
        },
    )
    Text(
        stringResource(R.string.blk_label_ringtones_help),
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(stringResource(R.string.blk_notifications), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
    listOf(
        Triple(stringResource(R.string.blk_notify_blocked), s.notifyBlocked) { n: NotifyLevel -> set { it.copy(notifyBlocked = n) } },
        Triple(stringResource(R.string.blk_notify_reported), s.notifyReported) { n: NotifyLevel -> set { it.copy(notifyReported = n) } },
        Triple(stringResource(R.string.blk_notify_likely), s.notifyLikelySpam) { n: NotifyLevel -> set { it.copy(notifyLikelySpam = n) } },
    ).forEach { (title, v, change) ->
        ParleyListItem(headlineContent = { Text(title) }, supportingContent = { NotifyChoice(v, allowDefault = false, change) })
    }
    if (s.notifyBlocked == NotifyLevel.NONE) {
        Text(
            stringResource(R.string.blk_notify_off_warning),
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    TextButton({
        BlockingNotifier.channels(context)
        try {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        } catch (_: Exception) {
        }
    }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.blk_channel_settings)) }
}

@Composable
internal fun EmergencySection(vm: AppViewModel, s: ScreeningSettings, set: ((ScreeningSettings) -> ScreeningSettings) -> Unit) {
    var n by rememberSaveable { mutableStateOf("") }
    Text(
        stringResource(R.string.blk_emergency_extras_help),
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
    )
    s.emergencyExtras.forEach { x ->
        ParleyListItem(
            headlineContent = { Text(bidiLtr(Format.number(x, vm.countryIso))) },
            trailingContent = {
                IconButton({ set { it.copy(emergencyExtras = it.emergencyExtras - x) } }) { Icon(Icons.Rounded.Delete, stringResource(R.string.ct_remove)) }
            },
        )
    }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            n,
            { n = it },
            label = { Text(stringResource(R.string.blk_add_a_number)) },
            singleLine = true,
            textStyle = ltrTextStyle(),
            modifier = Modifier.weight(1f),
        )
        TextButton({
            val clean = RuleTools.check(n, RuleType.EXACT, vm.countryIso)
            if (clean.error == null) set { it.copy(emergencyExtras = (it.emergencyExtras + clean.pattern).distinct()) }
            n = ""
        }, enabled = n.isNotBlank()) { Text(stringResource(R.string.blk_add)) }
    }
}

/** A rule with its hit counter ("5 calls, last 2 days ago") and on/off switch. */
@Composable
internal fun RuleRow(vm: AppViewModel, r: BlockRule, now: Long, onClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val expired = r.expiresAt.let { it != null && it <= now }
    val context = LocalContext.current
    val title = BlockingText.ruleTitle(context, r).let { if (r.note.isNullOrBlank() && r.type.isNumberRule) bidiLtr(it) else it }
    ParleyListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(if (r.kind == RuleKind.ALLOW) Icons.Rounded.VerifiedUser else Icons.Rounded.Rule, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(ruleSummary(vm, r, now, expired)) },
        // The row opens the rule; the switch is its own control and says which rule it turns on or off.
        trailingContent = {
            Switch(
                r.enabled && !expired,
                modifier = Modifier.semantics { contentDescription = title },
                onCheckedChange = { v -> scope.launch { vm.c.blocks.saveRule(r.copy(enabled = v, expiresAt = if (expired && v) null else r.expiresAt)) } },
            )
        },
    )
}

/** The rule's second line: what it does, its schedule, SIM and time left, and how many calls it stopped. */
@Composable
private fun ruleSummary(vm: AppViewModel, r: BlockRule, now: Long, expired: Boolean): String {
    val context = LocalContext.current
    val action = if (r.action == BlockAction.SILENCE) R.string.blk_action_silence_lower else R.string.blk_action_reject_lower
    val hits = if (r.hitCount > 0) {
        pluralStringResource(R.plurals.blk_calls, r.hitCount, r.hitCount) +
            (r.lastHitAt?.let { ", " + stringResource(R.string.blk_last_ago, ago(it, now)) } ?: "")
    } else {
        null
    }
    return listOfNotNull(
        BlockingText.ruleDescribe(context, r).takeIf { r.note != null || !r.type.isNumberRule },
        if (r.kind == RuleKind.BLOCK) stringResource(action) else null,
        r.schedule?.let { BlockingText.schedule(context, it) },
        r.simId?.let { id -> vm.sims.value.firstOrNull { it.id == id }?.label ?: stringResource(R.string.blk_one_sim) },
        r.expiresAt?.let { if (expired) stringResource(R.string.blk_expired) else stringResource(R.string.blk_for_duration, leftText(context, it - now)) },
        hits,
    ).joinToString(" · ")
}

/**
 * "You're on: Only people I know" and the setups to switch to, each opening what it changes before it's applied;
 * then the week in one quiet line ("12 calls silenced · no contacts affected").
 */
@Composable
internal fun PresetHeader(current: List<ScreeningPreset>, week: ScreeningWeek, pick: (ScreeningPreset) -> Unit) {
    val names = current.map { stringResource(it.title) }
    val on = if (names.isEmpty()) stringResource(R.string.blk_own_mix) else names.joinToString(" · ")
    Card(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.blk_youre_on, on), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
            Text(weekLine(week), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScreeningPreset.entries.forEach { p ->
                    FilterChip(p in current, { pick(p) }, label = { Text(stringResource(p.title)) })
                }
            }
            Text(
                stringResource(R.string.blk_setups_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
internal fun weekLine(w: ScreeningWeek): String {
    if (w.stopped == 0) return stringResource(R.string.blk_week_none)
    val calls = when {
        w.declined == 0 -> pluralStringResource(R.plurals.blk_week_silenced, w.silenced, w.silenced)
        w.silenced == 0 -> pluralStringResource(R.plurals.blk_week_declined_only, w.declined, w.declined)
        else -> stringResource(
            R.string.blk_week_both,
            pluralStringResource(R.plurals.blk_week_silenced, w.silenced, w.silenced),
            pluralStringResource(R.plurals.blk_week_declined, w.declined, w.declined),
        )
    }
    val contacts = if (w.contactsAffected == 0) stringResource(R.string.blk_week_no_contacts)
    else pluralStringResource(R.plurals.blk_week_contacts, w.contactsAffected, w.contactsAffected)
    return stringResource(R.string.blk_week_line, calls, contacts)
}
