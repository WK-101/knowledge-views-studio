package app.parley.ui.sync.shared

import android.content.res.Resources
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.common.sync.shared.ShieldKind
import app.parley.common.sync.shared.ShieldMode
import app.parley.common.sync.shared.ShieldOwn
import app.parley.data.sync.shared.SharedLabelState
import app.parley.ui.ChoiceRow
import app.parley.ui.ConfirmDialog
import app.parley.ui.Destination
import app.parley.ui.InfoRow
import app.parley.ui.LinkRow
import app.parley.ui.ParleyListItem
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import app.parley.ui.SwitchRow
import app.parley.ui.common.Format
import app.parley.ui.rowColors
import app.parley.ui.settings.bidiLtr
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope

/** Own verdicts the page lists before "And n more" (the rest are shared all the same). */
private const val SHOWN_OWN = 100

internal object FamilyShieldTexts {
    fun mode(res: Resources, m: ShieldMode): String = res.getString(
        when (m) {
            ShieldMode.WARN -> R.string.fsh_mode_warn
            ShieldMode.SILENCE -> R.string.fsh_mode_silence
            ShieldMode.BLOCK -> R.string.fsh_mode_block
        },
    )

    fun kind(res: Resources, k: ShieldKind): String = res.getString(
        when (k) {
            ShieldKind.BLOCKED -> R.string.fsh_kind_blocked
            ShieldKind.SCAM -> R.string.fsh_kind_scam
            ShieldKind.SPAM_LIKELY -> R.string.fsh_kind_spam
        },
    )
}

/** "Warn each other in Family?": what is shared, said once before the shield goes on. */
@Composable
private fun ExplainDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.fsh_explain_title, title),
        text = stringResource(R.string.fsh_explain_body, title),
        confirmLabel = stringResource(R.string.fsh_turn_on),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        icon = Icons.Rounded.Shield,
    )
}

/** Turns the shield on (after [ExplainDialog]) or off, with what a match does kept. */
private fun AppViewModel.setShield(s: SharedLabelState, on: Boolean, mode: ShieldMode, res: Resources) {
    // Not tied to the screen: the run that follows writes this phone's journal even if the page is left.
    viewModelScope.launch { if (!c.sharedLabels.setShield(s.labelId, on, mode)) toast(res.getString(R.string.fsh_failed)) }
}

/**
 * The label page's row for the shield: its switch (off by default; turning it on explains what is shared first) and
 * the way to its page. Only for a label that syncs here.
 */
@Composable
internal fun FamilyShieldRow(vm: AppViewModel, s: SharedLabelState, open: (Destination) -> Unit) {
    if (!SharedLabelMembership.syncs(s.membership)) return
    val res = LocalResources.current
    var explain by remember { mutableStateOf(false) }
    val sub = if (s.shieldOn) stringResource(R.string.fsh_row_on, FamilyShieldTexts.mode(res, s.shieldMode)) else stringResource(R.string.fsh_row_off)
    SwitchRow(stringResource(R.string.fsh_title), sub, s.shieldOn, Icons.Rounded.Shield) { on ->
        if (on) explain = true else vm.setShield(s, false, s.shieldMode, res)
    }
    // Its page: what a match does here, who shares what, and withdrawing.
    if (s.shieldOn) LinkRow(stringResource(R.string.fsh_page), null) { open(SharedLabelRoutes.Shield(s.labelId)) }
    if (explain) {
        ExplainDialog(s.title, onConfirm = { explain = false; vm.setShield(s, true, s.shieldMode, res) }, onDismiss = { explain = false })
    }
}

/**
 * The shield's page for one shared label: on or off, what a match does here, what each member shares (counts only:
 * their numbers are hashed), and this phone's own warnings, each of which can be withdrawn.
 */
