package app.parley.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles call notification buttons (not exported). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISSED) {
            val nid = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
            if (nid != 0) CallNotifier.instance?.onDismissed(nid, intent.getStringExtra(EXTRA_ID))
            return
        }
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_ANSWER -> CallManager.answer(id)
            ACTION_DECLINE -> CallManager.reject(id)
            ACTION_IGNORE -> CallManager.ignore(id)
            ACTION_HANGUP -> CallManager.hangup(id)
            ACTION_MUTE -> CallManager.setMuted(!CallManager.audio.value.muted)
            ACTION_SPEAKER -> CallManager.toggleSpeaker()
            ACTION_EXTEND -> CallClock.extend(id, 5)
            ACTION_KEEP_GOING -> CallClock.keepGoing(id)
        }
    }

    companion object {
        const val ACTION_ANSWER = "app.parley.telecom.ANSWER"
        const val ACTION_DECLINE = "app.parley.telecom.DECLINE"
        const val ACTION_IGNORE = "app.parley.telecom.IGNORE"
        const val ACTION_HANGUP = "app.parley.telecom.HANGUP"
        const val ACTION_MUTE = "app.parley.telecom.MUTE"
        const val ACTION_SPEAKER = "app.parley.telecom.SPEAKER"
        const val ACTION_EXTEND = "app.parley.telecom.EXTEND"
        const val ACTION_KEEP_GOING = "app.parley.telecom.KEEP_GOING"
        /** A call notification was swiped away (its delete intent). */
        const val ACTION_DISMISSED = "app.parley.telecom.DISMISSED"
        const val EXTRA_ID = "call_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
