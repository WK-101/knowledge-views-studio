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
        "test_call" to (R.string.discover_test_call_title to R.string.discover_test_call_summary),
        "rule_templates" to (R.string.discover_rule_templates_title to R.string.discover_rule_templates_summary),
        "block_import" to (R.string.discover_block_import_title to R.string.discover_block_import_summary),
        "expecting" to (R.string.discover_expecting_title to R.string.discover_expecting_summary),
        "history_undo" to (R.string.discover_history_undo_title to R.string.discover_history_undo_summary),
        "snapshots" to (R.string.discover_snapshots_title to R.string.discover_snapshots_summary),
        "backup" to (R.string.discover_backup_title to R.string.discover_backup_summary),
        "sync" to (R.string.discover_sync_title to R.string.discover_sync_summary),
        "coming_from" to (R.string.discover_coming_from_title to R.string.discover_coming_from_summary),
        "health" to (R.string.discover_health_title to R.string.discover_health_summary),
        "duplicates" to (R.string.discover_duplicates_title to R.string.discover_duplicates_summary),
        "circle" to (R.string.discover_circle_title to R.string.discover_circle_summary),
        "birthdays" to (R.string.discover_birthdays_title to R.string.discover_birthdays_summary),
        "to_call" to (R.string.discover_to_call_title to R.string.discover_to_call_summary),
        "remember" to (R.string.discover_remember_title to R.string.discover_remember_summary),
        "insights" to (R.string.discover_insights_title to R.string.discover_insights_summary),
        "labels" to (R.string.discover_labels_title to R.string.discover_labels_summary),
        "caller_vibration" to (R.string.discover_caller_vibration_title to R.string.discover_caller_vibration_summary),
        "unknown_ringtone" to (R.string.discover_unknown_ringtone_title to R.string.discover_unknown_ringtone_summary),
        "scan_qr" to (R.string.discover_scan_qr_title to R.string.discover_scan_qr_summary),
        "safe_word" to (R.string.discover_safe_word_title to R.string.discover_safe_word_summary),
        "privacy_dashboard" to (R.string.discover_privacy_dashboard_title to R.string.discover_privacy_dashboard_summary),
        "private_contacts" to (R.string.discover_private_contacts_title to R.string.discover_private_contacts_summary),
        "temporary" to (R.string.discover_temporary_title to R.string.discover_temporary_summary),
        "who_can_see" to (R.string.discover_who_can_see_title to R.string.discover_who_can_see_summary),
        "app_lock" to (R.string.discover_app_lock_title to R.string.discover_app_lock_summary),
        "message_number" to (R.string.discover_message_number_title to R.string.discover_message_number_summary),
        "messaged" to (R.string.discover_messaged_title to R.string.discover_messaged_summary),
        "bulk_add" to (R.string.discover_bulk_add_title to R.string.discover_bulk_add_summary),
        "my_card" to (R.string.discover_my_card_title to R.string.discover_my_card_summary),
        "paste_details" to (R.string.discover_paste_details_title to R.string.discover_paste_details_summary),
        "card_updates" to (R.string.discover_card_updates_title to R.string.discover_card_updates_summary),
        "quick_replies" to (R.string.discover_quick_replies_title to R.string.discover_quick_replies_summary),
        "auto_answer" to (R.string.discover_auto_answer_title to R.string.discover_auto_answer_summary),
        "helpers" to (R.string.discover_helpers_title to R.string.discover_helpers_summary),
        "call_time" to (R.string.discover_call_time_title to R.string.discover_call_time_summary),
        "sims" to (R.string.discover_sims_title to R.string.discover_sims_summary),
        "pocket_guard" to (R.string.discover_pocket_guard_title to R.string.discover_pocket_guard_summary),
        "missed_realert" to (R.string.discover_missed_realert_title to R.string.discover_missed_realert_summary),
        "simple_mode" to (R.string.discover_simple_mode_title to R.string.discover_simple_mode_summary),
    )

    /** Title and summary of [c]; a row without texts fails loudly in the catalog test, never silently on screen. */
    fun of(c: Capability): Pair<Int, Int> = rows[c.key] ?: error("No string resources for capability ${c.key}")

    fun has(key: String): Boolean = key in rows

    @StringRes fun job(j: Job): Int = when (j) {
        Job.STOP_SPAM -> R.string.discover_job_stop_spam
        Job.NEVER_LOSE -> R.string.discover_job_never_lose
        Job.STAY_IN_TOUCH -> R.string.discover_job_stay_in_touch
        Job.KNOW_WHO -> R.string.discover_job_know_who
        Job.KEEP_PRIVATE -> R.string.discover_job_keep_private
        Job.MESSAGE -> R.string.discover_job_message
        Job.BETTER_CALLS -> R.string.discover_job_better_calls
    }
}
