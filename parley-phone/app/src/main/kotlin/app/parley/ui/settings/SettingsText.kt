package app.parley.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.SettingEntry
import app.parley.common.SettingsCatalog
import app.parley.common.SettingsCategory

/**
 * Localised texts for [SettingsCatalog]: core:common keeps stable keys (and English reference texts that search
 * keeps matching), the app maps each key to string resources. Keywords are one comma-separated string per
 * setting, so translators can add their own synonyms.
 */
object SettingsText {
    private val entries: Map<String, Triple<Int, Int, Int>> = mapOf(
        "theme" to Triple(R.string.set_theme_title, R.string.set_theme_summary, R.string.set_theme_kw),
        "amoled" to Triple(R.string.set_amoled_title, R.string.set_amoled_summary, R.string.set_amoled_kw),
        "dynamic_color" to Triple(R.string.set_dynamic_color_title, R.string.set_dynamic_color_summary, R.string.set_dynamic_color_kw),
        "language" to Triple(R.string.set_language_title, R.string.set_language_summary, R.string.set_language_kw),
        "density" to Triple(R.string.set_density_title, R.string.set_density_summary, R.string.set_density_kw),
        "nav_tabs" to Triple(R.string.set_nav_tabs_title, R.string.set_nav_tabs_summary, R.string.set_nav_tabs_kw),
        "start_tab" to Triple(R.string.set_start_tab_title, R.string.set_start_tab_summary, R.string.set_start_tab_kw),
        "row_actions" to Triple(R.string.set_row_actions_title, R.string.set_row_actions_summary, R.string.set_row_actions_kw),
        "sort_names" to Triple(R.string.set_sort_names_title, R.string.set_sort_names_summary, R.string.set_sort_names_kw),
        "second_line" to Triple(R.string.set_second_line_title, R.string.set_second_line_summary, R.string.set_second_line_kw),
        "prefer_nickname" to Triple(R.string.set_prefer_nickname_title, R.string.set_prefer_nickname_summary, R.string.set_prefer_nickname_kw),
        "swipe_actions" to Triple(R.string.set_swipe_actions_title, R.string.set_swipe_actions_summary, R.string.set_swipe_actions_kw),
        "avatar_style" to Triple(R.string.set_avatar_style_title, R.string.set_avatar_style_summary, R.string.set_avatar_style_kw),
        "reset_tips" to Triple(R.string.set_reset_tips_title, R.string.set_reset_tips_summary, R.string.set_reset_tips_kw),
        "default_dialer" to Triple(R.string.set_default_dialer_title, R.string.set_default_dialer_summary, R.string.set_default_dialer_kw),
        "default_dialer_help" to Triple(R.string.set_default_dialer_help_title, R.string.set_default_dialer_help_summary, R.string.set_default_dialer_help_kw),
        "answer_gesture" to Triple(R.string.set_answer_gesture_title, R.string.set_answer_gesture_summary, R.string.set_answer_gesture_kw),
        "confirm_call" to Triple(R.string.set_confirm_call_title, R.string.set_confirm_call_summary, R.string.set_confirm_call_kw),
        "call_haptics" to Triple(R.string.set_call_haptics_title, R.string.set_call_haptics_summary, R.string.set_call_haptics_kw),
        "connect_haptic" to Triple(R.string.set_connect_haptic_title, R.string.set_connect_haptic_summary, R.string.set_connect_haptic_kw),
        "unknown_ringtone" to Triple(R.string.set_unknown_ringtone_title, R.string.set_unknown_ringtone_summary, R.string.set_unknown_ringtone_kw),
        "pocket_guard" to Triple(R.string.set_pocket_guard_title, R.string.set_pocket_guard_summary, R.string.set_pocket_guard_kw),
        "missed_realert" to Triple(R.string.set_missed_realert_title, R.string.set_missed_realert_summary, R.string.set_missed_realert_kw),
        "proximity_sensor" to Triple(R.string.set_proximity_sensor_title, R.string.set_proximity_sensor_summary, R.string.set_proximity_sensor_kw),
        "power_button_ends_call" to Triple(R.string.set_power_button_ends_call_title, R.string.set_power_button_ends_call_summary, R.string.set_power_button_ends_call_kw),
        "voicemail" to Triple(R.string.set_voicemail_title, R.string.set_voicemail_summary, R.string.set_voicemail_kw),
        "sims" to Triple(R.string.set_sims_title, R.string.set_sims_summary, R.string.set_sims_kw),
        "sim_accounts" to Triple(R.string.set_sim_accounts_title, R.string.set_sim_accounts_summary, R.string.set_sim_accounts_kw),
        "carrier_settings" to Triple(R.string.set_carrier_settings_title, R.string.set_carrier_settings_summary, R.string.set_carrier_settings_kw),
        "keypad_tones" to Triple(R.string.set_keypad_tones_title, R.string.set_keypad_tones_summary, R.string.set_keypad_tones_kw),
        "keypad_vibration" to Triple(R.string.set_keypad_vibration_title, R.string.set_keypad_vibration_summary, R.string.set_keypad_vibration_kw),
        "keypad_letters" to Triple(R.string.set_keypad_letters_title, R.string.set_keypad_letters_summary, R.string.set_keypad_letters_kw),
        "speed_dial" to Triple(R.string.set_speed_dial_title, R.string.set_speed_dial_summary, R.string.set_speed_dial_kw),
        "ussd" to Triple(R.string.set_ussd_title, R.string.set_ussd_summary, R.string.set_ussd_kw),
        "call_time" to Triple(R.string.set_call_time_title, R.string.set_call_time_summary, R.string.set_call_time_kw),
        "plan_minutes" to Triple(R.string.set_plan_minutes_title, R.string.set_plan_minutes_summary, R.string.set_plan_minutes_kw),
        "blocking" to Triple(R.string.set_blocking_title, R.string.set_blocking_summary, R.string.set_blocking_kw),
        "repeat_callers" to Triple(R.string.set_repeat_callers_title, R.string.set_repeat_callers_summary, R.string.set_repeat_callers_kw),
        "expecting_call" to Triple(R.string.set_expecting_call_title, R.string.set_expecting_call_summary, R.string.set_expecting_call_kw),
        "spam_lists" to Triple(R.string.set_spam_lists_title, R.string.set_spam_lists_summary, R.string.set_spam_lists_kw),
        "templates" to Triple(R.string.set_templates_title, R.string.set_templates_summary, R.string.set_templates_kw),
        "dry_run" to Triple(R.string.set_dry_run_title, R.string.set_dry_run_summary, R.string.set_dry_run_kw),
        "transfer" to Triple(R.string.set_transfer_title, R.string.set_transfer_summary, R.string.set_transfer_kw),
        "default_account" to Triple(R.string.set_default_account_title, R.string.set_default_account_summary, R.string.set_default_account_kw),
        "labels" to Triple(R.string.set_labels_title, R.string.set_labels_summary, R.string.set_labels_kw),
        "temporary_contacts" to Triple(R.string.set_temporary_contacts_title, R.string.set_temporary_contacts_summary, R.string.set_temporary_contacts_kw),
        "duplicates" to Triple(R.string.set_duplicates_title, R.string.set_duplicates_summary, R.string.set_duplicates_kw),
        "health" to Triple(R.string.set_health_title, R.string.set_health_summary, R.string.set_health_kw),
        "import_file" to Triple(R.string.set_import_file_title, R.string.set_import_file_summary, R.string.set_import_file_kw),
        "bulk_add" to Triple(R.string.set_bulk_add_title, R.string.set_bulk_add_summary, R.string.set_bulk_add_kw),
        "scan_qr" to Triple(R.string.qs_set_title, R.string.qs_set_summary, R.string.qs_set_kw),
        "import_sim" to Triple(R.string.set_import_sim_title, R.string.set_import_sim_summary, R.string.set_import_sim_kw),
        "export_vcf" to Triple(R.string.set_export_vcf_title, R.string.set_export_vcf_summary, R.string.set_export_vcf_kw),
        "export_csv" to Triple(R.string.set_export_csv_title, R.string.set_export_csv_summary, R.string.set_export_csv_kw),
        "export_account" to Triple(R.string.set_export_account_title, R.string.set_export_account_summary, R.string.set_export_account_kw),
        "birthdays" to Triple(R.string.set_birthdays_title, R.string.set_birthdays_summary, R.string.set_birthdays_kw),
        "birthday_reminders" to Triple(R.string.set_birthday_reminders_title, R.string.set_birthday_reminders_summary, R.string.set_birthday_reminders_kw),
        "reminder_time" to Triple(R.string.set_reminder_time_title, R.string.set_reminder_time_summary, R.string.set_reminder_time_kw),
        "nudges" to Triple(R.string.set_nudges_title, R.string.set_nudges_summary, R.string.set_nudges_kw),
        "date_lead" to Triple(R.string.set_date_lead_title, R.string.set_date_lead_summary, R.string.set_date_lead_kw),
        "circle_delivery" to Triple(R.string.set_circle_delivery_title, R.string.set_circle_delivery_summary, R.string.set_circle_delivery_kw),
        "circle_weekly_cap" to Triple(R.string.set_circle_weekly_cap_title, R.string.set_circle_weekly_cap_summary, R.string.set_circle_weekly_cap_kw),
        "log_prompts" to Triple(R.string.set_log_prompts_title, R.string.set_log_prompts_summary, R.string.set_log_prompts_kw),
        "memory_prompt" to Triple(R.string.c2_set_memory_prompt_title, R.string.c2_set_memory_prompt_summary, R.string.c2_set_memory_prompt_kw),
        "memory_lock_screen" to Triple(R.string.c2_set_memory_lock_title, R.string.c2_set_memory_lock_summary, R.string.c2_set_memory_lock_kw),
        "pre_call_peek" to Triple(R.string.c2_set_peek_title, R.string.c2_set_peek_summary, R.string.c2_set_peek_kw),
        "people_card" to Triple(R.string.c2_set_people_card_title, R.string.c2_set_people_card_summary, R.string.c2_set_people_card_kw),
        "first_mover" to Triple(R.string.c2_set_first_mover_title, R.string.c2_set_first_mover_summary, R.string.c2_set_first_mover_kw),
        "archive" to Triple(R.string.set_archive_title, R.string.set_archive_summary, R.string.set_archive_kw),
        "history_details" to Triple(R.string.set_history_details_title, R.string.set_history_details_summary, R.string.set_history_details_kw),
        "retention" to Triple(R.string.set_retention_title, R.string.set_retention_summary, R.string.set_retention_kw),
        "sim_labels" to Triple(R.string.set_sim_labels_title, R.string.set_sim_labels_summary, R.string.set_sim_labels_kw),
        "recents_layout" to Triple(R.string.set_recents_layout_title, R.string.set_recents_layout_summary, R.string.set_recents_layout_kw),
        "recents_style" to Triple(R.string.v33_set_recents_style_title, R.string.v33_set_recents_style_summary, R.string.v33_set_recents_style_kw),
        "clear_history" to Triple(R.string.set_clear_history_title, R.string.set_clear_history_summary, R.string.set_clear_history_kw),
        "insights" to Triple(R.string.set_insights_title, R.string.set_insights_summary, R.string.set_insights_kw),
        "import_calls" to Triple(R.string.set_import_calls_title, R.string.set_import_calls_summary, R.string.set_import_calls_kw),
        "quick_replies" to Triple(R.string.set_quick_replies_title, R.string.set_quick_replies_summary, R.string.set_quick_replies_kw),
        "my_details" to Triple(R.string.set_my_details_title, R.string.set_my_details_summary, R.string.set_my_details_kw),
        "messaged_numbers" to Triple(R.string.set_messaged_numbers_title, R.string.set_messaged_numbers_summary, R.string.set_messaged_numbers_kw),
        "messaged_expiry" to Triple(R.string.set_messaged_expiry_title, R.string.set_messaged_expiry_summary, R.string.set_messaged_expiry_kw),
        "app_lock" to Triple(R.string.set_app_lock_title, R.string.set_app_lock_summary, R.string.set_app_lock_kw),
        "lock_after" to Triple(R.string.set_lock_after_title, R.string.set_lock_after_summary, R.string.set_lock_after_kw),
        "secure_screen" to Triple(R.string.set_secure_screen_title, R.string.set_secure_screen_summary, R.string.set_secure_screen_kw),
        "hide_vault" to Triple(R.string.set_hide_vault_title, R.string.set_hide_vault_summary, R.string.set_hide_vault_kw),
        "private_history" to Triple(R.string.set_private_history_title, R.string.set_private_history_summary, R.string.set_private_history_kw),
        "privacy_dashboard" to Triple(R.string.set_privacy_dashboard_title, R.string.set_privacy_dashboard_summary, R.string.set_privacy_dashboard_kw),
        "who_can_see" to Triple(R.string.set_who_can_see_title, R.string.set_who_can_see_summary, R.string.set_who_can_see_kw),
        "private_names" to Triple(R.string.set_private_names_title, R.string.set_private_names_summary, R.string.set_private_names_kw),
        "private_directory" to Triple(R.string.set_private_directory_title, R.string.set_private_directory_summary, R.string.set_private_directory_kw),
        "app_permissions" to Triple(R.string.set_app_permissions_title, R.string.set_app_permissions_summary, R.string.set_app_permissions_kw),
        "backup" to Triple(R.string.set_backup_title, R.string.set_backup_summary, R.string.set_backup_kw),
        "backup_reminder" to Triple(R.string.set_backup_reminder_title, R.string.set_backup_reminder_summary, R.string.set_backup_reminder_kw),
        "sync" to Triple(R.string.set_sync_title, R.string.set_sync_summary, R.string.set_sync_kw),
        "journal" to Triple(R.string.set_journal_title, R.string.set_journal_summary, R.string.set_journal_kw),
        "markdown_export" to Triple(R.string.x_set_markdown_title, R.string.x_set_markdown_summary, R.string.x_set_markdown_kw),
        "simple_mode" to Triple(R.string.x_set_simple_title, R.string.x_set_simple_summary, R.string.x_set_simple_kw),
        "time_machine" to Triple(R.string.set_time_machine_title, R.string.set_time_machine_summary, R.string.set_time_machine_kw),
        "notification_settings" to Triple(R.string.set_notification_settings_title, R.string.set_notification_settings_summary, R.string.set_notification_settings_kw),
        "full_screen" to Triple(R.string.set_full_screen_title, R.string.set_full_screen_summary, R.string.set_full_screen_kw),
        "battery" to Triple(R.string.set_battery_title, R.string.set_battery_summary, R.string.set_battery_kw),
        "xiaomi" to Triple(R.string.set_xiaomi_title, R.string.set_xiaomi_summary, R.string.set_xiaomi_kw),
        "version" to Triple(R.string.set_version_title, R.string.set_version_summary, R.string.set_version_kw),
        "diagnostics" to Triple(R.string.set_diagnostics_title, R.string.set_diagnostics_summary, R.string.set_diagnostics_kw),
        "crash_reports" to Triple(R.string.set_crash_reports_title, R.string.set_crash_reports_summary, R.string.set_crash_reports_kw),
    )

