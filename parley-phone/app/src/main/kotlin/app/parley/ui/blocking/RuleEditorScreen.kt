package app.parley.ui.blocking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.blocking.BlockingText
import app.parley.common.BlockAction
import app.parley.common.BlockRule
import app.parley.common.LineType
import app.parley.common.NotifyLevel
import app.parley.common.RuleKind
import app.parley.common.RuleTools
import app.parley.common.RuleType
import app.parley.common.Schedule
import app.parley.common.lineTypeLabel
import app.parley.data.DryRun
import app.parley.data.GroupInfo
import app.parley.ui.common.Format
import app.parley.ui.settings.bidiLtr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun typeLabel(t: RuleType) = stringResource(
    when (t) {
        RuleType.EXACT -> R.string.blk_type_exact
        RuleType.PREFIX -> R.string.blk_type_prefix
        RuleType.WILDCARD -> R.string.blk_type_wildcard
        RuleType.CALLER_NAME -> R.string.blk_type_caller_name
        RuleType.REGION -> R.string.blk_type_region
        RuleType.NOT_MY_REGION -> R.string.blk_type_not_my_region
        RuleType.LINE_TYPE -> R.string.blk_type_line_type
        RuleType.LABEL -> R.string.blk_type_label
    },
)

private val LINE_TYPES = listOf(LineType.VOIP, LineType.PREMIUM_RATE, LineType.SHARED_COST, LineType.TOLL_FREE, LineType.UAN, LineType.PERSONAL_NUMBER, LineType.MOBILE, LineType.FIXED_LINE)

