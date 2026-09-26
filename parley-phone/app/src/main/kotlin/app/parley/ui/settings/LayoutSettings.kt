package app.parley.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.AppSettings
import app.parley.common.CallsLayout
import app.parley.common.FavoritesPlacement
import app.parley.common.RecentTap
import app.parley.common.StartTab
import app.parley.ui.SegmentedGroup
import kotlinx.coroutines.launch

/**
 * S1/S2 (v3.3): Settings › Appearance › Layout. Both combine options with small previews, the question whether to
 * keep the absorbed tab too (never removed silently), "Back to separate tabs", and the Recents row tap (shown in
 * every layout, unlike iOS's setting that only appears in its combined view).
 */
@Composable
internal fun LayoutSettingsGroup(vm: AppViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun set(f: (AppSettings) -> AppSettings) { scope.launch { vm.c.settings.update(f) } }
    val surfaces = s.surfaces
    // The tab a combine option would take out of the bar: asked about first, only when the user shows it.
    var askKeypad by remember { mutableStateOf(false) }
    var askFavorites by remember { mutableStateOf<FavoritesPlacement?>(null) }
    val tapOptions = listOf(stringResource(R.string.surf_tap_details), stringResource(R.string.surf_tap_call))

    SegmentedGroup(stringResource(R.string.surf_group_layout)) {
        item("calls_layout") {
            Column {
                ListItem(
                    headlineContent = { Text(settingTitle("calls_layout")) },
                    supportingContent = { Text(settingSummary("calls_layout")) },
                    leadingContent = { Icon(Icons.Rounded.Dialpad, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = rowColors(),
                )
                PreviewChoices(
                    listOf(
                        Triple(stringResource(R.string.surf_calls_separate), Thumb.CALLS_SEPARATE, surfaces.calls == CallsLayout.SEPARATE),
                        Triple(stringResource(R.string.surf_calls_combined), Thumb.CALLS_COMBINED, surfaces.calls == CallsLayout.COMBINED),
                    ),
                ) { i ->
                    when {
                        i == 0 -> set { it.copy(surfaces = it.surfaces.copy(calls = CallsLayout.SEPARATE)) }
                        surfaces.calls == CallsLayout.COMBINED -> Unit
                        s.navTabs.isVisible(StartTab.KEYPAD) -> askKeypad = true
                        else -> set { it.copy(surfaces = it.surfaces.copy(calls = CallsLayout.COMBINED)) }
                    }
                }
            }
        }
        if (surfaces.calls == CallsLayout.COMBINED) {
            item("keep_keypad_tab") {
                SwitchRow(stringResource(R.string.surf_keep_keypad_tab), stringResource(R.string.surf_keep_keypad_tab_sub), surfaces.keepKeypadTab) { v ->
                    set { it.copy(surfaces = it.surfaces.copy(keepKeypadTab = v)) }
                }
            }
        }
        item("favorites_in_contacts") {
            Column {
                ListItem(
                    headlineContent = { Text(settingTitle("favorites_in_contacts")) },
                    supportingContent = { Text(settingSummary("favorites_in_contacts")) },
                    leadingContent = { Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = rowColors(),
                )
                PreviewChoices(
                    listOf(
                        Triple(stringResource(R.string.surf_fav_off), Thumb.FAV_OFF, surfaces.favorites == FavoritesPlacement.OFF),
                        Triple(stringResource(R.string.surf_fav_section), Thumb.FAV_SECTION, surfaces.favorites == FavoritesPlacement.SECTION),
                        Triple(stringResource(R.string.surf_fav_strip), Thumb.FAV_STRIP, surfaces.favorites == FavoritesPlacement.STRIP),
                    ),
                ) { i ->
                    val next = FavoritesPlacement.entries[i]
                    when {
                        next == surfaces.favorites -> Unit
                        // Switching between section and strip, or off: nothing leaves the bar.
                        next == FavoritesPlacement.OFF || surfaces.favorites != FavoritesPlacement.OFF || !s.navTabs.isVisible(StartTab.FAVORITES) ->
                            set { it.copy(surfaces = it.surfaces.copy(favorites = next)) }
                        else -> askFavorites = next
                    }
                }
            }
        }
        if (surfaces.favorites != FavoritesPlacement.OFF) {
            item("keep_favorites_tab") {
                SwitchRow(stringResource(R.string.surf_keep_fav_tab), stringResource(R.string.surf_keep_fav_tab_sub), surfaces.keepFavoritesTab) { v ->
                    set { it.copy(surfaces = it.surfaces.copy(keepFavoritesTab = v)) }
                }
            }
            item("frequents_row") {
                SwitchRow(stringResource(R.string.surf_frequents_row), stringResource(R.string.surf_frequents_row_sub), surfaces.frequentsRow) { v ->
                    set { it.copy(surfaces = it.surfaces.copy(frequentsRow = v)) }
                }
            }
            if (s.homeLayout().circleHost == StartTab.CONTACTS) item("circle_moves") {
                InfoRow(stringResource(R.string.surf_circle_moves), null)
            }
        }
        if (surfaces.merged) {
            item("layout_back") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.surf_back_to_separate)) },
                    supportingContent = { Text(stringResource(R.string.surf_back_to_separate_sub)) },
                    leadingContent = { Icon(Icons.Rounded.ViewAgenda, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingContent = { TextButton({ set { it.copy(surfaces = it.surfaces.separated()) } }) { Text(stringResource(R.string.surf_back_action)) } },
                    colors = rowColors(),
                )
            }
        }
        choiceRow(
            "recent_tap",
            tapOptions,
            surfaces.recentTap.ordinal, Icons.Rounded.TouchApp,
        ) { i -> set { it.copy(surfaces = it.surfaces.copy(recentTap = RecentTap.entries[i])) } }
    }

    if (askKeypad) {
        KeepTabDialog(
            title = stringResource(R.string.surf_ask_keypad_title),
            body = stringResource(R.string.surf_ask_keypad_body),
            onDismiss = { askKeypad = false },
        ) { keep ->
            askKeypad = false
            set { it.copy(surfaces = it.surfaces.copy(calls = CallsLayout.COMBINED, keepKeypadTab = keep)) }
        }
    }
    askFavorites?.let { next ->
        KeepTabDialog(
            title = stringResource(R.string.surf_ask_fav_title),
            body = stringResource(R.string.surf_ask_fav_body),
            onDismiss = { askFavorites = null },
        ) { keep ->
            askFavorites = null
            set { it.copy(surfaces = it.surfaces.copy(favorites = next, keepFavoritesTab = keep)) }
        }
    }
}