    /** Every catalog key has resources (checked by [localizedCatalog], which Settings search calls). */
    private fun res(key: String): Triple<Int, Int, Int> = entries[key] ?: error("No string resources for setting $key")

    @StringRes fun title(key: String): Int = res(key).first

    @StringRes fun summary(key: String): Int = res(key).second

    @StringRes fun title(c: SettingsCategory): Int = when (c) {
        SettingsCategory.APPEARANCE -> R.string.set_cat_appearance_title
        SettingsCategory.CALLS -> R.string.set_cat_calls_title
        SettingsCategory.KEYPAD -> R.string.set_cat_keypad_title
        SettingsCategory.CALL_TIME -> R.string.set_cat_call_time_title
        SettingsCategory.BLOCKING -> R.string.set_cat_blocking_title
        SettingsCategory.CONTACTS -> R.string.set_cat_contacts_title
        SettingsCategory.HISTORY -> R.string.set_cat_history_title
        SettingsCategory.MESSAGING -> R.string.set_cat_messaging_title
        SettingsCategory.PRIVACY -> R.string.set_cat_privacy_title
        SettingsCategory.BACKUP -> R.string.set_cat_backup_title
        SettingsCategory.NOTIFICATIONS -> R.string.set_cat_notifications_title
        SettingsCategory.ABOUT -> R.string.set_cat_about_title
    }

