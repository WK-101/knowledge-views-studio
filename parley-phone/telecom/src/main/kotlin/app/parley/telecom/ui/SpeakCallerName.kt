package app.parley.telecom.ui

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.parley.telecom.R

/**
 * X4: says "<name> is calling" a few times while a call rings. Android's on-device text-to-speech: nothing is
 * recorded and no microphone is involved. Only for contacts ([name] non-null), and only when the ringer is on and
 * Do Not Disturb isn't silencing calls, so a silent phone stays silent. Stops as soon as the call stops ringing.
 */
@Composable
fun SpeakCallerName(callId: String?, name: String?) {
    if (callId == null || name.isNullOrBlank()) return
    val context = LocalContext.current
    val text = stringResource(R.string.x_incall_is_calling, name)
    DisposableEffect(callId, text) {
        val app = context.applicationContext
        val audio = app.getSystemService(AudioManager::class.java)
        val nm = app.getSystemService(NotificationManager::class.java)
        val allowed = audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            (nm == null || nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL || nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        val handler = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        var said = 0
        val speak = object : Runnable {
            override fun run() {
                val t = tts ?: return
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "parley-caller-$callId-$said")
                if (++said < TIMES) handler.postDelayed(this, EVERY_MS)
            }
        }
        if (allowed) {
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    )
                    handler.postDelayed(speak, FIRST_MS)
                }
            }
        }
        onDispose {
            handler.removeCallbacksAndMessages(null)
            runCatching { tts?.stop(); tts?.shutdown() }
            tts = null
        }
    }
}

private const val FIRST_MS = 1_500L
private const val EVERY_MS = 6_000L
private const val TIMES = 3
