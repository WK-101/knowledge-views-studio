package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import app.parley.R
import app.parley.common.NotificationChannels

/**
 * The "Reminders" channel group and its channels (birthdays and keep in touch, To call, the backup reminder), created
 * in one place so each one always lands in the group. Ids never change: Android keys a person's sound and importance
 * choices to them. A channel made before the group existed joins it the next time it is created here.
 *
 * Backup results (a failed scheduled backup) stay in their own channel outside the group; see [BackupWorker.notify].
 */
object ReminderChannels {
    private class Spec(@StringRes val name: Int, val importance: Int, val badge: Boolean = true)

    private val specs: Map<String, Spec> = mapOf(
        NotificationChannels.REMINDERS to Spec(R.string.work_channel_reminders, NotificationManager.IMPORTANCE_DEFAULT),
        // To call: never a badge on the app icon.
        NotificationChannels.TO_CALL to Spec(R.string.to_call_channel, NotificationManager.IMPORTANCE_DEFAULT, badge = false),
        NotificationChannels.BACKUP_REMINDER to Spec(R.string.work_channel_backup_reminder, NotificationManager.IMPORTANCE_DEFAULT),
    )

    /** Creates (or updates) the group and the channel [id], which must be one of [NotificationChannels.reminderChannels]. */
    fun ensure(context: Context, id: String) {
        val spec = specs[id] ?: error("$id isn't a reminder channel")
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroup(NotificationChannelGroup(NotificationChannels.REMINDERS_GROUP, context.getString(R.string.notif_group_reminders)))
        nm.createNotificationChannel(
            NotificationChannel(id, context.getString(spec.name), firstImportance(nm, id, spec.importance)).apply {
                group = NotificationChannels.REMINDERS_GROUP
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
