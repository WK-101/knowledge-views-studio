package app.parley.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.parley.container
import kotlinx.coroutines.launch

/**
 * Reminder notification actions that need no screen (R4 "Not now", R5 "Mark as wished"). Not exported; the pending
 * intents are explicit and immutable.
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
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_KEY = "lookup_key"
        private const val EXTRA_CONTACT_ID = "contact_id"
        private const val EXTRA_OCCASION = "occasion"

        private fun pending(context: Context, code: Int, intent: Intent): PendingIntent =
            PendingIntent.getBroadcast(context, code, intent.setClass(context, CircleActionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun notNow(context: Context, code: Int, tag: String, lookupKey: String): PendingIntent =
            pending(context, code, Intent(ACTION_NOT_NOW).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_KEY, lookupKey))

        fun wished(context: Context, code: Int, tag: String, lookupKey: String, contactId: Long, occasion: String): PendingIntent =
            pending(context, code, Intent(ACTION_WISHED).putExtra(EXTRA_TAG, tag).putExtra(EXTRA_KEY, lookupKey).putExtra(EXTRA_CONTACT_ID, contactId).putExtra(EXTRA_OCCASION, occasion))
    }
}
