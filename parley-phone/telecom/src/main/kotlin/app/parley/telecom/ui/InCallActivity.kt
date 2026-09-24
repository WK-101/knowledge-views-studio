package app.parley.telecom.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.telecom.CallActionReceiver
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.TelecomGraph
import app.parley.ui.ParleyTheme
import kotlinx.coroutines.delay

class InCallActivity : ComponentActivity() {
    private var showDialpad by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Only act on a fresh launch: a re-created activity (or one opened from Recents) must never
        // replay an old "answer" action onto a different, newer call.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handleIntent(intent)
        val deps = TelecomGraph.dependencies
        setContent {
            val look by deps.appearance.collectAsStateWithLifecycle()
            val calls by CallManager.state.collectAsStateWithLifecycle()
            val audio by CallManager.audio.collectAsStateWithLifecycle()
            val ended by CallManager.lastEnded.collectAsStateWithLifecycle()
            var keypad by remember { mutableStateOf(showDialpad) }
            val ringing = calls.any { it.state == CallState.RINGING }
            LaunchedEffect(ringing) {
                if (ringing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(calls.isEmpty()) {
                if (calls.isEmpty()) {
                    delay(1200)
                    if (CallManager.state.value.isEmpty()) finishAndRemoveTask()
                }
            }
            ParleyTheme(look.themeMode, look.amoled, look.dynamicColor, look.density) {
                InCallScreen(
                    calls = calls,
                    audio = audio,
                    ended = ended,
                    answerGesture = look.answerGesture,
                    quickReplies = look.quickReplies,
                    keypadOpen = keypad || showDialpad,
                    onKeypad = { keypad = it; showDialpad = false },
                    onAddCall = { unlockThen { startActivity(deps.mainIntent(this, dialpad = true)) } },
                    onOpenContact = { c -> unlockThen { startActivity(deps.contactIntent(this, c.contactId, c.number)) } },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_DIALPAD, false) == true) showDialpad = true
        if (intent?.action == ACTION_ANSWER) {
            intent.getStringExtra(CallActionReceiver.EXTRA_ID)?.let { CallManager.answer(it) }
            setIntent(Intent(intent).setAction(null))
        }
    }

    override fun onResume() {
        super.onResume()
        CallManager.setUiVisible(true)
    }

    override fun onPause() {
        CallManager.setUiVisible(false)
        super.onPause()
    }

    private fun unlockThen(block: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = block()
            })
        } else {
            block()
        }
    }

    companion object {
        const val ACTION_ANSWER = "app.parley.telecom.ui.ANSWER"
        private const val EXTRA_DIALPAD = "dialpad"
        fun intent(context: Context, dialpad: Boolean): Intent =
            Intent(context, InCallActivity::class.java).putExtra(EXTRA_DIALPAD, dialpad)
    }
}
