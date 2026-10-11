package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import app.parley.R
import app.parley.common.NotificationChannels

/**
 * The channels of Parley's notices (everything but calls and screening), created in one place. The "Reminders" ones
 * (birthdays and keep in touch, To call, the backup reminder) always land in their group. Ids never change: Android
 * keys a person's sound and importance choices to them. A channel made before the group existed joins it the next
 * time it is created here.
 *
 * Backup results (a failed scheduled backup) stay in their own channel outside the group; see [BackupWorker.notify].
 */
object NoticeChannels {
    private class Spec(@StringRes val name: Int, val importance: Int, val badge: Boolean = true, @StringRes val description: Int = 0)

    private val specs: Map<String, Spec> = mapOf(
        NotificationChannels.REMINDERS to Spec(R.string.work_channel_reminders, NotificationManager.IMPORTANCE_DEFAULT),
        // To call: never a badge on the app icon.
        NotificationChannels.TO_CALL to Spec(R.string.discover_to_call_title, NotificationManager.IMPORTANCE_DEFAULT, badge = false),
        NotificationChannels.BACKUP_REMINDER to Spec(R.string.work_channel_backup_reminder, NotificationManager.IMPORTANCE_DEFAULT),
        NotificationChannels.BACKUPS to Spec(R.string.work_channel_backups, NotificationManager.IMPORTANCE_DEFAULT),
        NotificationChannels.HOUSEKEEPING to Spec(R.string.work_channel_housekeeping, NotificationManager.IMPORTANCE_LOW),
        NotificationChannels.PLAN to Spec(R.string.hist_plan_section, NotificationManager.IMPORTANCE_DEFAULT),
        NotificationChannels.CONTACTS_SAFETY to Spec(R.string.watch_channel, NotificationManager.IMPORTANCE_DEFAULT),
        NotificationChannels.PRIVATE_NAMES to Spec(R.string.privnames_channel, NotificationManager.IMPORTANCE_DEFAULT),
        NotificationChannels.JOBS to Spec(R.string.job_channel, NotificationManager.IMPORTANCE_LOW, description = R.string.job_channel_desc),
        // A reminder that something is on, never news: no sound, no badge.
        NotificationChannels.SITUATION to Spec(
            R.string.sit_notice_channel, NotificationManager.IMPORTANCE_LOW, badge = false, description = R.string.sit_notice_channel_desc,
        ),
    )

    /** Creates (or updates) the channel [id] (and its group for a reminder channel). */
    fun ensure(context: Context, id: String) {
        val spec = specs[id] ?: error("$id isn't a notice channel")
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val reminder = id in NotificationChannels.reminderChannels
        if (reminder) {
            nm.createNotificationChannelGroup(NotificationChannelGroup(NotificationChannels.REMINDERS_GROUP, context.getString(R.string.notif_group_reminders)))
        }
        nm.createNotificationChannel(
            NotificationChannel(id, context.getString(spec.name), firstImportance(nm, id, spec.importance)).apply {
                if (reminder) group = NotificationChannels.REMINDERS_GROUP
                if (spec.description != 0) description = context.getString(spec.description)
                setShowBadge(spec.badge)
            },
        )
    }

    /**
     * The backup reminder used to share the Backups channel. When its own channel is made for the first time it
     * starts no louder than Backups was set: someone who turned Backups off keeps the reminder off too.
     */
    private fun firstImportance(nm: NotificationManager, id: String, importance: Int): Int {
        if (id != NotificationChannels.BACKUP_REMINDER || nm.getNotificationChannel(id) != null) return importance
        val old = nm.getNotificationChannel(NotificationChannels.BACKUPS) ?: return importance
        return minOf(importance, old.importance)
    }

    /** Moves the channels that already exist into the group (after an update), without creating the others. */
    fun regroupExisting(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        NotificationChannels.reminderChannels.filter { nm.getNotificationChannel(it) != null }.forEach { ensure(context, it) }
    }
}
