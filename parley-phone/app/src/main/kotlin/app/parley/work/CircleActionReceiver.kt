package app.parley.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.parley.container
import kotlinx.coroutines.launch

/**
 * Reminder notification actions that need no screen ("Not now", "Mark as wished", and for someone quieter than usual
 * "Add to your Circle" and "They're fine"). Not exported; the pending intents are explicit and immutable.
 */
class CircleActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(EXTRA_TAG)
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val pending = goAsync()
        val c = context.container
        c.scope.launch {
            try {
                when (intent.action) {
                    ACTION_NOT_NOW -> if (key.isNotEmpty()) c.circle.snooze(key)
                    ACTION_WISHED -> intent.getStringExtra(EXTRA_OCCASION)?.let { occasion ->
                        c.circle.markWished(key, intent.getLongExtra(EXTRA_CONTACT_ID, -1).takeIf { it > 0 }, occasion)
                    }
                    // Natural rhythm, starting from how often you used to talk.
                    ACTION_ADD -> if (key.isNotEmpty()) {
                        c.circle.setRhythm(key, intent.getLongExtra(EXTRA_CONTACT_ID, -1).takeIf { it > 0 }, intent.getIntExtra(EXTRA_DAYS, 30), natural = true)
                    }
                    ACTION_FINE -> c.circle.markFine(key, intent.getLongExtra(EXTRA_CONTACT_ID, -1).takeIf { it > 0 })
                }
            } finally {
                if (tag != null) NotificationManagerCompat.from(context).cancel(tag, 0)
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_NOT_NOW = "app.parley.circle.NOT_NOW"
        private const val ACTION_WISHED = "app.parley.circle.WISHED"
        private const val ACTION_ADD = "app.parley.circle.ADD"
        private const val ACTION_FINE = "app.parley.circle.FINE"
        private const val EXTRA_DAYS = "days"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_KEY = "lookup_key"
        private const val EXTRA_CONTACT_ID = "contact_id"
        private const val EXTRA_OCCASION = "occasion"

        private fun pending(context: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(
                context, code, intent.setClass(context, CircleActionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun notNow(context: Context, code: Int, tag: String, lookupKey: String): PendingIntent =
            pending(context, code, Intent(ACTION_NOT_NOW).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_KEY, lookupKey))

        fun addToCircle(context: Context, code: Int, tag: String, lookupKey: String, contactId: Long, days: Int): PendingIntent =
            pending(context, code, Intent(ACTION_ADD).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_KEY, lookupKey).putExtra(EXTRA_CONTACT_ID, contactId).putExtra(EXTRA_DAYS, days))

        fun fine(context: Context, code: Int, tag: String, lookupKey: String, contactId: Long): PendingIntent =
            pending(context, code, Intent(ACTION_FINE).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_KEY, lookupKey).putExtra(EXTRA_CONTACT_ID, contactId))

        fun wished(context: Context, code: Int, tag: String, lookupKey: String, contactId: Long, occasion: String): PendingIntent =
            pending(
                context,
                code,
                Intent(ACTION_WISHED).putExtra(EXTRA_TAG, tag).putExtra(
                    EXTRA_KEY, lookupKey,
                ).putExtra(EXTRA_CONTACT_ID, contactId).putExtra(EXTRA_OCCASION, occasion),
            )
    }
}
