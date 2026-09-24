package app.parley.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.parley.common.SettingEntry
import app.parley.common.SettingsCatalog
import app.parley.ui.SegmentedGroupScope

/** Rows sit on the segmented card's surface, so their own container is transparent. */
@Composable
internal fun rowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun RowIcon(icon: ImageVector?) {
    if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SwitchRow(title: String, sub: String?, value: Boolean, icon: ImageVector? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
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
            SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp).fillMaxWidth()) {
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

/** A choice from a longer list, shown as the current value; tap for a menu. */
@Composable
fun MenuRow(title: String, options: List<String>, selected: Int, icon: ImageVector? = null, sub: String? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Change") { open = true },
            headlineContent = { Text(title) },
            supportingContent = { Text(listOfNotNull(options.getOrElse(selected) { "" }, sub).joinToString(" · ")) },
            leadingContent = icon?.let { { RowIcon(it) } },
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

// Builder helpers: rows keyed by their catalog entry, so titles come from SettingsCatalog and search can find them.

internal fun entry(key: String): SettingEntry = SettingsCatalog[key]

fun SegmentedGroupScope.switchRow(key: String, value: Boolean, icon: ImageVector? = null, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    item(key) { SwitchRow(entry(key).title, sub ?: entry(key).summary, value, icon, enabled, onChange) }

fun SegmentedGroupScope.linkRow(key: String, icon: ImageVector? = null, sub: String? = null, external: Boolean = false, onClick: () -> Unit) =
    item(key) { LinkRow(entry(key).title, sub ?: entry(key).summary, icon, external, onClick) }

fun SegmentedGroupScope.menuRow(key: String, options: List<String>, selected: Int, icon: ImageVector? = null, sub: String? = null, onPick: (Int) -> Unit) =
    item(key) { MenuRow(entry(key).title, options, selected, icon, sub, onPick) }

fun SegmentedGroupScope.choiceRow(key: String, options: List<String>, selected: Int, icon: ImageVector? = null, onPick: (Int) -> Unit) =
    item(key) { ChoiceRow(entry(key).title, options, selected, icon, onPick) }

/**
 * A settings screen: large title that collapses as you scroll (with the scroll-linked tint), grouped content
 * with 12 dp between groups, and room for the navigation bar at the end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    back: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { actions() },
                scrollBehavior = scroll,
            )
        },
    ) { p ->
        val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
        Column(
            Modifier.fillMaxSize()
                .padding(top = p.calculateTopPadding(), start = p.calculateStartPadding(dir), end = p.calculateEndPadding(dir))
                .verticalScroll(rememberScrollState())
                // Scrolls behind the navigation bar, and the last row can still scroll above it.
                .padding(bottom = p.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(0.dp))
            content()
        }
    }
}

/** Round tinted icon used by the category list. */
@Composable
fun TonalIcon(icon: ImageVector, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = content, modifier = Modifier.size(22.dp)) }
    }
}
