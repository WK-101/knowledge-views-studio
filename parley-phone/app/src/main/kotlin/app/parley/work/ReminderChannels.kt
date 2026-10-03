package app.parley.work

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.StringRes
import app.parley.R
import app.parley.common.NotificationChannels

/**
 * The "Reminders" channel group and its channels (birthdays and keep in touch, To call, backups), created in one
 * place so each one always lands in the group. Ids never change: Android keys a person's sound and importance
 * choices to them. A channel made before the group existed joins it the next time it is created here.
 */
object ReminderChannels {
    private class Spec(@StringRes val name: Int, val importance: Int, val badge: Boolean = true)

    private val specs: Map<String, Spec> = mapOf(
        NotificationChannels.REMINDERS to Spec(R.string.work_channel_reminders, NotificationManager.IMPORTANCE_DEFAULT),
        // To call: never a badge on the app icon.
        NotificationChannels.TO_CALL to Spec(R.string.to_call_channel, NotificationManager.IMPORTANCE_DEFAULT, badge = false),
        NotificationChannels.BACKUPS to Spec(R.string.work_channel_backups, NotificationManager.IMPORTANCE_DEFAULT),
    )

    /** Creates (or updates) the group and the channel [id], which must be one of [NotificationChannels.reminderChannels]. */
    fun ensure(context: Context, id: String) {
        val spec = specs[id] ?: error("$id isn't a reminder channel")
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroup(NotificationChannelGroup(NotificationChannels.REMINDERS_GROUP, context.getString(R.string.notif_group_reminders)))
        nm.createNotificationChannel(
            NotificationChannel(id, context.getString(spec.name), spec.importance).apply {
                group = NotificationChannels.REMINDERS_GROUP
                setShowBadge(spec.badge)
            },
        )
    }

    /** Moves the channels that already exist into the group (after an update), without creating the others. */
    fun regroupExisting(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        NotificationChannels.reminderChannels.filter { nm.getNotificationChannel(it) != null }.forEach { ensure(context, it) }
    }
}
