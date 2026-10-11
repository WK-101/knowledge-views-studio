package app.parley.ui.discover

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.ux.HelpTopic
import app.parley.ui.LinkRow
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing

/** The texts of a help page: its title, what to know, and the name of the place its button opens (null: none). */
data class HelpTexts(@StringRes val title: Int, @StringRes val body: Int, @StringRes val action: Int?)

object HelpText {
    fun of(t: HelpTopic): HelpTexts = when (t) {
        HelpTopic.CALL_SCREEN -> HelpTexts(R.string.help_call_screen_title, R.string.help_call_screen_body, R.string.help_call_screen_action)
        HelpTopic.DID_NOT_RING -> HelpTexts(R.string.help_did_not_ring_title, R.string.help_did_not_ring_body, R.string.discover_test_call_title)
        HelpTopic.SITUATION_QUIET ->
            HelpTexts(R.string.help_situation_quiet_title, R.string.help_situation_quiet_body, R.string.discover_situations_title)
        HelpTopic.EXPECTING -> HelpTexts(R.string.help_expecting_title, R.string.help_expecting_body, R.string.blk_check_expecting)
        HelpTopic.NOTIFICATIONS -> HelpTexts(R.string.help_notifications_title, R.string.help_notifications_body, R.string.help_notifications_action)
        HelpTopic.LATE -> HelpTexts(R.string.help_late_title, R.string.help_late_body, R.string.help_late_action)
        HelpTopic.ARCHIVED -> HelpTexts(R.string.help_archived_title, R.string.help_archived_body, R.string.recall_group_archived)
        HelpTopic.UNDO -> HelpTexts(R.string.help_undo_title, R.string.help_undo_body, R.string.jr_title)
        HelpTopic.NEW_PHONE -> HelpTexts(R.string.help_new_phone_title, R.string.help_new_phone_body, R.string.discover_backup_title)
        HelpTopic.SOMETHING_ELSE ->
            HelpTexts(R.string.help_something_else_title, R.string.help_something_else_body, R.string.diag_title)
        HelpTopic.CHAPTERS -> HelpTexts(R.string.discover_chapters_title, R.string.help_chapters_body, R.string.blk_check_labels)
        HelpTopic.TALK_ABOUT -> HelpTexts(R.string.agenda_title, R.string.help_talk_about_body, null)
        HelpTopic.FAMILY_SHIELD -> HelpTexts(R.string.blk_decides_shield, R.string.help_family_shield_body, R.string.discover_shared_labels_title)
    }
}

/** Tools › Help & troubleshooting: one row per question; each opens its page. Nothing here is a setting. */
@Composable
fun HelpScreen(back: () -> Unit, openTopic: (HelpTopic) -> Unit) {
    SettingsScaffold(stringResource(R.string.help_title), back) {
        Text(
            stringResource(R.string.help_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        SegmentedGroup {
            HelpTopic.troubleshooting.forEach { t ->
                item(t.key) { LinkRow(stringResource(HelpText.of(t).title), null) { openTopic(t) } }
            }
        }
    }
}

/**
 * One help page: what to know, then one button to the place that fixes it, opened as Tools opens it (a screen, or a
 * setting where Settings search would open it). A link to a page that no longer exists shows Help's own list.
 */
@Composable
fun HelpPageScreen(vm: AppViewModel, topic: HelpTopic?, back: () -> Unit, openTopic: (HelpTopic) -> Unit) {
    if (topic == null) {
        HelpScreen(back, openTopic)
        return
    }
    val texts = HelpText.of(topic)
    val title = stringResource(texts.title)
    SettingsScaffold(title, back) {
        Text(
            stringResource(texts.body), style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        val target = topic.target
        val action = texts.action
        if (target != null && action != null) {
            val place = stringResource(action)
            FilledTonalButton({ vm.navigate(capabilityEvent(target)) }, Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.m)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                Text(stringResource(R.string.fav_widget_open_name, place), Modifier.padding(start = Spacing.s))
            }
        }
    }
}