/**
 * Full rule editor (B1, B9, B16, B17, B18, B20, B24, B6): allow or block, what to match with a live preview,
 * SIM, schedule, notification, ringtone, expiry, and "try it on last week" before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(vm: AppViewModel, ruleId: Long, initial: BlockRule, back: () -> Unit) {
    val rules by vm.c.blocks.rules.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val isDefault by vm.isDefaultDialer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(rules) { rules.firstOrNull { it.id == ruleId } }
    var loadedFrom by remember { mutableStateOf<Long?>(null) }
    var r by remember { mutableStateOf(initial) }
    LaunchedEffect(existing) {
        if (existing != null && loadedFrom != existing.id) {
            r = existing
            loadedFrom = existing.id
        }
    }
    var groups by remember { mutableStateOf<List<GroupInfo>>(emptyList()) }
    LaunchedEffect(r.type) { if (r.type == RuleType.LABEL && groups.isEmpty()) groups = withContext(Dispatchers.IO) { vm.c.contacts.groups() } }
    val checked = remember(r.pattern, r.type) { RuleTools.check(r.pattern, r.type, vm.countryIso) }
    val preview = remember(checked.pattern, r.type) { if (checked.error == null && r.type.isNumberRule) RuleTools.preview(r.copy(pattern = checked.pattern), vm.countryIso) else emptyList() }
    var dry by remember { mutableStateOf<DryRun?>(null) }
    var dryRunning by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pickTone = rememberRingtonePicker { r = r.copy(ringtone = it) }
    val allow = r.kind == RuleKind.ALLOW

    fun save() {
        scope.launch {
            vm.c.blocks.saveRule(r.copy(pattern = checked.pattern, note = r.note?.trim()?.ifBlank { null }))
            back()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (ruleId == 0L) R.string.blk_new_rule else R.string.blk_edit_rule)) },
            navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.set_back)) } },
            actions = {
                if (ruleId != 0L) IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, stringResource(R.string.blk_delete_rule)) }
                TextButton(::save, enabled = checked.error == null) { Text(stringResource(R.string.set_save)) }
            },
        )
    }) { p ->
        Column(Modifier.padding(p).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(RuleKind.BLOCK to stringResource(R.string.blk_block), RuleKind.ALLOW to stringResource(R.string.blk_always_allow)).forEachIndexed { i, (k, label) ->
                    SegmentedButton(r.kind == k, { r = r.copy(kind = k) }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Text(
                stringResource(if (allow) R.string.blk_editor_allow_help else R.string.blk_editor_block_help),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(stringResource(R.string.blk_editor_match), style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RuleType.entries.forEach { t -> FilterChip(r.type == t, { r = r.copy(type = t, pattern = if (t.isNumberRule == r.type.isNumberRule) r.pattern else "") }, label = { Text(typeLabel(t)) }) }
            }
            when (r.type) {
                RuleType.EXACT, RuleType.PREFIX, RuleType.WILDCARD, RuleType.CALLER_NAME, RuleType.REGION -> OutlinedTextField(
                    r.pattern, { r = r.copy(pattern = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    // Numbers and patterns stay left-to-right in Arabic and Urdu; a caller name follows its own script.
                    textStyle = if (r.type.isNumberRule) LtrText() else androidx.compose.material3.LocalTextStyle.current,
                    label = {
                        Text(
                            stringResource(
                                when (r.type) {
                                    RuleType.EXACT -> R.string.blk_phone_number
                                    RuleType.PREFIX -> R.string.blk_type_prefix
                                    RuleType.WILDCARD -> R.string.blk_type_wildcard
                                    RuleType.CALLER_NAME -> R.string.blk_field_name_contains
                                    else -> R.string.blk_field_country_codes
                                },
                            ),
                        )
                    },
                    supportingText = {
                        Text(
                            checked.error?.let { BlockingText.ruleCheck(context, it) } ?: stringResource(
                                when (r.type) {
                                    RuleType.EXACT -> R.string.blk_help_exact
                                    RuleType.PREFIX -> R.string.blk_help_prefix
                                    RuleType.WILDCARD -> R.string.blk_help_wildcard
                                    RuleType.CALLER_NAME -> R.string.blk_help_caller_name
                                    else -> R.string.blk_help_region
                                },
                            ),
                        )
                    },
                    isError = r.pattern.isNotBlank() && checked.error != null,
                )
                RuleType.NOT_MY_REGION -> Text(stringResource(R.string.blk_editor_not_my_region, vm.countryIso.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                RuleType.LINE_TYPE -> {
                    val selected = r.pattern.split(',').filter { it.isNotBlank() }.toSet()
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LINE_TYPES.forEach { lt ->
                            val on = lt.name in selected
                            FilterChip(on, { r = r.copy(pattern = (if (on) selected - lt.name else selected + lt.name).joinToString(",")) }, label = { Text(BlockingText.lineType(context, lt.name)) })
                        }
                    }
                    Text(stringResource(R.string.blk_editor_line_type_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RuleType.LABEL -> {
                    if (groups.isEmpty()) Text(stringResource(R.string.blk_editor_no_labels), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // By title: the rule covers the label in every account.
                        groups.map { app.parley.common.LabelRefs.key(it.title) }.distinct().forEach { t ->
                            FilterChip(r.labelKey == t, { r = r.copy(pattern = t, label = t, ringtone = null) }, label = { Text(t) })
                        }
                    }
                    Text(
                        stringResource(if (allow) R.string.blk_editor_label_allow_help else R.string.blk_editor_label_block_help),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (checked.error == null && r.type.isNumberRule && r.pattern.isNotBlank()) {
                if (checked.pattern != r.pattern.trim()) Text(stringResource(R.string.blk_editor_saved_as, bidiLtr(checked.pattern)), style = MaterialTheme.typography.bodySmall)
                if (preview.isNotEmpty()) Text(stringResource(R.string.blk_editor_will_match, preview.joinToString(", ") { bidiLtr(it) }), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            checked.warnings.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.tertiary)
                    Text("  " + BlockingText.ruleCheck(context, w), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }

            if (!allow) {
                Text(stringResource(R.string.blk_editor_what_happens), style = MaterialTheme.typography.titleSmall)
                ActionChoice(r.action, { r = r.copy(action = it) })
                Text(stringResource(R.string.blk_editor_action_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (sims.size >= 2) {
                Text(stringResource(R.string.blk_editor_sim), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(r.simId == null, { r = r.copy(simId = null) }, label = { Text(stringResource(R.string.blk_editor_any_sim)) })
                    sims.forEach { s -> FilterChip(r.simId == s.id, { r = r.copy(simId = s.id) }, label = { Text(s.label) }) }
                }
                if (r.simId != null && !isDefault) {
                    Text(
                        stringResource(R.string.blk_editor_sim_unavailable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Text(stringResource(R.string.blk_editor_when), style = MaterialTheme.typography.titleSmall)
            ScheduleField(r.schedule, { r = r.copy(schedule = it) })

            if (allow) {
                Text(stringResource(R.string.blk_editor_how_long), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val now = remember { System.currentTimeMillis() }
                    FilterChip(r.expiresAt == null, { r = r.copy(expiresAt = null) }, label = { Text(stringResource(R.string.blk_always)) })
                    FilterChip(r.expiresAt != null && r.expiresAt!! - now <= 25 * 3_600_000L, { r = r.copy(expiresAt = now + 24 * 3_600_000L) }, label = { Text(stringResource(R.string.ct_hours_short, 24)) })
                    FilterChip(r.expiresAt != null && r.expiresAt!! - now > 25 * 3_600_000L, { r = r.copy(expiresAt = now + 7 * 86_400_000L) }, label = { Text(pluralStringResource(R.plurals.set_days, 7, 7)) })
                }
                r.expiresAt?.let { Text(stringResource(R.string.blk_editor_until, Format.fullDate(context, it)), style = MaterialTheme.typography.bodySmall) }
                // A label's ringtone has one home: the label's page in Contacts.
                if (r.type != RuleType.LABEL) {
                    Text(stringResource(R.string.blk_editor_ringtone), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton({ pickTone(r.ringtone) }) { Text(ringtoneTitle(context, r.ringtone) ?: stringResource(R.string.set_same_as_usual)) }
                        if (r.ringtone != null) TextButton({ r = r.copy(ringtone = null) }) { Text(stringResource(R.string.set_reset)) }
                    }
                }
            } else {
                Text(stringResource(R.string.blk_editor_notify_me), style = MaterialTheme.typography.titleSmall)
                NotifyChoice(r.notify, allowDefault = true) { r = r.copy(notify = it) }
            }

            OutlinedTextField(
                r.note.orEmpty(), { r = r.copy(note = it) },
                label = { Text(stringResource(R.string.blk_editor_name)) }, placeholder = { Text(stringResource(R.string.blk_editor_name_hint)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            if (!allow && checked.error == null && (r.pattern.isNotBlank() || r.type == RuleType.NOT_MY_REGION)) {
                OutlinedButton({
                    dryRunning = true
                    scope.launch {
                        val calls = vm.c.callLog.calls.value.orEmpty()
                        dry = runCatching { vm.c.screener.dryRun(calls, 7, candidateRule = r.copy(id = -1, pattern = checked.pattern, enabled = true, hitCount = 0)) }.getOrNull()
                        dryRunning = false
                    }
                }, enabled = !dryRunning) { Text(stringResource(if (dryRunning) R.string.blk_editor_dry_busy else R.string.blk_editor_dry_button)) }
                dry?.let { d ->
                    val added = d.added
                    Text(
                        if (added.isEmpty()) stringResource(R.string.blk_editor_dry_none)
                        else pluralStringResource(
                            R.plurals.blk_editor_dry_result, added.size, added.size,
                            added.take(5).joinToString(", ") { bidiLtr(Format.number(it.call.number, vm.countryIso)) } + if (added.size > 5) "…" else "",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(stringResource(R.string.blk_editor_dry_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (existing != null && existing.hitCount > 0) {
                Text(
                    pluralStringResource(R.plurals.blk_calls, existing.hitCount, existing.hitCount) + (existing.lastHitAt?.let { ", " + stringResource(R.string.blk_last_ago, ago(it)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(::save, enabled = checked.error == null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.blk_save_rule)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.blk_delete_rule_q)) },
            confirmButton = { TextButton({ scope.launch { vm.c.blocks.deleteRule(ruleId); back() } }) { Text(stringResource(R.string.blk_delete)) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}

/** Defaults for a new rule from the route. */
fun newRule(kind: RuleKind, type: RuleType, pattern: String) = BlockRule(
    pattern = pattern, type = type, kind = kind, action = BlockAction.REJECT, notify = NotifyLevel.DEFAULT,
    schedule = null as Schedule?,
)
