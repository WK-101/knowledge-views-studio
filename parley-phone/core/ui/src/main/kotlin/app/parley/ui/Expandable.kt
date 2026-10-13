// The expandable parts and the style enum they share live together.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** How an [ExpandableHeader] looks: a section title in a page, or a list row that opens in place. */
enum class ExpandableStyle {
    /** A section title ("Advanced", a contact page's folded section): small primary text, a summary beside it. */
    Section,

    /** A row of a list or card (a Blocking section, a label's settings, an import source): icon, title, summary. */
    Row,
}

/**
 * The one open/closed chevron: it points down while closed and turns half a turn as it opens, on the theme's fast
 * spatial spring (still when animations are off). Decorative: the header it sits in says the state.
 */
@Composable
fun ExpandChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    val turn by animateFloatAsState(if (expanded) HALF_TURN else 0f, ParleyMotion.fastSpatial(), label = "chevron")
    Icon(Icons.Rounded.ExpandMore, contentDescription, modifier.graphicsLayer { rotationZ = turn }, tint = tint)
}

/**
 * The header of a part that opens in place. TalkBack hears one stop: the title (and summary), "heading", its state
 * ("Expanded"/"Collapsed") and what a double tap does ([actionLabel], by default "Unfold"/"Fold"), so heading
 * navigation jumps between such parts like between any other section.
 *
 * [summary] says what's inside while it's closed ("124 entries"); a [Row] can carry richer [supporting] content
 * instead (chips). [colors] are the row's (transparent inside a [SegmentedGroup] or a card).
 */
@Composable
fun ExpandableHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    style: ExpandableStyle = ExpandableStyle.Section,
    actionLabel: String? = null,
    supporting: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
) {
    val words = kitStrings()
    val state = if (expanded) words.expanded else words.collapsed
    val action = actionLabel ?: if (expanded) words.fold else words.unfold
    val toggle = Modifier
        .clickable(role = Role.Button, onClickLabel = action, onClick = onToggle)
        .semantics(mergeDescendants = true) {
            heading()
            stateDescription = state
        }
    when (style) {
        ExpandableStyle.Row -> ParleyListItem(
            modifier = modifier.then(toggle),
            leadingContent = icon?.let { { Icon(it, null, tint = MaterialTheme.colorScheme.primary) } },
            headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
            supportingContent = supporting ?: summary?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            trailingContent = { ExpandChevron(expanded) },
            colors = colors,
        )
        ExpandableStyle.Section -> Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l)
                .clip(ParleyShapes.tile)
                .then(toggle)
                .heightIn(min = 48.dp)
                .padding(start = Spacing.l, end = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, null, Modifier.padding(end = Spacing.m), tint = MaterialTheme.colorScheme.primary) }
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (!expanded && !summary.isNullOrBlank()) SEPARATOR + summary else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            ExpandChevron(expanded, Modifier.padding(Spacing.s))
        }
    }
}

/** What an [ExpandableHeader] opens: it grows and fades in, and shrinks away, on the theme's springs. */
@Composable
fun ExpandableBody(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(expanded, modifier, enter = ParleyMotion.expandIn(), exit = ParleyMotion.collapseOut()) {
        Column(content = content)
    }
}

private const val HALF_TURN = 180f

// The kit's list separator, the one the app's "main_separator" spells out.
private const val SEPARATOR = " · "
