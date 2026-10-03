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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalResources
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
import app.parley.ui.people.HandleText
import app.parley.ui.ParleyShapes
import app.parley.ui.Spacing

/** A labelled quick-action tile (label ≥ 12 sp, 64 dp tall); long-press offers the alternative (choose again). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowScope.ActionTile(icon: ImageVector, label: String, enabled: Boolean, onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, onClick: () -> Unit) {
    Surface(
        shape = ParleyShapes.card,
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        modifier = Modifier.weight(1f).heightIn(min = 64.dp),
    ) {
        Column(
            Modifier.combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = longClickLabel)
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(
                label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Transparent rows for grouped cards. */
@Composable
fun groupRowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * One row of a grouped section. U2: the section's icon only on the first row ([showIcon]); the others keep the
 * space so the text lines up. [menu] items appear on long-press (copy, set default…). Drawn as a compact [InfoRow].
 * Without [onClick] (a fact with nothing to open) a tap copies the value, so the row never offers an action that does
 * nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupDataRow(
    icon: ImageVector,
    showIcon: Boolean,
    text: String,
    label: String?,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
    headline: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        InfoRow(
            modifier = Modifier.combinedClickable(
                onClickLabel = if (onClick == null) stringResource(R.string.main_copy) else null,
                onClick = onClick ?: { Intents.copy(context, text) },
                onLongClick = { if (menu != null) open = true else Intents.copy(context, text) },
                onLongClickLabel = stringResource(if (menu != null) R.string.main_more_actions else R.string.main_copy),
            ),
            leading = { if (showIcon) Icon(icon, null) },
            headline = headline ?: { Text(text) },
            supporting = label?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            trailing = trailing,
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
 * The contact page's row: the value, and its label as a supporting line under it ("Mobile · WhatsApp, Signal"),
 * like the phone's own contacts apps. 56 dp for one line and 60 dp for two, instead of the 72 dp of a Material
 * two-line list item, because a contact's page is many short facts: the text keeps its size, only the padding
 * around it shrinks. The leading slot is a 24 dp gutter (empty keeps the text lined up); [trailing] holds 48 dp
 * icon actions. It grows with large fonts. Put the click in [modifier].
 */
@Composable
fun InfoRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    headline: @Composable () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.l, end = if (trailing != null) Spacing.xs else Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) { leading() }
            }
            Spacer(Modifier.width(Spacing.l))
        }
        Column(Modifier.weight(1f).padding(vertical = Spacing.s)) {
            CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge, headline)
            }
            if (supporting != null) {
                CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, supporting)
                }
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.xs))
            CompositionLocalProvider(LocalContentColor provides cs.onSurfaceVariant) {
                Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        }
    }
}

/**
 * The "Messengers" rows for handles: tap opens the handle in its app (explicit package when known, else the
 * system chooser; web links ask first through [onWeb]); long-press copies.
 */
fun SegmentedGroupScope.handleRows(handles: List<HandleItem>, icon: ImageVector, onWeb: (HandleLink) -> Unit, firstHasIcon: Boolean = true) {
    handles.filter { it.value.isNotBlank() }.forEachIndexed { i, h ->
        item {
            val context = LocalContext.current
            val link = remember(h) { Handles.link(h.handle) }
            GroupDataRow(
                icon, showIcon = firstHasIcon && i == 0, text = h.value, label = HandleText.label(LocalResources.current, h.handle),
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
