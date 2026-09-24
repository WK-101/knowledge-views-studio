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
import app.parley.common.SettingsCatalog
import app.parley.common.people.AvatarStyle
import app.parley.common.people.SwipeAction
import app.parley.ui.Avatar
import app.parley.ui.LocalAvatarStyle
import app.parley.ui.settings.MenuRow
import app.parley.ui.settings.SwitchRow

/** U4: Settings › Appearance › Swipe actions, with a live preview row to try them on. */
@Composable
fun SwipeSettings(vm: AppViewModel) {
    val s by vm.people.settings.collectAsStateWithLifecycle()
    val entry = SettingsCatalog["swipe_actions"]
    val choices = SwipeAction.entries
    var tried by remember { mutableStateOf<String?>(null) }
    Column {
        SwitchRow(entry.title, entry.summary, s.swipe.enabled, Icons.Rounded.Swipe) { v -> vm.people.update { it.copy(swipe = it.swipe.copy(enabled = v)) } }
        if (s.swipe.enabled) {
            MenuRow("Swipe right", choices.map { it.label }, choices.indexOf(s.swipe.right), Icons.Rounded.SwipeRight) { i ->
                vm.people.update { it.copy(swipe = it.swipe.copy(right = choices[i])) }
            }
            MenuRow("Swipe left", choices.map { it.label }, choices.indexOf(s.swipe.left), Icons.Rounded.SwipeLeft) { i ->
                vm.people.update { it.copy(swipe = it.swipe.copy(left = choices[i])) }
            }
            Text("Try it:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            // A preview row: swiping it only says what would happen.
            SwipeActionRow(s.swipe, hasNumber = true, canDelete = true, onAction = { a -> tried = "${a.label.substringBefore(" (")}: that's what a swipe would do" }) {
                app.parley.ui.OnGroupSurface {
                    ListItem(
                        leadingContent = { Avatar("Alex Example", null, 40.dp) },
                        headlineContent = { Text("Alex Example") },
                        supportingContent = { Text(tried ?: "Swipe this row left or right") },
                    )
                }
            }
            Text(
                "Delete always offers Undo. A swipe on Recents deletes that row's calls; on Contacts, the contact (restorable from Recently deleted).",
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
    val entry = SettingsCatalog["avatar_style"]
    val styles = AvatarStyle.entries
    Column {
        MenuRow(entry.title, styles.map { it.label }, styles.indexOf(s.avatarStyle), Icons.Rounded.AccountCircle, sub = "Names starting with an emoji show it") { i ->
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
