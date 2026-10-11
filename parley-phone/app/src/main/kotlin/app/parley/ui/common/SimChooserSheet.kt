package app.parley.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.calls.SimCard
import app.parley.common.calls.SimReason
import app.parley.ui.Bidi
import app.parley.ui.ParleyShapes
import app.parley.ui.ParleySheet
import app.parley.ui.Spacing

/**
 * "Call Ana with": one card per SIM, in the order they sit in the phone. A card shows the SIM's colour and slot, its
 * name as set on the phone ("Work"), the network and its own number when Android gives them, Roaming while it roams
 * and Suggested (with why) for the SIM calls with this number drop less on. A tap on a card calls; "Remember" keeps
 * that SIM for the number. The whole card is one TalkBack stop that reads all of it and says "Call".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimChooserSheet(
    title: String,
    cards: List<SimCard>,
    rememberLabel: String,
    remember: Boolean,
    onRemember: (Boolean) -> Unit,
    countryIso: String,
    planSummary: @Composable (simId: String) -> String?,
    onPick: (simId: String) -> Unit,
    onCancel: () -> Unit,
) {
    ParleySheet(onDismissRequest = onCancel, title = title) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            cards.forEach { card -> SimCardItem(card, countryIso, planSummary(card.id)) { onPick(card.id) } }
            Row(
                Modifier.fillMaxWidth().clip(ParleyShapes.control)
                    .toggleable(remember, role = Role.Checkbox, onValueChange = onRemember)
                    .heightIn(min = 48.dp).padding(end = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(remember, onCheckedChange = null, modifier = Modifier.padding(Spacing.m))
                Text(rememberLabel, style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = Spacing.s), horizontalArrangement = Arrangement.End) {
                TextButton(onCancel, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.dc_cancel)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimCardItem(card: SimCard, countryIso: String, plan: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val suggested = card.suggested != null
    val shape = ParleyShapes.card
    Surface(
        color = if (suggested) colors.secondaryContainer else colors.surfaceContainerHigh,
        contentColor = if (suggested) colors.onSecondaryContainer else colors.onSurface,
        shape = shape,
        modifier = Modifier.fillMaxWidth()
            .then(if (suggested) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .clip(shape)
            .clickable(onClickLabel = stringResource(R.string.action_call), role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.heightIn(min = 72.dp).padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            SimChip(card)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val line = listOfNotNull(card.carrier, card.number?.let { Bidi.ltr(Format.number(it, countryIso)) }).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                plan?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                if (card.roaming || suggested) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (suggested) Tag(stringResource(R.string.call_sim_suggested), colors.primary, colors.onPrimary)
                        if (card.roaming) Tag(stringResource(R.string.call_sim_roaming), colors.tertiaryContainer, colors.onTertiaryContainer)
                    }
                }
                card.suggested?.let { Text(stringResource(reasonText(it)), style = MaterialTheme.typography.bodySmall) }
            }
            Icon(Icons.Rounded.Call, null, tint = colors.primary)
        }
    }
}

/** The SIM's colour (as set on the phone) with its slot number, or the SIM icon when Android doesn't say the slot. */
@Composable
private fun SimChip(card: SimCard) {
    val fill = if (card.color != 0) Color(card.color) else MaterialTheme.colorScheme.secondaryContainer
    val on = if (card.color == 0) MaterialTheme.colorScheme.onSecondaryContainer else if (fill.luminance() > 0.5f) Color.Black else Color.White
    val slot = card.slot.takeIf { it >= 0 }?.let { stringResource(R.string.call_sim_slot, it + 1) }
    Box(
        // TalkBack reads "SIM 1"; the colour is decoration.
        Modifier.size(44.dp).clip(ParleyShapes.control).background(fill).clearAndSetSemantics { if (slot != null) contentDescription = slot },
        contentAlignment = Alignment.Center,
    ) {
        if (slot != null) {
            Text((card.slot + 1).toString(), style = MaterialTheme.typography.titleMedium, color = on)
        } else {
            Icon(Icons.Rounded.SimCard, null, tint = on)
        }
    }
}

@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = ParleyShapes.tag) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs))
    }
}

private fun reasonText(r: SimReason): Int = when (r) {
    SimReason.FEWER_DROPS -> R.string.call_sim_reason_fewer_drops
}
