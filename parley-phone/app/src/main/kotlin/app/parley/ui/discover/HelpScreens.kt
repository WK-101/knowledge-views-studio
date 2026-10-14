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
    @Suppress("CyclomaticComplexMethod") // One branch per help page.
    fun of(t: HelpTopic): HelpTexts = when (t) {
        HelpTopic.CALL_SCREEN -> HelpTexts(R.string.help_call_screen_title, R.string.help_call_screen_body, R.string.label_default_phone_app)
        HelpTopic.DID_NOT_RING -> HelpTexts(R.string.help_did_not_ring_title, R.string.help_did_not_ring_body, R.string.action_test_a_call)
        HelpTopic.SITUATION_QUIET ->
            HelpTexts(R.string.help_situation_quiet_title, R.string.help_situation_quiet_body, R.string.label_situations)
        HelpTopic.EXPECTING -> HelpTexts(R.string.help_expecting_title, R.string.help_expecting_body, R.string.label_expecting_a_call)
        HelpTopic.NOTIFICATIONS -> HelpTexts(R.string.help_notifications_title, R.string.help_notifications_body, R.string.label_notification_settings)
        HelpTopic.LATE -> HelpTexts(R.string.help_late_title, R.string.help_late_body, R.string.label_battery_optimisation)
        HelpTopic.ARCHIVED -> HelpTexts(R.string.help_archived_title, R.string.help_archived_body, R.string.label_archived_contacts)
        HelpTopic.UNDO -> HelpTexts(R.string.help_undo_title, R.string.help_undo_body, R.string.label_history_undo)
        HelpTopic.NEW_PHONE -> HelpTexts(R.string.help_new_phone_title, R.string.help_new_phone_body, R.string.label_encrypted_backups)
        HelpTopic.SOMETHING_ELSE ->
            HelpTexts(R.string.help_something_else_title, R.string.help_something_else_body, R.string.action_export_diagnostics)
        HelpTopic.CHAPTERS -> HelpTexts(R.string.label_chapters, R.string.help_chapters_body, R.string.label_labels)
        HelpTopic.TALK_ABOUT -> HelpTexts(R.string.label_to_talk_about, R.string.help_talk_about_body, null)
        HelpTopic.FAMILY_SHIELD -> HelpTexts(R.string.label_family_spam_shield, R.string.help_family_shield_body, R.string.label_shared_labels)
        HelpTopic.PROMISE_TIME -> HelpTexts(R.string.label_promise_time, R.string.help_promise_time_body, R.string.label_to_call)
        HelpTopic.NEXT_UP -> HelpTexts(R.string.next_up_label, R.string.help_next_up_body, R.string.label_to_call)
        HelpTopic.CALLBACK_WATCH -> HelpTexts(R.string.callback_title, R.string.help_callback_watch_body, R.string.label_to_call)
        HelpTopic.SEASONS -> HelpTexts(R.string.circle_season_title, R.string.help_seasons_body, null)
        HelpTopic.QUIETER -> HelpTexts(R.string.help_quieter_title, R.string.help_quieter_body, null)
        HelpTopic.LABEL_CIRCLE -> HelpTexts(R.string.help_label_circle_title, R.string.help_label_circle_body, R.string.label_labels)
        HelpTopic.YOUR_YEAR -> HelpTexts(R.string.circle_year_review, R.string.help_your_year_body, R.string.label_call_insights)
        HelpTopic.SITUATIONS_WIDGET ->
            HelpTexts(R.string.discover_situations_widget_title, R.string.help_situations_widget_body, R.string.label_situations)
        HelpTopic.HIDE_NUMBER -> HelpTexts(R.string.help_hide_number_title, R.string.help_hide_number_body, null)
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
                Text(stringResource(R.string.action_open_named, place), Modifier.padding(start = Spacing.s))
            }
        }
    }
}
