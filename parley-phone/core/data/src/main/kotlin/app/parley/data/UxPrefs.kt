package app.parley.data

import android.content.Context
import app.parley.common.ux.BackupNudge
import app.parley.common.ux.Tips
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v3.2 layout and wording state (U2 tips, U6 "What's new", C3 backup reminder). Kept apart from [SettingsRepository]
 * because most of it isn't a setting but "what the user has already seen"; the two real settings (reminder
 * threshold, reset tips) are listed in SettingsCatalog like every other.
 */
data class UxState(
    /** U2: coach marks already dismissed. */
    val seenTips: Set<String> = emptySet(),
    /** U6: the last version code whose "What's new" card was seen, dismissed or skipped. */
    val whatsNewSeen: Int = 0,
    /** C3: remind after this many days without a backup (14 or 30). */
    val backupReminderDays: Int = BackupNudge.REMINDER_DAYS.first(),
    /** C3: the banner stays hidden until then (a dismissal snoozes, never silences for good). */
    val backupSnoozedUntil: Long = 0,
    /** C3: when the last reminder notification went out (at most one a month). */
    val backupNotifiedAt: Long = 0,
)

class UxPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("ux", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<UxState> = _state

    private fun load() = UxState(
        seenTips = Tips.decode(prefs.getString(K_TIPS, null)),
        whatsNewSeen = prefs.getInt(K_WHATS_NEW, 0),
        backupReminderDays = BackupNudge.reminderDays(prefs.getInt(K_REMINDER_DAYS, 0)),
        backupSnoozedUntil = prefs.getLong(K_SNOOZED, 0),
        backupNotifiedAt = prefs.getLong(K_NOTIFIED, 0),
    )

    private fun edit(f: (android.content.SharedPreferences.Editor) -> Unit) {
        prefs.edit().also(f).apply()
        _state.value = load()
    }

    fun dismissTip(id: String) = edit { it.putString(K_TIPS, Tips.encode(_state.value.seenTips + id)) }

    /** U2: "Reset tips" shows every coach mark again. */
    fun resetTips() = edit { it.remove(K_TIPS) }

    fun setWhatsNewSeen(version: Int) = edit { it.putInt(K_WHATS_NEW, version) }

    fun setBackupReminderDays(days: Int) = edit { it.putInt(K_REMINDER_DAYS, BackupNudge.reminderDays(days)) }

    fun snoozeBackupBanner(now: Long = System.currentTimeMillis()) = edit { it.putLong(K_SNOOZED, BackupNudge.snoozeUntil(now)) }

    fun setBackupNotified(now: Long = System.currentTimeMillis()) = edit { it.putLong(K_NOTIFIED, now) }

    /** When Parley was first installed on this device (the backup clock starts there before the first backup). */
    fun installedAt(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrDefault(0L)

    private companion object {
        const val K_TIPS = "seen_tips"
        const val K_WHATS_NEW = "whats_new_seen"
        const val K_REMINDER_DAYS = "backup_reminder_days"
        const val K_SNOOZED = "backup_snoozed_until"
        const val K_NOTIFIED = "backup_notified_at"
    }
}
