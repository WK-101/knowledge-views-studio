package app.parley.ui.discover

import androidx.annotation.StringRes
import app.parley.R
import app.parley.common.ux.Capability
import app.parley.common.ux.Job

/**
 * The shown texts of [app.parley.common.ux.CapabilityCatalog]: core:common keeps the keys and English reference texts
 * (which search keeps matching), the app maps each key to its title and summary strings.
 */
object CapabilityText {
    private val rows: Map<String, Pair<Int, Int>> = mapOf(
        "who_rings" to (R.string.discover_who_rings_title to R.string.discover_who_rings_summary),
        "spam_lists" to (R.string.discover_spam_lists_title to R.string.discover_spam_lists_summary),
        "sales_lines" to (R.string.discover_sales_lines_title to R.string.discover_sales_lines_summary),
        "test_call" to (R.string.action_test_a_call to R.string.discover_test_call_summary),
        "rule_templates" to (R.string.discover_rule_templates_title to R.string.discover_rule_templates_summary),
        "block_import" to (R.string.discover_block_import_title to R.string.discover_block_import_summary),
        "expecting" to (R.string.label_expecting_a_call to R.string.discover_expecting_summary),
        "history_undo" to (R.string.discover_history_undo_title to R.string.discover_history_undo_summary),
        "snapshots" to (R.string.label_daily_snapshots to R.string.discover_snapshots_summary),
        "backup" to (R.string.label_encrypted_backups to R.string.discover_backup_summary),
        "sync" to (R.string.sync_title to R.string.discover_sync_summary),
        "coming_from" to (R.string.discover_coming_from_title to R.string.discover_coming_from_summary),
        "health" to (R.string.label_contact_health_check to R.string.discover_health_summary),
        "import_export" to (R.string.discover_import_export_title to R.string.discover_import_export_summary),
        "reminders" to (R.string.discover_reminders_title to R.string.discover_reminders_summary),
        "lock_now" to (R.string.discover_lock_now_title to R.string.discover_lock_now_summary),
        "duplicates" to (R.string.action_find_merge_duplicates to R.string.discover_duplicates_summary),
        "circle" to (R.string.discover_circle_title to R.string.discover_circle_summary),
        "birthdays" to (R.string.label_birthdays_dates to R.string.discover_birthdays_summary),
        "to_call" to (R.string.label_to_call to R.string.discover_to_call_summary),
        "remember" to (R.string.discover_remember_title to R.string.discover_remember_summary),
        "whos_in" to (R.string.label_whos_in to R.string.discover_whos_in_summary),
        "insights" to (R.string.label_call_insights to R.string.discover_insights_summary),
        "labels" to (R.string.discover_labels_title to R.string.discover_labels_summary),
        "caller_vibration" to (R.string.discover_caller_vibration_title to R.string.discover_caller_vibration_summary),
        "unknown_ringtone" to (R.string.discover_unknown_ringtone_title to R.string.discover_unknown_ringtone_summary),
        "scan_qr" to (R.string.discover_scan_qr_title to R.string.discover_scan_qr_summary),
        "safe_word" to (R.string.label_family_safe_word to R.string.discover_safe_word_summary),
        "privacy_dashboard" to (R.string.label_privacy_dashboard to R.string.discover_privacy_dashboard_summary),
        "private_contacts" to (R.string.label_private_contacts to R.string.discover_private_contacts_summary),
        "temporary" to (R.string.label_temporary_contacts to R.string.discover_temporary_summary),
        "who_can_see" to (R.string.privacy_who_can_see to R.string.discover_who_can_see_summary),
        "app_lock" to (R.string.label_app_lock to R.string.discover_app_lock_summary),
        "message_number" to (R.string.shortcut_message_number_long to R.string.discover_message_number_summary),
        "messaged" to (R.string.label_messaged_numbers to R.string.discover_messaged_summary),
        "bulk_add" to (R.string.action_add_several_numbers to R.string.discover_bulk_add_summary),
        "my_card" to (R.string.action_send_my_details to R.string.discover_my_card_summary),
        "paste_details" to (R.string.discover_paste_details_title to R.string.discover_paste_details_summary),
        "introduce" to (R.string.action_introduce_myself_more to R.string.discover_introduce_summary),
        "quick_replies" to (R.string.discover_quick_replies_title to R.string.discover_quick_replies_summary),
        "auto_answer" to (R.string.action_answer_automatically to R.string.discover_auto_answer_summary),
        "helpers" to (R.string.discover_helpers_title to R.string.discover_helpers_summary),
        "call_time" to (R.string.discover_call_time_title to R.string.discover_call_time_summary),
        "rescue_call" to (R.string.label_rescue_call to R.string.rescue_row_sub),
        "sims" to (R.string.discover_sims_title to R.string.discover_sims_summary),
        "pocket_guard" to (R.string.discover_pocket_guard_title to R.string.discover_pocket_guard_summary),
        "missed_realert" to (R.string.discover_missed_realert_title to R.string.discover_missed_realert_summary),
        "simple_mode" to (R.string.label_simple_mode to R.string.discover_simple_mode_summary),
        "scam_check" to (R.string.discover_scam_check_title to R.string.discover_scam_check_summary),
        "shared_labels" to (R.string.label_shared_labels to R.string.discover_shared_labels_summary),
        "parley_pin" to (R.string.discover_parley_pin_title to R.string.discover_parley_pin_summary),
        "private_names" to (R.string.pn_title to R.string.discover_private_names_summary),
        "drive_profile" to (R.string.label_drive_profile to R.string.discover_drive_profile_summary),
        "calling_abroad" to (R.string.discover_calling_abroad_title to R.string.discover_calling_abroad_summary),
        "phone_menus" to (R.string.label_phone_menus to R.string.discover_phone_menus_summary),
        "call_quality" to (R.string.label_call_quality to R.string.discover_call_quality_summary),
        "rtt" to (R.string.discover_rtt_title to R.string.discover_rtt_summary),
        "voicemail" to (R.string.label_voicemail to R.string.discover_voicemail_summary),
        "speed_dial" to (R.string.label_speed_dial to R.string.discover_speed_dial_summary),
        "search_everything" to (R.string.action_search_everything to R.string.discover_search_everything_summary),
        "archived" to (R.string.label_archived_contacts to R.string.discover_archived_summary),
        "situations" to (R.string.label_situations to R.string.discover_situations_summary),
        "case_files" to (R.string.label_case_files to R.string.discover_case_files_summary),
        "family_shield" to (R.string.label_family_spam_shield to R.string.discover_family_shield_summary),
        "chapters" to (R.string.label_chapters to R.string.discover_chapters_summary),
        "to_talk_about" to (R.string.label_to_talk_about to R.string.discover_to_talk_about_summary),
        "help" to (R.string.help_title to R.string.discover_help_summary),
        "promise_time" to (R.string.label_promise_time to R.string.discover_promise_time_summary),
        "next_up" to (R.string.next_up_label to R.string.discover_next_up_summary),
        "callback_watch" to (R.string.callback_title to R.string.discover_callback_watch_summary),
    )

    /** Title and summary of [c]; a row without texts fails loudly in the catalog test, never silently on screen. */
    fun of(c: Capability): Pair<Int, Int> = rows[c.key] ?: error("No string resources for capability ${c.key}")

    fun has(key: String): Boolean = key in rows

    @StringRes fun job(j: Job): Int = when (j) {
        Job.STOP_SPAM -> R.string.discover_job_stop_spam
        Job.NEVER_LOSE -> R.string.discover_job_never_lose
        Job.STAY_IN_TOUCH -> R.string.label_stay_in_touch
        Job.KNOW_WHO -> R.string.discover_job_know_who
        Job.KEEP_PRIVATE -> R.string.discover_job_keep_private
        Job.MESSAGE -> R.string.discover_job_message
        Job.BETTER_CALLS -> R.string.discover_job_better_calls
        Job.HELP -> R.string.discover_job_help
    }
}
