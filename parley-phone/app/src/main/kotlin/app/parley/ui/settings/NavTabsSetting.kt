package app.parley.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.parley.common.NavTabs
import app.parley.common.StartTab
import app.parley.ui.home.icon
import app.parley.ui.home.label

/**
 * Show, hide and reorder the home tabs. Drag a row by its handle; TalkBack users get "Move up" / "Move down"
 * actions on each row. The last visible tab can't be switched off.
 */
@Composable
fun NavTabsEditor(tabs: NavTabs, onChange: (NavTabs) -> Unit) {
    // Local order while dragging, written back when the finger lifts.
    var order by remember { mutableStateOf(tabs.order) }
    var dragging by remember { mutableStateOf<StartTab?>(null) }
    LaunchedEffect(tabs.order) { if (dragging == null) order = tabs.order }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val latestTabs by androidx.compose.runtime.rememberUpdatedState(tabs)
    val latestOnChange by androidx.compose.runtime.rememberUpdatedState(onChange)

    Column(Modifier.padding(vertical = 4.dp)) {
        order.forEachIndexed { i, t ->
            key(t) {
                val shown = t !in tabs.hidden
                val lifted = dragging == t
                Row(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) dragOffset else 0f }
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent)
                        .onSizeChanged { rowHeight = it.height }
                        .heightIn(min = 56.dp)
                        .toggleable(shown, enabled = shown.not() || tabs.canHide(t), role = Role.Switch) { v -> onChange(tabs.copy(order = order).setVisible(t, v)) }
                        .semantics {
                            stateDescription = if (shown) "Shown in the navigation bar" else "Hidden"
                            customActions = listOfNotNull(
                                if (i > 0) CustomAccessibilityAction("Move up") { onChange(tabs.copy(order = order).moveUp(t)); true } else null,
                                if (i < order.size - 1) CustomAccessibilityAction("Move down") { onChange(tabs.copy(order = order).moveDown(t)); true } else null,
                            )
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragHandle, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                            // A tap on the handle doesn't switch the tab on or off; only dragging does something.
                            .pointerInput(Unit) { detectTapGestures { } }
                            .pointerInput(t) {
                                detectVerticalDragGestures(
                                    onDragStart = { dragging = t; dragOffset = 0f },
                                    onDragEnd = {
                                        dragging = null
                                        dragOffset = 0f
                                        if (order != latestTabs.order) latestOnChange(latestTabs.copy(order = order))
                                    },
                                    onDragCancel = { dragging = null; dragOffset = 0f; order = latestTabs.order },
                                ) { change, dy ->
                                    change.consume()
                                    dragOffset += dy
                                    val from = order.indexOf(t)
                                    val step = rowHeight.toFloat()
                                    if (dragOffset > step / 2 && from < order.size - 1) {
                                        order = order.toMutableList().apply { add(from + 1, removeAt(from)) }
                                        dragOffset -= step
                                    } else if (dragOffset < -step / 2 && from > 0) {
                                        order = order.toMutableList().apply { add(from - 1, removeAt(from)) }
                                        dragOffset += step
                                    }
                                }
                            },
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(t.icon, null, tint = if (shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = MaterialTheme.typography.bodyLarge)
                        if (shown && !tabs.canHide(t)) {
                            Text("At least one tab stays", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Switch(shown, onCheckedChange = null, enabled = !shown || tabs.canHide(t), modifier = Modifier.padding(end = 8.dp))
                }
                if (i < order.size - 1) HorizontalDivider(Modifier.padding(start = 64.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
