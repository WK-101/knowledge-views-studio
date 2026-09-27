package app.parley.ui.contact

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.people.ContactPageLayout
import app.parley.common.people.ContactSection
import app.parley.common.people.SectionMode
import app.parley.ui.SegmentedGroup
import app.parley.ui.settings.SettingsScaffold
import app.parley.ui.settings.SwitchRow

/** The contact page's own screens. */
object ContactPageRoutes {
    const val TIMELINE = "contacttimeline/{id}"
    fun timeline(id: Long) = "contacttimeline/$id"
    const val SECTIONS = "contactpagesections"
}

fun NavGraphBuilder.contactPageRoutes(vm: AppViewModel, nav: NavController) {
    composable(ContactPageRoutes.TIMELINE, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
        ContactTimelineScreen(vm, it.arguments!!.getLong("id"), back = { nav.popBackStack() })
    }
    composable(ContactPageRoutes.SECTIONS) { ContactPageSettingsScreen(vm, back = { nav.popBackStack() }) }
}

/**
 * Settings › Contacts › Contact page sections. Drag a section by its handle to reorder it; each one starts
 * open, folded or hidden (hiding never deletes anything). TalkBack gets "Move up" / "Move down" on each row.
 */
@Composable
fun ContactPageSettingsScreen(vm: AppViewModel, back: () -> Unit) {
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val layout = s.contactPage
    fun set(f: (ContactPageLayout) -> ContactPageLayout) = vm.people.update { it.copy(contactPage = f(it.contactPage)) }
    SettingsScaffold(stringResource(R.string.contact_page_settings_title), back, actions = {
        TextButton({ set { it.reset() } }, enabled = !layout.isDefault) { Text(stringResource(R.string.contact_page_reset)) }
    }) {
        Text(
            stringResource(R.string.contact_page_settings_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val haptics = LocalHapticFeedback.current
    // Local order while dragging, written back when the finger lifts.
    var order by remember { mutableStateOf(layout.order) }
    var dragging by remember { mutableStateOf<ContactSection?>(null) }
    LaunchedEffect(layout.order) { if (dragging == null) order = layout.order }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val latest by rememberUpdatedState(layout)
    val latestOnChange by rememberUpdatedState(onChange)
    val moveUp = stringResource(R.string.set_move_up)
    val moveDown = stringResource(R.string.set_move_down)
    fun moved(from: Int, to: Int) = latest.copy(order = order).moved(from, to)

    Column(Modifier.padding(vertical = 4.dp)) {
        order.forEachIndexed { i, sec ->
            key(sec) {
                val lifted = dragging == sec
                val mode = layout.mode(sec)
                var menu by remember { mutableStateOf(false) }
                val modeText = modeLabel(res, mode)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) dragOffset else 0f }
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                        .onSizeChanged { rowHeight = it.height }
                        .heightIn(min = 56.dp)
                        .clickable(onClickLabel = stringResource(R.string.contact_page_choose_start)) { menu = true }
                        .semantics {
                            stateDescription = modeText
                            customActions = listOfNotNull(
                                if (i > 0) CustomAccessibilityAction(moveUp) { onChange(moved(i, i - 1)); true } else null,
                                if (i < order.size - 1) CustomAccessibilityAction(moveDown) { onChange(moved(i, i + 1)); true } else null,
                            )
                        }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragHandle, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(48.dp)
                            .padding(12.dp)
                            .pointerInput(Unit) { detectTapGestures { } }
                            .pointerInput(sec) {
                                detectVerticalDragGestures(
                                    onDragStart = { dragging = sec; dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    onDragEnd = {
                                        dragging = null
                                        dragOffset = 0f
                                        if (order != latest.order) latestOnChange(latest.copy(order = order))
                                    },
                                    onDragCancel = { dragging = null; dragOffset = 0f; order = latest.order },
                                ) { change, dy ->
                                    change.consume()
                                    dragOffset += dy
                                    val from = order.indexOf(sec)
                                    val step = rowHeight.toFloat()
                                    if (dragOffset > step / 2 && from < order.size - 1) {
                                        order = order.toMutableList().apply { add(from + 1, removeAt(from)) }
                                        dragOffset -= step
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    } else if (dragOffset < -step / 2 && from > 0) {
                                        order = order.toMutableList().apply { add(from - 1, removeAt(from)) }
                                        dragOffset += step
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    }
                                }
                            },
                    )
                    Spacer(Modifier.width(4.dp))
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

private fun modeLabel(res: android.content.res.Resources, m: SectionMode): String = res.getString(
    when (m) {
        SectionMode.OPEN -> R.string.contact_page_mode_open
        SectionMode.FOLDED -> R.string.contact_page_mode_folded
        SectionMode.HIDDEN -> R.string.contact_page_mode_hidden
    },
)

private fun modeIcon(m: SectionMode) = when (m) {
    SectionMode.OPEN -> Icons.Rounded.UnfoldMore
    SectionMode.FOLDED -> Icons.Rounded.UnfoldLess
    SectionMode.HIDDEN -> Icons.Rounded.VisibilityOff
}
