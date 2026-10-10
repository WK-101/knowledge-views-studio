package app.parley.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import app.parley.common.SettingsCatalog
import app.parley.ui.Spacing
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = stringResource(if (open) R.string.set_advanced_hide else R.string.set_advanced_show)) { open = !open }
                .padding(horizontal = 32.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.blk_advanced),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.groupGap)) { content() }
        }
    }
}
