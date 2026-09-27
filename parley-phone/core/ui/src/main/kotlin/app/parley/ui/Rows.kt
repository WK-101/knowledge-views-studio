package app.parley.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.parley.common.ListDensity

/** Rows on a segmented card's (or a dialog's) surface: their own container is transparent. */
@Composable
fun rowColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun RowIcon(icon: ImageVector?) {
    if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A setting that is on or off. The whole row is one switch for TalkBack (one focus stop, "switch, on"); the
 * [Switch] inside only shows the state.
 */
@Composable
fun SwitchRow(
    title: String,
    sub: String?,
    value: Boolean,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = { Switch(value, onCheckedChange = null, enabled = enabled) },
        colors = rowColors(),
    )
}

/** A row that opens a screen (chevron) or a system screen ([external]: "open in new" icon). */
@Composable
fun LinkRow(title: String, sub: String?, icon: ImageVector? = null, external: Boolean = false, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = {
            Icon(
                if (external) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = rowColors(),
    )
}

/** Text-only row (status, version). */
@Composable
fun InfoRow(title: String, sub: String?, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = trailing,
        colors = rowColors(),
    )
}

/** A choice between 2–3 short options as connected buttons under the title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceRow(title: String, options: List<String>, selected: Int, icon: ImageVector? = null, onPick: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = icon?.let { { RowIcon(it) } },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.padding(top = Spacing.s).fillMaxWidth()) {
                options.forEachIndexed { i, o ->
                    SegmentedButton(selected == i, { onPick(i) }, SegmentedButtonDefaults.itemShape(i, options.size)) {
                        Text(o, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        colors = rowColors(),
    )
}

/**
 * A choice from a longer list, shown as the current value; tap for a menu with the options as radio buttons.
 * [leading] takes any start content (a coloured dot) when an [icon] isn't enough.
 */
@Composable
fun MenuRow(
    title: String,
    options: List<String>,
    selected: Int,
    icon: ImageVector? = null,
    sub: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onPick: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = kitStrings().change) { open = true },
            headlineContent = { Text(title) },
            supportingContent = { Text(listOfNotNull(options.getOrElse(selected) { "" }, sub).joinToString(" · ")) },
            leadingContent = leading ?: icon?.let { { RowIcon(it) } },
            colors = rowColors(),
        )
        DropdownMenu(open, { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    { Text(o) },
                    leadingIcon = { RadioButton(i == selected, onClick = null) },
                    onClick = { open = false; onPick(i) },
                )
            }
        }
    }
}

/** Round tinted icon used by category lists. */
@Composable
fun TonalIcon(icon: ImageVector, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = content, modifier = Modifier.size(22.dp)) }
    }
}

/**
 * The title of a part of a list: A–Z letters, days in Recents, "Frequent", the sections of a contact's page.
 * TalkBack announces it as a heading, so its users can jump from one to the next. [sticky] headers get the list's
 * background so rows scroll under them; [inset] is the start padding (24 dp lines up with avatar rows' text
 * columns in the home lists); [trailing] holds a small action (a count, "Reorder").
 */
@Composable
fun ListSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    sticky: Boolean = false,
    inset: Dp = Spacing.listInset,
    top: Dp = Spacing.s,
    bottom: Dp = Spacing.xs,
    color: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val base = if (sticky) modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface) else modifier
    if (trailing == null) {
        Text(
            text, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = base.padding(start = inset, end = Spacing.l, top = top, bottom = bottom).semantics { heading() },
        )
    } else {
        Row(base.padding(start = inset, end = Spacing.s, top = top, bottom = bottom), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            trailing()
        }
    }
}

/**
 * Settings › Appearance › List density for rows of people and calls. Comfortable rows are Material's list rows;
 * compact ones lose [COMPACT_TRIM] above and below (the row's own padding shrinks from 8 to 4 dp; the text and
 * icons stay the same size). Put it first in the row's modifier chain.
 */
@Composable
fun Modifier.listDensity(): Modifier =
    if (LocalDensityPref.current != ListDensity.COMPACT) this
    else clipToBounds().layout { measurable, constraints ->
        val trim = COMPACT_TRIM.roundToPx()
        val placeable = measurable.measure(constraints)
        val height = (placeable.height - 2 * trim).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, -trim) }
    }

private val COMPACT_TRIM = 4.dp

/**
 * A list row for people and calls: Material's ListItem that follows the list density setting ([listDensity]).
 * Same parameters as ListItem.
 */
@Composable
fun ParleyListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
) {
    ListItem(
        headlineContent = headlineContent,
        modifier = Modifier.listDensity().then(modifier),
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = colors,
    )
}
