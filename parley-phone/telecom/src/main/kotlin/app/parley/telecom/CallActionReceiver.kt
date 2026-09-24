package app.parley.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles call notification buttons (not exported). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_ANSWER -> CallManager.answer(id)
            ACTION_DECLINE -> CallManager.reject(id)
            ACTION_IGNORE -> CallManager.ignore(id)
            ACTION_HANGUP -> CallManager.hangup(id)
            ACTION_MUTE -> CallManager.setMuted(!CallManager.audio.value.muted)
            ACTION_SPEAKER -> CallManager.toggleSpeaker()
        }
    }

    companion object {
        const val ACTION_ANSWER = "app.parley.telecom.ANSWER"
        const val ACTION_DECLINE = "app.parley.telecom.DECLINE"
        const val ACTION_IGNORE = "app.parley.telecom.IGNORE"
        const val ACTION_HANGUP = "app.parley.telecom.HANGUP"
        const val ACTION_MUTE = "app.parley.telecom.MUTE"
        const val ACTION_SPEAKER = "app.parley.telecom.SPEAKER"
        const val EXTRA_ID = "call_id"
    }
}
