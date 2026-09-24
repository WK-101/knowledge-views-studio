package app.parley

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.parley.data.PlaceResult
import kotlinx.coroutines.launch

/** Shows our own missed-call notification (Telecom delegates it to the default dialer). */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val pending = goAsync()
        context.container.scope.launch {
            try {
                show(context, count, number)
            } finally {
                pending.finish()
            }
        }
    }

    private fun show(context: Context, count: Int, number: String?) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (count <= 0) {
            nm.cancel(ID)
            return
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
        val name = number?.let { context.container.contacts.lookup(it)?.name }
            ?: number?.let { kotlinx.coroutines.runBlocking { context.container.vault.lookup(it)?.second?.name } }
            ?: number?.takeIf { it.isNotBlank() }
        val title = if (count == 1) "Missed call" else "$count missed calls"
        val text = if (count == 1) (name ?: "Private number") else name?.let { "Latest: $it" } ?: ""
        val open = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_MISSED).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(app.parley.ui.R.drawable.ic_stat_missed)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setNumber(count)
            .setDeleteIntent(action(context, MissedCallActionReceiver.ACTION_CLEAR, null, 13))
        if (count == 1 && !number.isNullOrBlank()) {
            b.addAction(0, "Call back", action(context, MissedCallActionReceiver.ACTION_CALL_BACK, number, 11))
            b.addAction(
                0, "Message",
                // "Message on…": SMS or a chat app, chosen in a small sheet.
                PendingIntent.getActivity(
                    context, 12,
                    app.parley.messaging.MessageOn.intent(context, number),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        try {
            NotificationManagerCompat.from(context).notify(ID, b.build())
        } catch (_: SecurityException) {
        }
    }

    private fun action(context: Context, action: String, number: String?, req: Int) = PendingIntent.getBroadcast(
        context, req,
        Intent(context, MissedCallActionReceiver::class.java).setAction(action).putExtra("number", number),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "missed_calls_v1"
        const val ID = 4712
    }
}

// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
class MissedCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.container
        val pending = goAsync()
        c.scope.launch {
            try {
                if (intent.action == ACTION_CALL_BACK) {
                    intent.getStringExtra("number")?.let { c.placer.call(it) as PlaceResult }
                    context.getSystemService(NotificationManager::class.java).cancel(MissedCallReceiver.ID)
                }
                c.callLog.markMissedRead()
                try {
                    context.getSystemService(TelecomManager::class.java).cancelMissedCallsNotification()
                } catch (_: SecurityException) {
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CALL_BACK = "app.parley.CALL_BACK"
        const val ACTION_CLEAR = "app.parley.CLEAR_MISSED"
    }
}