private fun AppSettings.homeLayout() = app.parley.common.HomeLayout(navTabs, surfaces)

/** Asks whether the absorbed tab also stays in the bar; dismissing changes nothing. */
@Composable
private fun KeepTabDialog(title: String, body: String, onDismiss: () -> Unit, onPick: (keep: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton({ onPick(false) }) { Text(stringResource(R.string.surf_ask_hide_tab)) } },
        dismissButton = { TextButton({ onPick(true) }) { Text(stringResource(R.string.surf_ask_keep_tab)) } },
    )
}

private enum class Thumb { CALLS_SEPARATE, CALLS_COMBINED, FAV_OFF, FAV_SECTION, FAV_STRIP }

/** Choices as small phone previews with a label; one is selected (radio semantics for TalkBack). */
@Composable
private fun PreviewChoices(options: List<Triple<String, Thumb, Boolean>>, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        options.forEachIndexed { i, (label, thumb, selected) ->
            val cs = MaterialTheme.colorScheme
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant), RoundedCornerShape(16.dp))
                    .selectable(selected, role = Role.RadioButton) { onPick(i) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LayoutThumb(thumb, selected)
                Text(
                    label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    color = if (selected) cs.primary else cs.onSurface, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** A simple drawn mock of the home screen for an option (decorative: the label says what it is). */
@Composable
private fun LayoutThumb(thumb: Thumb, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val frame = cs.surfaceContainerHighest
    val ink = cs.onSurfaceVariant.copy(alpha = 0.45f)
    val accent = if (selected) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.7f)
    val panel = cs.secondaryContainer
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.size(width = 56.dp, height = 96.dp).graphicsLayer { if (rtl) scaleX = -1f }) {
        val w = size.width
        val h = size.height
        val u = w / 14f
        drawRoundRect(frame, cornerRadius = CornerRadius(3 * u))
        val barTop = h - 3 * u
        fun bar(n: Int) {
            val step = w / (n + 1)
            repeat(n) { k -> drawCircle(if (k == 0) accent else ink, radius = 0.8f * u, center = Offset(step * (k + 1), barTop + 1.5f * u)) }
        }
        fun rows(from: Float, to: Float) {
            var y = from
            while (y + 2 * u <= to) {
                drawCircle(ink, radius = 0.9f * u, center = Offset(2.5f * u, y + u))
                drawRoundRect(ink, Offset(4.5f * u, y + 0.6f * u), Size(w - 7 * u, 0.8f * u), CornerRadius(0.4f * u))
                y += 2.6f * u
            }
        }
        val top = 1.5f * u
        when (thumb) {
            Thumb.CALLS_SEPARATE -> { rows(top, barTop); bar(4) }
            Thumb.CALLS_COMBINED -> {
                val panelTop = h * 0.45f
                rows(top, panelTop)
                drawRoundRect(panel, Offset(0f, panelTop), Size(w, barTop - panelTop), CornerRadius(2 * u))
                keypad(panelTop + 1.2f * u, barTop - 0.8f * u, accent, ink)
                bar(3)
            }
            Thumb.FAV_OFF -> { rows(top, barTop); bar(4) }
            Thumb.FAV_SECTION -> {
                drawRoundRect(accent, Offset(1.5f * u, top), Size(4 * u, 0.7f * u), CornerRadius(0.35f * u))
                val tileTop = top + 1.8f * u
                repeat(2) { r -> repeat(3) { c -> drawCircle(accent, radius = 1.3f * u, center = Offset(w * (c + 1) / 4f, tileTop + 1.4f * u + r * 3.2f * u)) } }
                rows(tileTop + 6.8f * u, barTop)
                bar(3)
            }
            Thumb.FAV_STRIP -> {
                repeat(4) { c -> drawCircle(accent, radius = 1.2f * u, center = Offset(2.5f * u + c * 3.1f * u, top + 1.6f * u)) }
                rows(top + 4.2f * u, barTop)
                bar(3)
            }
        }
    }
}

/** The keypad in a thumbnail: 3 × 4 dots and a call button. */
private fun DrawScope.keypad(top: Float, bottom: Float, accent: Color, ink: Color) {
    val w = size.width
    val rowsH = (bottom - top) / 5f
    repeat(4) { r -> repeat(3) { c -> drawCircle(ink, radius = rowsH * 0.28f, center = Offset(w * (c + 1) / 4f, top + rowsH * (r + 0.5f))) } }
    drawCircle(accent, radius = rowsH * 0.38f, center = Offset(w / 2f, top + rowsH * 4.5f))
}
