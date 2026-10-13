package app.parley.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The one floating button of a screen, the same everywhere: [Size] square with [CornerRadius] corners
 * ([ParleyShapes.fab]), the primary container colour, Material's elevation, at the bottom end [Margin] from the edge
 * and from the navigation bar (the Scaffold's own FAB place, or a pane's bottom end beside a detail pane). Only the
 * icon and the action change between screens; nothing hides it on scroll, and an extended label folds to the icon.
 *
 * The button never moves for what is around it: everything else anchored to the same edges gives way. Its area is
 * [Reserve] tall at the bottom of the list or pane; the A–Z index ends above it, a list under it ends with
 * [listPadding] so its last row scrolls clear, and a snackbar sits above it (the Scaffold does that for its own
 * button; [clearOfFab] for one placed by hand). The bars below (navigation bar, the Situation and return-to-call chips)
 * push the whole area up with them.
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

    /**
     * The button's area, measured up from the bottom of the list or pane it floats over: the button and a [Margin] above
     * and below. Room a row along the bottom leaves at its end, the A–Z index leaves under it and a list leaves after its
     * last row, so nothing ever sits under the button or competes with it for a touch.
     */
    val Reserve: Dp = Size + Margin * 2

    /** Bottom content padding for a list a floating button floats over ([extra] on top): its last row scrolls clear. */
    fun listPadding(extra: Dp = 0.dp): PaddingValues = PaddingValues(bottom = Reserve + extra)

    /** Between a menu's items, and between the last item and its button ([ParleyFabMenu]). */
    val MenuGap: Dp = Spacing.s

    /** A menu item's lift over the list below (lower than the button's own, so the button stays on top). */
    val MenuItemElevation: Dp = 3.dp

    /** A menu item's icon, the size of the button's. */
    val MenuIconSize: Dp = 24.dp
}

/**
 * Keeps an element anchored to the bottom (a snackbar, a hint) above the floating button's area when [fab] shows one
 * placed by hand (in a pane, over the docked keypad), where the Scaffold doesn't lift it.
 */
fun Modifier.clearOfFab(fab: Boolean = true): Modifier = if (fab) padding(bottom = ParleyFabDefaults.Reserve) else this

/** How every floating button comes and goes (a quick spring from small, the same for all of them). */
@Composable
private fun FabVisibility(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    // Scale on a spatial spring (it may overshoot a little); alpha on an effects spec, which never does.
    val grow = ParleyMotion.fastSpatial<Float>()
    val fade = ParleyMotion.fastEffects<Float>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(grow, initialScale = 0.4f) + fadeIn(fade),
        exit = scaleOut(grow, targetScale = 0.4f) + fadeOut(fade),
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
 * A floating button that opens a short menu of ways to do one thing (Contacts' "Add": New contact, Scan QR code…).
 * Parley's own, not Material's sample menu: the button keeps the size, [ParleyFabDefaults.CornerRadius] corner and
 * place of [ParleyFab] open or closed (its colour deepens and the icon turns into Close), and each item
 * ([ParleyFabMenuScope.Item]) is a [ParleyFabDefaults.Size]-tall tile with the same corner, on the surface colour the
 * app's cards use, its icon in the primary colour and its label in the extended button's type, all of them as wide as
 * the widest so icons and labels line up. The items rise from the button with the theme's fast springs
 * ([ParleyMotion]). TalkBack reads the button as a button with [stateDescription] ("menu closed"), and
 * [actionLabel] as what a double tap does. [wrap] puts something around the button itself (a coach mark's anchor).
 */
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
    items: @Composable ParleyFabMenuScope.() -> Unit,
) {
    FabVisibility(visible, modifier) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(ParleyFabDefaults.MenuGap)) {
            // The items grow out of the button's outer corner: bottom right, or bottom left when right to left.
            val origin = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TransformOrigin(0f, 1f) else TransformOrigin(1f, 1f)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(ParleyMotion.fastEffects()) + scaleIn(ParleyMotion.fastSpatial(), initialScale = MENU_FROM, transformOrigin = origin),
                exit = fadeOut(ParleyMotion.fastEffects()) + scaleOut(ParleyMotion.fastSpatial(), targetScale = MENU_FROM, transformOrigin = origin),
            ) {
                Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(ParleyFabDefaults.MenuGap)) {
                    ParleyFabMenuScope.items()
                }
            }
            wrap { FabMenuButton(expanded, onExpandedChange, icon, contentDescription, stateDescription, actionLabel) }
        }
    }
}

/** The menu's own button: [ParleyFab]'s size and corner, primary container closed and primary open. */
@Composable
private fun FabMenuButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    icon: ImageVector,
    contentDescription: String,
    stateDescription: String,
    actionLabel: String,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (expanded) colors.primary else colors.primaryContainer, ParleyMotion.fastEffects(), label = "fabMenu")
    val content by animateColorAsState(if (expanded) colors.onPrimary else colors.onPrimaryContainer, ParleyMotion.fastEffects(), label = "fabMenuIcon")
    FloatingActionButton(
        onClick = { onExpandedChange(!expanded) },
        // A button that opens a menu, not an on/off switch.
        modifier = Modifier.size(ParleyFabDefaults.Size).clearAndSetSemantics {
            this.contentDescription = contentDescription
            this.stateDescription = stateDescription
            role = Role.Button
            onClick(label = actionLabel) { onExpandedChange(!expanded); true }
        },
        shape = ParleyShapes.fab, containerColor = container, contentColor = content,
    ) {
        val fade = ParleyMotion.fastEffects<Float>()
        val scale = ParleyMotion.fastSpatial<Float>()
        AnimatedContent(
            expanded,
            transitionSpec = {
                (fadeIn(fade) + scaleIn(scale, initialScale = ICON_FROM)) togetherWith (fadeOut(fade) + scaleOut(scale, targetScale = ICON_FROM))
            },
            label = "fabMenuGlyph",
        ) { open -> Icon(if (open) Icons.Rounded.Close else icon, null) }
    }
}

/** What a [ParleyFabMenu] holds: its items, top to bottom. */
object ParleyFabMenuScope {
    /** One way in the menu: [icon] and [text] on a tile shaped like the button, as wide as the widest item. */
    @Composable
    fun Item(text: String, icon: ImageVector, onClick: () -> Unit) {
        val colors = MaterialTheme.colorScheme
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().heightIn(min = ParleyFabDefaults.Size).semantics { role = Role.Button },
            shape = ParleyShapes.fab,
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            shadowElevation = ParleyFabDefaults.MenuItemElevation,
        ) {
            Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(ParleyFabDefaults.MenuIconSize), tint = colors.primary)
                Text(text, Modifier.padding(start = Spacing.m), style = MaterialTheme.typography.labelLarge, maxLines = 2)
            }
        }
    }
}

/** How small the menu's items start when they rise from the button. */
private const val MENU_FROM = 0.85f

/** How small the button's icon starts as it turns into Close and back. */
private const val ICON_FROM = 0.6f
