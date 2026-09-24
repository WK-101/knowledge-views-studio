package app.parley

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import app.parley.calls.MissedCallNotifier
import app.parley.data.PlaceResult
import kotlinx.coroutines.launch

/** Shows our own missed-call notifications (Telecom delegates them to the default dialer). See [MissedCallNotifier]. */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val pending = goAsync()
        context.container.scope.launch {
            try {
                MissedCallNotifier.show(context, count, number)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val CHANNEL = MissedCallNotifier.CHANNEL
        const val ID = MissedCallNotifier.ID
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
                val nm = context.getSystemService(NotificationManager::class.java)
                when (intent.action) {
                    ACTION_CALL_BACK -> {
                        intent.getStringExtra("number")?.let { c.placer.call(it) as PlaceResult }
                        MissedCallNotifier.cancelAll(context)
                        seen(context)
                    }
                    // V2: block from the notification (only after unlocking, see MissedCallNotifier.blockAction).
                    ACTION_BLOCK -> {
                        intent.getStringExtra("number")?.takeIf { it.isNotBlank() }?.let { n ->
                            if (!c.blocks.blockNumber(n)) app.parley.blocking.BlockingActions.blockNumberRule(c, n)
                        }
                        nm.cancel(intent.getIntExtra(EXTRA_ID, MissedCallNotifier.ID))
                        if (!MissedCallNotifier.anyShowing(context, childrenOnly = true)) {
                            MissedCallNotifier.cancelAll(context)
                            seen(context)
                        }
                    }
                    // One caller's notification was swiped away: once none is left, they've all been seen.
                    ACTION_DISMISSED_ONE -> if (!MissedCallNotifier.anyShowing(context, childrenOnly = true)) {
                        MissedCallNotifier.cancelAll(context)
                        seen(context)
                    }
                    else -> seen(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Marks missed calls seen: clears Telecom's count and stops the re-alert (V3). */
    private suspend fun seen(context: Context) {
        MissedCallNotifier.stopReAlert(context)
        context.container.callLog.markMissedRead()
        try {
            context.getSystemService(TelecomManager::class.java).cancelMissedCallsNotification()
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val ACTION_CALL_BACK = "app.parley.CALL_BACK"
        const val ACTION_CLEAR = "app.parley.CLEAR_MISSED"
        const val ACTION_BLOCK = "app.parley.BLOCK_MISSED"
        const val ACTION_DISMISSED_ONE = "app.parley.DISMISSED_ONE_MISSED"
        const val EXTRA_ID = "notification_id"
    }
}