    @StringRes fun summary(c: SettingsCategory): Int = when (c) {
        SettingsCategory.APPEARANCE -> R.string.set_cat_appearance_summary
        SettingsCategory.CALLS -> R.string.set_cat_calls_summary
        SettingsCategory.KEYPAD -> R.string.set_cat_keypad_summary
        SettingsCategory.CALL_TIME -> R.string.set_cat_call_time_summary
        SettingsCategory.BLOCKING -> R.string.set_cat_blocking_summary
        SettingsCategory.CONTACTS -> R.string.set_cat_contacts_summary
        SettingsCategory.HISTORY -> R.string.set_cat_history_summary
        SettingsCategory.MESSAGING -> R.string.set_cat_messaging_summary
        SettingsCategory.PRIVACY -> R.string.set_cat_privacy_summary
        SettingsCategory.BACKUP -> R.string.set_cat_backup_summary
        SettingsCategory.NOTIFICATIONS -> R.string.set_cat_notifications_summary
        SettingsCategory.ABOUT -> R.string.set_cat_about_summary
    }

    /** The catalog in the current language, for Settings search; English words keep matching. */
    fun localizedCatalog(context: Context): List<SettingEntry> = SettingsCatalog.entries.map { e ->
        val (t, s, k) = res(e.key)
        e.localized(
            title = context.getString(t),
            summary = context.getString(s),
            // Latin and Arabic commas.
            keywords = context.getString(k).split(',', '\u060C'),
            categoryTitle = context.getString(title(e.category)),
        )
    }
}

/**
 * Keeps a phone number, code or other digits left-to-right inside right-to-left text (Arabic, Urdu), without
 * forcing the direction of the text around it. For display only: never store the result.
 */
fun bidiLtr(s: String): String = android.text.BidiFormatter.getInstance().unicodeWrap(s, android.text.TextDirectionHeuristics.LTR)

/** [bidiLtr] when [s] is a phone number (a title that fell back to the number), else [s] unchanged. */
fun bidiLtrIfNumber(s: String): String = if (s.isNotEmpty() && s.all { it.isDigit() || it in "+-() .#*" }) bidiLtr(s) else s

/** Localised title of the setting [key]. */
@Composable
fun settingTitle(key: String): String = stringResource(SettingsText.title(key))

/** Localised summary of the setting [key]. */
@Composable
fun settingSummary(key: String): String = stringResource(SettingsText.summary(key))

@Composable
fun SettingsCategory.localTitle(): String = stringResource(SettingsText.title(this))

@Composable
fun SettingsCategory.localSummary(): String = stringResource(SettingsText.summary(this))
