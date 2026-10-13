package app.parley.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag

/**
 * The ⋮ button alone, for a ⋮ that opens something other than a drop-down (a sheet, a dialog). Its spoken name is
 * [label]: "More options" in a top bar (the default, the same on every screen), "More for Ana" on a row.
 */
@Composable
fun OverflowButton(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = kitStrings().moreOptions, enabled: Boolean = true) {
    IconButton(onClick, modifier, enabled = enabled) { Icon(Icons.Rounded.MoreVert, label) }
}

/**
 * A ⋮ and its drop-down. [content] gets `close` to call before an item acts. In a top bar leave [label] as it is
 * ("More options"); a row's menu names the row ("More for Ana").
 */
@Composable
fun OverflowMenu(
    modifier: Modifier = Modifier,
    label: String = kitStrings().moreOptions,
    enabled: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    OverflowMenu(open, { open = it }, modifier, label, enabled, content)
}

/** [OverflowMenu] whose open state the screen keeps ([expanded]), for a menu something else can open or close too. */
@Composable
fun OverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String = kitStrings().moreOptions,
    enabled: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    Box(modifier) {
        OverflowButton({ onExpandedChange(true) }, label = label, enabled = enabled)
        DropdownMenu(expanded, onDismissRequest = { onExpandedChange(false) }) { content { onExpandedChange(false) } }
    }
}

/**
 * One item of a drop-down: its words and, if it has one, its icon. A [destructive] item (Delete, Clear call history)
 * comes last in its menu: it is set apart by a line above it and drawn in the error colour, on top of the question
 * or Undo that already guards it.
 */
@Composable
fun ParleyMenuItem(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    if (destructive) DestructiveDivider()
    val colors = if (destructive) {
        val error = MaterialTheme.colorScheme.error
        MenuDefaults.itemColors(textColor = error, leadingIconColor = error)
    } else MenuDefaults.itemColors()
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = icon?.let { { Icon(it, null) } },
        enabled = enabled,
        colors = colors,
    )
}

/** The line that sets a destructive item apart from the ones above it, in a drop-down or a sheet of rows. */
@Composable
fun DestructiveDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.testTag(DESTRUCTIVE_DIVIDER_TAG), color = MaterialTheme.colorScheme.outlineVariant)
}

/** The test tag of [DestructiveDivider], so tests can check a destructive item is set apart. */
const val DESTRUCTIVE_DIVIDER_TAG = "destructive-divider"
