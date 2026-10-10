package app.parley.telecom.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.parley.common.calls.VerifyCallBack
import app.parley.telecom.CallManager
import app.parley.telecom.CallUi
import app.parley.telecom.R
import app.parley.telecom.SimTip
import app.parley.telecom.TelecomGraph
import app.parley.ui.Bidi
import app.parley.ui.CallColors
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleyType
import app.parley.ui.Spacing
import app.parley.ui.rowColors
import app.parley.ui.systemMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A connected call the network dropped. The reason in plain words ("Lost signal · Wi-Fi calling") and a big
 * Call again, the same number on the same SIM. It stays a few seconds, or until dismissed.
 */
@Composable
internal fun DropCard(call: CallUi, onCallAgain: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, onSimTip: (Boolean) -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = ParleyShapes.panel,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SignalCellularConnectedNoInternet0Bar, null, tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.m))
                Text(stringResource(R.string.call_drop_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            call.dropText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.xs))
            }
            // Never on a call masked on the lock screen: it names the person.
            call.simTip?.takeIf { !call.lockMasked }?.let { tip -> SimTipRow(tip, onSimTip) }
            if (call.canCallAgain) {
                Spacer(Modifier.height(Spacing.l))
                Button(
                    onClick = onCallAgain,
                    colors = ButtonDefaults.buttonColors(containerColor = CallColors.Accept, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Icon(Icons.Rounded.Call, null, Modifier.size(24.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.call_drop_call_again), style = MaterialTheme.typography.titleMedium)
                }
            }
            TextButton(onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.call_failed_dismiss)) }
        }
    }
}

/** "Calls to Ana drop less on SIM 2": use that SIM for them from now on, or no thanks (it isn't offered again). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimTipRow(tip: SimTip, onAnswer: (Boolean) -> Unit) {
    Column(Modifier.padding(top = Spacing.m)) {
        Text(
            stringResource(R.string.call_sim_tip, tip.name, tip.simLabel),
            style = MaterialTheme.typography.bodyMedium,
        )
        // Wraps under large fonts and on narrow screens rather than squeezing the labels.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.xs)) {
            FilledTonalButton({ onAnswer(true) }, Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.call_sim_tip_use, tip.simLabel, tip.name))
            }
            TextButton({ onAnswer(false) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.call_sim_tip_no)) }
        }
    }
}

/**
 * Hold mode, in place of the grid: how long you've been waiting, what Parley does and doesn't do (it can't hear
 * the call), the keypad for "press 1 to keep holding", and a big way out.
 */
@Composable
internal fun HoldModePanel(call: CallUi, onKeypad: () -> Unit) {
    val now by rememberElapsedNow()
    val waited = ((now - call.holdModeSince).coerceAtLeast(0)) / 1000
    val res = LocalResources.current
    val spoken = stringResource(R.string.holdmode_waited, spokenDuration(res, waited))
    Column(
        Modifier.widthIn(max = CallButtonSize.panelMaxWidth).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.HourglassTop, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.holdmode_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            clockText(waited),
            style = ParleyType.bigClock,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.xs).semantics { contentDescription = spoken },
        )
        Text(
            stringResource(R.string.holdmode_honest), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = Spacing.s, start = Spacing.l, end = Spacing.l),
        )
        Spacer(Modifier.height(Spacing.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onKeypad, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Rounded.Dialpad, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.incall_keypad))
            }
            Button(onClick = { CallManager.stopHoldMode(call.id) }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.holdmode_end), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** What the "Check it's really them" sheet lists for a caller who claims to be an organisation. */
private sealed interface Organisations {
    data object Loading : Organisations
    data object Locked : Organisations
    data class Loaded(val list: List<VerifyCallBack.Saved>) : Organisations
}

/**
 * "Check it's really them": hang up and call the number you saved, since caller ID can be faked but your saved
 * number reaches the real person or organisation. For a contact (or a private contact), their saved numbers, the one
 * that called first; for anyone, the saved organisations to pick from (a caller claiming to be "the bank"). Shown only
 * after the phone is unlocked; with an ended call ([live] false) it just dials.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerifySheet(call: CallUi, live: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val number = call.number?.takeIf { !call.hidden && it.isNotBlank() }
    val own by produceState<List<VerifyCallBack.Saved>?>(null, number, call.accountId) {
        value = if (number == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { VerifyCallBack.choices(TelecomGraph.dependencies.savedNumbersFor(number, call.accountId), number) }.getOrDefault(emptyList())
        }
    }
    var pickOrganisation by remember { mutableStateOf(false) }
    val organisations by produceState<Organisations>(Organisations.Loading, pickOrganisation) {
        if (!pickOrganisation) return@produceState
        val list = withContext(Dispatchers.IO) { runCatching { TelecomGraph.dependencies.savedOrganisations() }.getOrDefault(emptyList()) }
        value = if (list == null) Organisations.Locked else Organisations.Loaded(VerifyCallBack.organisations(list))
    }
    val app = context.applicationContext
    val dial = { s: VerifyCallBack.Saved ->
        onDismiss()
        CallManager.hangUpAndCall(call.id, s.number, call.accountId) { problem -> systemMessage(app, problem, long = true) }
    }
    ParleySheet(onDismissRequest = onDismiss, title = stringResource(R.string.verify_title)) {
        Text(
            stringResource(if (live) R.string.verify_sheet_body else R.string.verify_sheet_body_ended),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.s),
        )
        own?.forEach { s -> SavedRow(s, Icons.Rounded.Person) { dial(s) } }
        if (!pickOrganisation) {
            ParleyListItem(
                headlineContent = { Text(stringResource(R.string.verify_pick_organisation)) },
                supportingContent = { Text(stringResource(R.string.verify_pick_organisation_hint)) },
                leadingContent = { Icon(Icons.Rounded.Business, null) },
                colors = rowColors(),
                modifier = Modifier.clickable { pickOrganisation = true },
            )
        } else {
            when (val o = organisations) {
                Organisations.Loading -> Unit
                Organisations.Locked -> Note(stringResource(R.string.verify_locked))
                is Organisations.Loaded ->
                    if (o.list.isEmpty()) Note(stringResource(R.string.verify_no_organisations))
                    else o.list.forEach { s -> SavedRow(s, Icons.Rounded.Business) { dial(s) } }
            }
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

@Composable
private fun SavedRow(s: VerifyCallBack.Saved, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val sep = stringResource(R.string.tc_separator)
    ParleyListItem(
        headlineContent = { Text(stringResource(R.string.verify_call_saved, s.name)) },
        supportingContent = { Text(listOfNotNull(s.label?.takeIf { it.isNotBlank() }, Bidi.ltr(s.number)).joinToString(sep)) },
        leadingContent = { Icon(icon, null) },
        colors = rowColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.m),
    )
}

/**
 * A one-time tip on the call screen, for a gesture nobody would find by themselves. Dismissed with its close
 * button, or for good once the gesture is used.
 */
@Composable
internal fun CallTip(text: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, shape = ParleyShapes.card,
        modifier = Modifier.widthIn(max = CallButtonSize.panelMaxWidth).fillMaxWidth().padding(bottom = Spacing.l),
    ) {
        Row(Modifier.padding(start = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lightbulb, null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = Spacing.m))
            IconButton(onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.call_tip_dismiss)) }
        }
    }
}

/** For the picture-in-picture window: "On hold · 12:34" while in hold mode. */
internal fun holdModeSeconds(call: CallUi, now: Long = SystemClock.elapsedRealtime()): Long =
    ((now - call.holdModeSince).coerceAtLeast(0)) / 1000
