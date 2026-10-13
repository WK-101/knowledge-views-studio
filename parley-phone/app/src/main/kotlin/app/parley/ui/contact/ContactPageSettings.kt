package app.parley.ui.contact

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.ContactPageLayout
import app.parley.common.people.ContactSection
import app.parley.common.people.SectionMode
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.SwitchRow
import app.parley.ui.ReorderMath
import app.parley.ui.Spacing
import app.parley.ui.rememberReorderState
import app.parley.ui.reorderActions
import app.parley.ui.reorderHandle
import app.parley.ui.reorderable

/**
 * Settings › Contacts › Contact page sections. Drag a section by its handle to reorder it; each one starts
 * open, folded or hidden (hiding never deletes anything). TalkBack gets "Move up" / "Move down" on each row.
 */
@Composable
fun ContactPageSettingsScreen(vm: AppViewModel, back: () -> Unit) {
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val layout = s.contactPage
    fun set(f: (ContactPageLayout) -> ContactPageLayout) = vm.people.update { it.copy(contactPage = f(it.contactPage)) }
    SettingsScaffold(stringResource(R.string.label_contact_page_sections), back, actions = {
        TextButton({ set { it.reset() } }, enabled = !layout.isDefault) { Text(stringResource(R.string.action_reset)) }
    }) {
        Text(
            stringResource(R.string.contact_page_settings_body), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        SegmentedGroup(stringResource(R.string.contact_page_sections)) {
            item("contact_page") { SectionsEditor(layout) { next -> set { next } } }
        }
        SegmentedGroup {
            item("contact_page_chips") {
                SwitchRow(stringResource(R.string.contact_page_chips_title), stringResource(R.string.contact_page_chips_summary), s.sectionChips, Icons.Rounded.SmartButton) { v ->
                    vm.people.update { it.copy(sectionChips = v) }
                }
            }
        }
    }
}

@Composable
private fun SectionsEditor(layout: ContactPageLayout, onChange: (ContactPageLayout) -> Unit) {
    val res = LocalResources.current
    // Local order while dragging, written back when the finger lifts.
    var order by remember { mutableStateOf(layout.order) }
    val reorder = rememberReorderState<ContactSection>()
    LaunchedEffect(layout.order) { if (reorder.lifted == null) order = layout.order }
    val latest by rememberUpdatedState(layout)
    val latestOnChange by rememberUpdatedState(onChange)
    val moveUp = stringResource(R.string.action_move_up)
    val moveDown = stringResource(R.string.action_move_down)
    fun moved(from: Int, to: Int) = latest.copy(order = order).moved(from, to)

    Column(Modifier.padding(vertical = Spacing.xs)) {
        order.forEachIndexed { i, sec ->
            key(sec) {
                val mode = layout.mode(sec)
                var menu by remember { mutableStateOf(false) }
                val modeText = modeLabel(res, mode)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .reorderable(reorder, sec, shape = MaterialTheme.shapes.medium)
                        .heightIn(min = 56.dp)
                        .clickable(onClickLabel = stringResource(R.string.contact_page_choose_start)) { menu = true }
                        .semantics { stateDescription = modeText }
                        .reorderActions(moveUp, moveDown, i, order.size) { to ->
                            // Shown moved at once; the saved order follows.
                            val next = moved(i, to)
                            order = next.order
                            onChange(next)
                        }
                        .padding(horizontal = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragHandle, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(48.dp)
                            .padding(Spacing.m)
                            .reorderHandle(
                                reorder, sec, index = { order.indexOf(sec) }, count = { order.size },
                                move = { from, to -> order = ReorderMath.moved(order, from, to) },
                                onDrop = { if (order != latest.order) latestOnChange(latest.copy(order = order)) },
                                onCancel = { order = latest.order },
                            ),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        sectionTitle(res, sec), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                        color = if (mode == SectionMode.HIDDEN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                    Box {
                        Row(Modifier.heightIn(min = 48.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(modeIcon(mode), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(modeText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp))
                            Icon(Icons.Rounded.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(menu, { menu = false }) {
                            SectionMode.entries.forEach { m ->
                                DropdownMenuItem(
                                    { Text(modeLabel(res, m)) }, leadingIcon = { Icon(modeIcon(m), null) },
                                    onClick = { menu = false; onChange(latest.withMode(sec, m)) },
                                )
                            }
                        }
                    }
                }
                if (i < order.size - 1) HorizontalDivider(Modifier.padding(start = 64.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

private fun modeLabel(res: Resources, m: SectionMode): String = res.getString(
    when (m) {
        SectionMode.OPEN -> R.string.action_open
        SectionMode.FOLDED -> R.string.label_folded
        SectionMode.HIDDEN -> R.string.label_hidden
    },
)

private fun modeIcon(m: SectionMode) = when (m) {
    SectionMode.OPEN -> Icons.Rounded.UnfoldMore
    SectionMode.FOLDED -> Icons.Rounded.UnfoldLess
    SectionMode.HIDDEN -> Icons.Rounded.VisibilityOff
}
