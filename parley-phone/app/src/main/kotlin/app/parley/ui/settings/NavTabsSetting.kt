package app.parley.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.NavTabs
import app.parley.common.StartTab
import app.parley.ui.home.icon
import app.parley.ui.home.label
import app.parley.ui.ReorderMath
import app.parley.ui.Spacing
import app.parley.ui.rememberReorderState
import app.parley.ui.reorderActions
import app.parley.ui.reorderHandle
import app.parley.ui.reorderable

/**
 * Show, hide and reorder the home tabs. Drag a row by its handle; TalkBack users get "Move up" / "Move down"
 * actions on each row. The last visible tab can't be switched off.
 */
@Composable
fun NavTabsEditor(
    tabs: NavTabs,
    /** Tabs a combined surface shows instead of the bar (Keypad → Recents, Favorites → Contacts). */
    inside: Map<StartTab, StartTab> = emptyMap(),
    onChange: (NavTabs) -> Unit,
) {
    // Local order while dragging, written back when the finger lifts.
    var order by remember { mutableStateOf(tabs.order) }
    val reorder = rememberReorderState<StartTab>()
    LaunchedEffect(tabs.order) { if (reorder.lifted == null) order = tabs.order }
    val latestTabs by rememberUpdatedState(tabs)
    val latestOnChange by rememberUpdatedState(onChange)
    val shownText = stringResource(R.string.set_tab_shown)
    val hiddenText = stringResource(R.string.label_hidden)
    val moveUp = stringResource(R.string.action_move_up)
    val moveDown = stringResource(R.string.action_move_down)

    Column(Modifier.padding(vertical = Spacing.xs)) {
        order.forEachIndexed { i, t ->
            key(t) {
                val shown = t !in tabs.hidden
                Row(
                    Modifier
                        .fillMaxWidth()
                        .reorderable(reorder, t, shape = MaterialTheme.shapes.medium)
                        .heightIn(min = 56.dp)
                        .toggleable(shown, enabled = shown.not() || tabs.canHide(t), role = Role.Switch) { v -> onChange(tabs.copy(order = order).setVisible(t, v)) }
                        .semantics { stateDescription = if (shown) shownText else hiddenText }
                        .reorderActions(moveUp, moveDown, i, order.size) { to ->
                            val next = if (to < i) tabs.copy(order = order).moveUp(t) else tabs.copy(order = order).moveDown(t)
                            order = next.order
                            onChange(next)
                        }
                        .padding(horizontal = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragHandle, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .padding(Spacing.s)
                            // A tap on the handle doesn't switch the tab on or off; only dragging does something.
                            .reorderHandle(
                                reorder, t, index = { order.indexOf(t) }, count = { order.size },
                                move = { from, to -> order = ReorderMath.moved(order, from, to) },
                                onDrop = { if (order != latestTabs.order) latestOnChange(latestTabs.copy(order = order)) },
                                onCancel = { order = latestTabs.order },
                            ),
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Icon(t.icon, null, tint = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(Spacing.l))
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = MaterialTheme.typography.bodyLarge)
                        inside[t]?.let { host ->
                            if (shown) Text(stringResource(R.string.home_tab_inside, host.label), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (shown && !tabs.canHide(t)) {
                            Text(stringResource(R.string.set_tab_one_stays), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(shown, onCheckedChange = null, enabled = !shown || tabs.canHide(t), modifier = Modifier.padding(end = Spacing.s))
                }
                if (i < order.size - 1) HorizontalDivider(Modifier.padding(start = 64.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
