package app.parley.ui.people

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.SwipeLeft
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.common.people.AvatarStyle
import app.parley.common.people.SwipeAction
import app.parley.ui.Avatar
import app.parley.ui.LocalAvatarStyle
import app.parley.ui.settings.MenuRow
import app.parley.ui.settings.SwitchRow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.parley.R

/** U4: Settings › Appearance › Swipe actions, with a live preview row to try them on. */
@Composable
fun SwipeSettings(vm: AppViewModel) {
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val choices = SwipeAction.entries
    var tried by remember { mutableStateOf<String?>(null) }
    val res = androidx.compose.ui.platform.LocalResources.current
    Column {
        SwitchRow(app.parley.ui.settings.settingTitle("swipe_actions"), app.parley.ui.settings.settingSummary("swipe_actions"), s.swipe.enabled, Icons.Rounded.Swipe) { v -> vm.people.update { it.copy(swipe = it.swipe.copy(enabled = v)) } }
        if (s.swipe.enabled) {
            MenuRow(stringResource(R.string.swipe_right), choices.map { swipeLabel(res, it) }, choices.indexOf(s.swipe.right), Icons.Rounded.SwipeRight) { i ->
                vm.people.update { it.copy(swipe = it.swipe.copy(right = choices[i])) }
            }
            MenuRow(stringResource(R.string.swipe_left), choices.map { swipeLabel(res, it) }, choices.indexOf(s.swipe.left), Icons.Rounded.SwipeLeft) { i ->
                vm.people.update { it.copy(swipe = it.swipe.copy(left = choices[i])) }
            }
            Text(stringResource(R.string.swipe_try), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            // A preview row: swiping it only says what would happen.
            SwipeActionRow(s.swipe, hasNumber = true, canDelete = true, onAction = { a -> tried = res.getString(R.string.swipe_tried, swipeLabel(res, a, short = true)) }) {
                app.parley.ui.OnGroupSurface {
                    val example = stringResource(R.string.swipe_example_name)
                    ListItem(
                        leadingContent = { Avatar(example, null, 40.dp) },
                        headlineContent = { Text(example) },
                        supportingContent = { Text(tried ?: stringResource(R.string.swipe_try_hint)) },
                    )
                }
            }
            Text(
                stringResource(R.string.swipe_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** U6: Settings › Appearance › Avatars. */
@Composable
fun AvatarStyleSetting(vm: AppViewModel) {
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val styles = AvatarStyle.entries
    Column {
        val avatarLabels = styles.map { st -> stringResource(if (st == AvatarStyle.GREY) R.string.avatar_grey else R.string.avatar_colourful) }
        MenuRow(app.parley.ui.settings.settingTitle("avatar_style"), avatarLabels, styles.indexOf(s.avatarStyle), Icons.Rounded.AccountCircle, sub = stringResource(R.string.avatar_emoji_hint)) { i ->
            vm.people.update { it.copy(avatarStyle = styles[i]) }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalAvatarStyle provides s.avatarStyle) {
            androidx.compose.foundation.layout.Row(Modifier.padding(start = 72.dp, bottom = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Avatar("Anna Smith", null, 36.dp)
                Avatar("Ben", null, 36.dp)
                Avatar("🐶 Rex", null, 36.dp)
                Avatar("Acme", null, 36.dp, isCompany = true)
            }
        }
    }
}
