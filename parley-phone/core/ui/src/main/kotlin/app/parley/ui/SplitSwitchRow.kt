package app.parley.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A row that opens its own page and has a switch of its own, split by a thin line: the text opens [onOpen], the switch
 * (a 48 dp target, its own TalkBack stop read as [switchLabel]) turns it on or off.
 */
@Composable
fun SplitSwitchRow(
    title: String,
    sub: String?,
    value: Boolean,
    switchLabel: String,
    openLabel: String,
    icon: ImageVector? = null,
    onOpen: () -> Unit,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ListItem(
            modifier = Modifier.weight(1f).clickable(onClickLabel = openLabel, onClick = onOpen),
            headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = sub?.let { { Text(it) } },
            leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
            colors = rowColors(),
        )
        VerticalDivider(Modifier.height(32.dp))
        Box(
            Modifier.heightIn(min = 56.dp)
                .toggleable(value, role = Role.Switch, onValueChange = onChange)
                .semantics { contentDescription = switchLabel }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Switch(value, onCheckedChange = null) }
    }
}
