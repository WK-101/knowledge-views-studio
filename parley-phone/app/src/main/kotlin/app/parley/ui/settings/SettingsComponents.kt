package app.parley.ui.settings

import app.parley.ui.ExpandableHeader
import app.parley.ui.ExpandableBody
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import app.parley.common.SettingsCatalog
import app.parley.ui.Spacing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.LocalHighlightKey
import app.parley.ui.SegmentedGroup
import app.parley.ui.SegmentedGroupScope
import app.parley.ui.ChoiceRow
import app.parley.ui.LinkRow
import app.parley.ui.MenuRow
import app.parley.ui.SwitchRow

// Builder helpers: rows keyed by their catalog entry, so titles come from SettingsText (the localised
// SettingsCatalog) and search can find them.

fun SegmentedGroupScope.switchRow(key: String, value: Boolean, icon: ImageVector? = null, sub: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    item(key) { SwitchRow(settingTitle(key), sub ?: settingSummary(key), value, icon, enabled, onChange) }

fun SegmentedGroupScope.linkRow(key: String, icon: ImageVector? = null, sub: String? = null, external: Boolean = false, onClick: () -> Unit) =
    item(key) { LinkRow(settingTitle(key), sub ?: settingSummary(key), icon, external, onClick) }

fun SegmentedGroupScope.menuRow(key: String, options: List<String>, selected: Int, icon: ImageVector? = null, sub: String? = null, onPick: (Int) -> Unit) =
    item(key) { MenuRow(settingTitle(key), options, selected, icon, sub, onPick = onPick) }

fun SegmentedGroupScope.choiceRow(key: String, options: List<String>, selected: Int, icon: ImageVector? = null, onPick: (Int) -> Unit) =
    item(key) { ChoiceRow(settingTitle(key), options, selected, icon, onPick) }

/**
 * Rarely needed settings, folded under "Advanced" at the end of a page (one per page). The group opens by itself when
 * Settings search points at a setting the catalog marks advanced ([SettingsCatalog.ADVANCED]), or at one of [keys]
 * (rows that aren't catalog settings).
 */
@Composable
fun AdvancedGroup(keys: Set<String> = emptySet(), content: SegmentedGroupScope.() -> Unit) {
    AdvancedSection(keys) { SegmentedGroup(content = content) }
}

/** [AdvancedGroup] for groups that draw themselves (a page's own composable groups go inside as they are). */
@Composable
fun AdvancedSection(keys: Set<String> = emptySet(), content: @Composable () -> Unit) {
    val highlight = LocalHighlightKey.current
    var open by rememberSaveable { mutableStateOf(SettingsCatalog.isAdvanced(highlight) || (highlight != null && highlight in keys)) }
    Column {
        ExpandableHeader(
            stringResource(R.string.label_advanced), open, { open = !open },
            actionLabel = stringResource(if (open) R.string.set_advanced_hide else R.string.set_advanced_show),
        )
        ExpandableBody(open) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.groupGap)) { content() }
        }
    }
}
