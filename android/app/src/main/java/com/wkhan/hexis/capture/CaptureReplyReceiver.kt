package com.wkhan.hexis.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.app.RemoteInput
import android.content.Intent
import android.widget.Toast
import com.wkhan.hexis.App
import com.wkhan.hexis.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Receives the inline reply from [QuickReplyNotification] and adds it as a task via the repository's
 * headless quick-capture (same natural-language parsing as the widget popup and app shortcuts), then
 * re-posts the notification so its reply field resets and stays available. Fully offline; no mic
 * permission — the dictation was the system keyboard's.
 */
class CaptureReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QuickReplyNotification.ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(QuickReplyNotification.KEY_TEXT)?.toString()?.trim()
        val app = context.applicationContext as App
        val pending = goAsync()
        app.appScope.launch {
            try {
                if (!text.isNullOrBlank()) {
                    app.repository.quickCaptureTask(text)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(app, app.getString(R.string.quick_reply_added), Toast.LENGTH_SHORT).show()
                    }
                }
                // Refresh so the system clears the "sending…" state and the reply field is ready again.
                QuickReplyNotification.post(app)
            } finally {
                pending.finish()
            }
        }
    }
}
