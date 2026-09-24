package app.parley.ui.contact

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.people.HandleLink
import app.parley.common.people.Handles
import app.parley.data.HandleItem
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.common.Intents

/** U3: a labelled quick-action tile (label ≥ 12 sp); long-press offers the alternative (choose again). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScope.ActionTile(icon: ImageVector, label: String, enabled: Boolean, onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        modifier = Modifier.weight(1f).heightIn(min = 72.dp),
    ) {
        Column(
            Modifier.combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Transparent rows for grouped cards. */
@Composable
fun groupRowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * One row of a grouped section. U2: the section's icon only on the first row ([showIcon]); the others keep the
 * space so the text lines up. [menu] items appear on long-press (copy, set default…).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupDataRow(
    icon: ImageVector,
    showIcon: Boolean,
    text: String,
    label: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
    headline: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = { if (menu != null) open = true else Intents.copy(context, text) },
                onLongClickLabel = stringResource(if (menu != null) R.string.main_more_actions else R.string.main_copy),
            ),
            colors = groupRowColors(),
            leadingContent = { if (showIcon) Icon(icon, null) else Spacer(Modifier.size(24.dp)) },
            headlineContent = headline ?: { Text(text) },
            supportingContent = label?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            trailingContent = trailing,
        )
        if (menu != null) {
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.main_copy)) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { open = false; Intents.copy(context, text) })
                menu { open = false }
            }
        }
    }
}

/**
 * I1: the "Messengers" rows for handles: tap opens the handle in its app (explicit package when known, else the
 * system chooser; web links ask first through [onWeb]); long-press copies.
 */
fun SegmentedGroupScope.handleRows(handles: List<HandleItem>, icon: ImageVector, onWeb: (HandleLink) -> Unit, firstHasIcon: Boolean = true) {
    handles.filter { it.value.isNotBlank() }.forEachIndexed { i, h ->
        item {
            val context = LocalContext.current
            val link = remember(h) { Handles.link(h.handle) }
            GroupDataRow(
                icon, showIcon = firstHasIcon && i == 0, text = h.value, label = app.parley.ui.people.HandleText.label(androidx.compose.ui.platform.LocalResources.current, h.handle),
                onClick = {
                    if (link == null) Intents.copy(context, h.value)
                    else if (!ContactMessaging.openHandle(context, link)) onWeb(link)
                },
                trailing = if (link != null) ({ Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.detail_open_in_app), tint = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
            )
        }
    }
}

/** A short line under a section ("WhatsApp can't see your contacts…"). */
@Composable
fun GroupNote(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
