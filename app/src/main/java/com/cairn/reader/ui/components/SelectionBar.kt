package com.cairn.reader.ui.components

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The contextual action bar every list surface shows once one or more items are selected:
 * a close (✕) button, the running count, an optional "select all", then the surface's own
 * bulk actions. Kept in one place so multi-select looks and behaves identically everywhere
 * (Inbox, Read Later, Library, Trash, Offline, …).
 */
@Composable
fun SelectionActionBar(
    count: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectAll: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_selection)) }
        Text(
            "$count selected",
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurface,
        )
        if (onSelectAll != null) {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.DoneAll, contentDescription = stringResource(R.string.select_all), tint = scheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.weight(1f))
        actions()
        Spacer(Modifier.width(4.dp))
    }
}