@Composable
fun FamilyShieldScreen(vm: AppViewModel, id: String, back: () -> Unit) {
    val res = LocalResources.current
    val shared = vm.c.sharedLabels
    LaunchedEffect(Unit) { shared.load() }
    val states by shared.states.collectAsStateWithLifecycle()
    val s = states.firstOrNull { it.labelId == id }
    LaunchedEffect(s == null, states.isNotEmpty()) { if (s == null && states.isNotEmpty()) back() }
    if (s == null) return
    var explain by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<List<ShieldOwn>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { mine = shared.shield?.mine() }
    val me = rememberMyHex(vm)
    SettingsScaffold(stringResource(R.string.fsh_title), back) {
        Text(
            stringResource(R.string.fsh_explain_body, s.title), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        SegmentedGroup {
            item("on") {
                SwitchRow(stringResource(R.string.fsh_switch), stringResource(R.string.fsh_switch_sub), s.shieldOn, Icons.Rounded.Shield) { on ->
                    if (on) explain = true else vm.setShield(s, false, s.shieldMode, res)
                }
            }
            item("mode") {
                val modes = ShieldMode.entries
                ChoiceRow(stringResource(R.string.fsh_mode_title), modes.map { FamilyShieldTexts.mode(res, it) }, modes.indexOf(s.shieldMode)) { i ->
                    vm.setShield(s, s.shieldOn, modes[i], res)
                }
            }
        }
        Text(
            listOfNotNull(stringResource(R.string.fsh_never), stringResource(R.string.fsh_by_file).takeIf { s.byFile && s.shieldOn })
                .joinToString("\n"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        if (s.shieldOn) FromOthers(s, me)
        Yours(vm, s.shieldOn, mine) { reload++ }
        Text(
            stringResource(R.string.fsh_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
    }
    if (explain) {
        ExplainDialog(s.title, onConfirm = { explain = false; vm.setShield(s, true, s.shieldMode, res) }, onDismiss = { explain = false })
    }
}

/** What each other member shares: counts only, since their numbers arrive hashed. */
@Composable
private fun FromOthers(s: SharedLabelState, me: String?) {
    SegmentedGroup(stringResource(R.string.fsh_from_others)) {
        val names = s.members.associate { it.keyHex to it.name }
        val from = s.shieldIn.filterKeys { it != me }.entries.sortedByDescending { it.value.size }
        if (from.isEmpty()) item("none") { InfoRow(stringResource(R.string.fsh_from_none), null) }
        from.forEach { (hex, list) ->
            item(hex) {
                val name = names[hex]?.ifBlank { null } ?: stringResource(R.string.shl_someone)
                InfoRow(pluralStringResource(R.plurals.fsh_from_member, list.size, name, list.size), null, Icons.Rounded.Person)
            }
        }
    }
}

/** This phone's own warnings ([mine] null while read), each with Withdraw. */
@Composable
private fun Yours(vm: AppViewModel, on: Boolean, mine: List<ShieldOwn>?, onChanged: () -> Unit) {
    SegmentedGroup(stringResource(R.string.fsh_yours)) {
        val list = mine.orEmpty()
        when {
            !on -> item("off") { InfoRow(stringResource(R.string.fsh_yours_off), null) }
            mine != null && list.isEmpty() -> item("none") { InfoRow(stringResource(R.string.fsh_yours_none), null) }
            else -> {
                list.take(SHOWN_OWN).forEach { v -> item(v.e164) { OwnRow(vm, v, onChanged) } }
                if (list.size > SHOWN_OWN) {
                    item("more") { InfoRow(pluralStringResource(R.plurals.fsh_more, list.size - SHOWN_OWN, list.size - SHOWN_OWN), null) }
                }
            }
        }
    }
}

/** One number this phone shares: its kind and when, and Withdraw. */
@Composable
private fun OwnRow(vm: AppViewModel, v: ShieldOwn, onChanged: () -> Unit) {
    val res = LocalResources.current
    val number = bidiLtr(Format.number(v.e164, vm.countryIso))
    ParleyListItem(
        headlineContent = { Text(number) },
        supportingContent = { Text(stringResource(R.string.fsh_own_line, FamilyShieldTexts.kind(res, v.kind), SharedLabelTexts.ago(v.at))) },
        trailingContent = {
            val cd = stringResource(R.string.fsh_withdraw_cd, number)
            TextButton(
                {
                    vm.viewModelScope.launch {
                        val ok = vm.c.sharedLabels.withdrawFromShield(v.e164)
                        vm.toast(res.getString(if (ok) R.string.fsh_withdrawn else R.string.fsh_failed))
                        onChanged()
                    }
                },
                modifier = Modifier.semantics { contentDescription = cd },
            ) { Text(stringResource(R.string.fsh_withdraw)) }
        },
        colors = rowColors(),
    )
}
