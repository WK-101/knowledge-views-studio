// The menu helpers and the label type they share live together.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.parley.R
import app.parley.common.ux.MenuEntry
import app.parley.common.ux.MenuGroup
import app.parley.ui.ParleyListItem
import app.parley.ui.ParleySheet

/** What a menu shows for one action: its words and icon. */
data class MenuLabel(val text: String, val icon: ImageVector)

/** The words of a [MenuGroup]'s entry ("Share…") and the title of its sheet. */
@Composable
fun MenuGroup.title(): String = stringResource(
    when (this) {
        MenuGroup.SHARE -> R.string.menu_group_share
        MenuGroup.PRIVACY -> R.string.menu_group_privacy
        MenuGroup.MORE -> R.string.menu_group_more
        MenuGroup.WHY_IT_RANG -> R.string.menu_group_why
    },
)

private val MenuGroup.icon: ImageVector
    get() = when (this) {
        MenuGroup.SHARE -> Icons.Rounded.Share
        MenuGroup.PRIVACY -> Icons.Rounded.Shield
        MenuGroup.MORE -> Icons.Rounded.MoreHoriz
        MenuGroup.WHY_IT_RANG -> Icons.AutoMirrored.Rounded.HelpOutline
    }

/**
 * The items of a ⋮ menu built by one of [app.parley.common.ux.ContactMenu]'s siblings: an action runs ([onAction]),
 * a group opens its sheet ([onGroup]). Call inside a DropdownMenu; the menu closes before either.
 */
@Composable
fun <A> MenuItems(
    entries: List<MenuEntry<A>>,
    label: @Composable (A) -> MenuLabel,
    close: () -> Unit,
    onGroup: (MenuEntry.Group<A>) -> Unit,
    onAction: (A) -> Unit,
) {
    entries.forEach { e ->
        val l = e.label(label)
        DropdownMenuItem({ Text(l.text) }, leadingIcon = { Icon(l.icon, null) }, onClick = {
            close()
            when (e) {
                is MenuEntry.Action -> onAction(e.action)
                is MenuEntry.Group -> onGroup(e)
            }
        })
    }
}

/** The same entries as rows of a sheet (a Recents call's actions). */
@Composable
fun <A> MenuRows(entries: List<MenuEntry<A>>, label: @Composable (A) -> MenuLabel, onGroup: (MenuEntry.Group<A>) -> Unit, onAction: (A) -> Unit) {
    entries.forEach { e ->
        SheetRow(e.label(label)) {
            when (e) {
                is MenuEntry.Action -> onAction(e.action)
                is MenuEntry.Group -> onGroup(e)
            }
        }
    }
}

/** A group's own sheet: its actions as rows; a tap closes the sheet and runs the action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <A> MenuGroupSheet(group: MenuEntry.Group<A>, label: @Composable (A) -> MenuLabel, onDismiss: () -> Unit, onAction: (A) -> Unit) {
    ParleySheet(onDismissRequest = onDismiss, title = group.group.title().removeSuffix("…")) {
        group.actions.forEach { a ->
            SheetRow(label(a)) {
                onDismiss()
                onAction(a)
            }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

/** An entry's words and icon: its action's, or its group's ("Share…"). */
@Composable
private fun <A> MenuEntry<A>.label(label: @Composable (A) -> MenuLabel): MenuLabel = when (this) {
    is MenuEntry.Action -> label(action)
    is MenuEntry.Group -> MenuLabel(group.title(), group.icon)
}

@Composable
private fun SheetRow(l: MenuLabel, onClick: () -> Unit) {
    ParleyListItem(headlineContent = { Text(l.text) }, leadingContent = { Icon(l.icon, null) }, modifier = Modifier.clickable(onClick = onClick))
}
