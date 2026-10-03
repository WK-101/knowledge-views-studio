package com.wkhan.hexis.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.app.RemoteInput
import android.os.Build
import com.wkhan.hexis.R

/**
 * An optional, ongoing low-priority notification with an inline reply field. Tapping "Add a task" opens
 * the system keyboard's reply box, where the user can dictate (the keyboard's own mic) or type a task;
 * on send it is added instantly via the repository's headless quick-capture.
 *
 * This is a capture surface that needs NO microphone permission in the core and no addon at all — the
 * dictation is the system keyboard's job. Opt-in from Settings; the enabled state is a local preference.
 */
object QuickReplyNotification {

    const val KEY_TEXT = "com.wkhan.hexis.extra.QUICK_REPLY_TEXT"
    const val ACTION_REPLY = "com.wkhan.hexis.action.QUICK_REPLY"

    private const val CHANNEL = "quick_capture"
    private const val NOTIF_ID = 2050
    private const val PREFS = "quick_reply"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) post(context) else cancel(context)
    }

    /** Post (or refresh) the ongoing quick-capture notification. Safe to call repeatedly. */
    fun post(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(nm, context)

        val remoteInput = RemoteInput.Builder(KEY_TEXT)
            .setLabel(context.getString(R.string.quick_reply_hint))
            .build()

        val replyIntent = Intent(context, CaptureReplyReceiver::class.java).setAction(ACTION_REPLY)
        // MUTABLE is required so the system can fill in the typed/dictated text on this PendingIntent.
        val replyPi = PendingIntent.getBroadcast(
            context, 0, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(
            addActionIcon(), context.getString(R.string.quick_reply_action), replyPi,
        ).addRemoteInput(remoteInput).build()

        val notif = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.wic_voice)
            .setContentTitle(context.getString(R.string.quick_reply_title))
            .setContentText(context.getString(R.string.quick_reply_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(action)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /** Re-post on app start if the user has it enabled (it can be dropped by a force-stop / shade clear). */
    fun repostIfEnabled(context: Context) {
        if (isEnabled(context)) post(context)
    }

    private fun addActionIcon() =
        android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_input_add)

    private fun ensureChannel(nm: NotificationManager, context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.quick_reply_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
