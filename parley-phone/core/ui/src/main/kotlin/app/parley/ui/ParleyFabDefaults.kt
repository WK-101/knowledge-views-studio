package app.parley.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp

/**
 * The one floating button of a screen, the same everywhere: [Size] square with [CornerRadius] corners
 * ([ParleyShapes.fab]), the primary container colour, Material's elevation, at the bottom end [Margin] from the edge
 * and from the navigation bar (the Scaffold's own FAB place, or a pane's bottom end beside a detail pane). Only the
 * icon and the action change between screens; nothing hides it on scroll, and an extended label folds to the icon.
 */
object ParleyFabDefaults {
    /** Material's standard FAB. */
    val Size: Dp = 56.dp

    /**
     * The corner every floating button has. Set here, not taken from the theme: Material's plain FAB reads the theme's
     * large shape (24 dp in Parley) while its menu button is 16 dp, which made the two look different side by side.
     */
    val CornerRadius: Dp = 16.dp

    /** From the end edge and from the bar or pane bottom below, as Scaffold places its floating button. */
    val Margin: Dp = Spacing.l

    /** Room a row along the bottom leaves at its end so the button never covers it. */
    val Reserve: Dp = Size + Margin * 2
}

/** How every floating button comes and goes (a quick spring from small, the same for all of them). */
@Composable
private fun FabVisibility(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val spring = ParleyMotion.fastSpatial<Float>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(spring, initialScale = 0.4f) + fadeIn(spring),
        exit = scaleOut(spring, targetScale = 0.4f) + fadeOut(spring),
    ) { content() }
}

/**
 * A screen's floating button. [contentDescription] is what TalkBack says; with [text] it is an extended button whose
 * label shows while [expanded] (fold it once the list scrolls) and the icon alone otherwise, at the same height and
 * corner. [badge]: a short text on the icon (the docked keypad's typed digits). Place it with the Scaffold's
 * `floatingActionButton` slot, or at `Alignment.BottomEnd` with [ParleyFabDefaults.Margin] inside a pane.
 */
@Composable
fun ParleyFab(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    text: String? = null,
    expanded: Boolean = text != null,
    badge: String? = null,
) {
    FabVisibility(visible, modifier) {
        val colors = MaterialTheme.colorScheme
        val glyph: @Composable () -> Unit = {
            val iconLabel = if (text != null && expanded) null else contentDescription
            if (badge != null) {
                BadgedBox(badge = { Badge(containerColor = colors.primary, contentColor = colors.onPrimary) { ForceLtr { Text(badge, maxLines = 1) } } }) {
                    Icon(icon, iconLabel)
                }
            } else {
                Icon(icon, iconLabel)
            }
        }
        if (text != null) {
            ExtendedFloatingActionButton(
                text = { Text(text) }, icon = glyph, onClick = onClick, expanded = expanded,
                shape = ParleyShapes.fab, containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
            )
        } else {
            FloatingActionButton(
                onClick, Modifier.size(ParleyFabDefaults.Size),
                shape = ParleyShapes.fab, containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
            ) { glyph() }
        }
    }
}

/**
 * A floating button that opens a short menu of ways to do one thing (Contacts' "Add": New contact, Scan QR code…):
 * Material's expressive FAB menu, with the closed button the same size, corner and place as [ParleyFab]. TalkBack
 * reads it as a button with [stateDescription] ("menu closed"), and [actionLabel] as what a double tap does.
 * [wrap] puts something around the button itself (a coach mark's anchor).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ParleyFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    icon: ImageVector,
    contentDescription: String,
    stateDescription: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    wrap: @Composable (@Composable () -> Unit) -> Unit = { it() },
    items: @Composable FloatingActionButtonMenuScope.() -> Unit,
) {
    FabVisibility(visible, modifier) {
        FloatingActionButtonMenu(
            expanded = expanded,
            button = {
                wrap {
                    ToggleFloatingActionButton(
                        checked = expanded, onCheckedChange = onExpandedChange,
                        // A button that opens a menu, not an on/off switch.
                        modifier = Modifier.clearAndSetSemantics {
                            this.contentDescription = contentDescription
                            this.stateDescription = stateDescription
                            role = Role.Button
                            onClick(label = actionLabel) { onExpandedChange(!expanded); true }
                        },
                        containerColor = ToggleFloatingActionButtonDefaults.containerColor(
                            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary,
                        ),
                        containerSize = { ParleyFabDefaults.Size },
                        // Closed: the corner of every floating button; open: round, as Material's menu has it.
                        containerCornerRadius = { p -> lerp(ParleyFabDefaults.CornerRadius, ParleyFabDefaults.Size / 2, p) },
                    ) {
                        Icon(if (checkedProgress > 0.5f) Icons.Rounded.Close else icon, null)
                    }
                }
            },
            // The menu pads its button by Margin at the end and below; undone so the closed button sits where a
            // ParleyFab does (offset follows the layout direction, so right to left too).
            modifier = Modifier.offset(x = ParleyFabDefaults.Margin, y = ParleyFabDefaults.Margin),
        ) { items() }
    }
}
