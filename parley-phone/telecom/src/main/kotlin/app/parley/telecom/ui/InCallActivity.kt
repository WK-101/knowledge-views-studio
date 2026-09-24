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
import app.parley.telecom.PostCallAction
import app.parley.telecom.TelecomGraph
import app.parley.ui.ParleyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.lifecycle.lifecycleScope

class InCallActivity : ComponentActivity() {
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        app.parley.ui.AppLocale.override(this, newBase)
    }

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
            LaunchedEffect(calls.isNotEmpty()) { if (calls.isNotEmpty()) keepEnded = false }
            val ringing = calls.any { it.state == CallState.RINGING }
            LaunchedEffect(ringing) {
                if (ringing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(calls.isEmpty(), keepEnded) {
                if (calls.isEmpty() && !keepEnded) {
                    // V4: the post-call card for an unknown number stays a little longer, and for good once touched.
                    delay(if (CallManager.lastEnded.value?.postCallCard == true) POST_CALL_CARD_MS else ENDED_MS)
                    if (CallManager.state.value.isEmpty() && !keepEnded) finishAndRemoveTask()
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
                    onPostCall = ::onPostCall,
                )
            }
        }
    }

    /** The call-ended screen stays up while the user uses the post-call card (V4). */
    private var keepEnded by mutableStateOf(false)

    private fun onPostCall(choice: PostCallChoice) {
        val deps = TelecomGraph.dependencies
        when (choice) {
            PostCallChoice.Touched -> keepEnded = true
            PostCallChoice.Done -> finishAndRemoveTask()
            is PostCallChoice.Block -> openApp { deps.postCallIntent(this, PostCallAction.BLOCK, choice.number) }
            is PostCallChoice.Report -> openApp { deps.postCallIntent(this, PostCallAction.REPORT, choice.number) }
            // Explicit intent into the app's "Message on…" sheet (this module can't depend on the app).
            is PostCallChoice.MessageOn -> openApp {
                Intent(ACTION_MESSAGE_ON).setClassName(packageName, MESSAGE_ON_ACTIVITY).putExtra("number", choice.number)
                    .apply { choice.accountId?.let { putExtra("account_id", it) } }
            }
            is PostCallChoice.SavePrivately -> unlockThen {
                lifecycleScope.launch {
                    val said = runCatching { deps.savePrivately(choice.number, choice.name) }.getOrNull()
                    Toast.makeText(this@InCallActivity, said ?: getString(app.parley.telecom.R.string.incall_save_failed), Toast.LENGTH_LONG).show()
                    if (said != null && CallManager.state.value.isEmpty()) finishAndRemoveTask()
                }
            }
        }
    }

    /** Unlocks, opens the app with [intent] and closes the call-ended screen. */
    private fun openApp(intent: () -> Intent?) = unlockThen {
        val i = intent() ?: return@unlockThen
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        if (CallManager.state.value.isEmpty()) finishAndRemoveTask()
    }

    override fun onStop() {
        super.onStop()
        // Left with the post-call card still up (home button, screen off): nothing more to show.
        if (keepEnded && CallManager.state.value.isEmpty() && !isChangingConfigurations) finishAndRemoveTask()
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
        private const val ENDED_MS = 1200L
        private const val POST_CALL_CARD_MS = 8000L
        private const val ACTION_MESSAGE_ON = "app.parley.action.MESSAGE_ON"
        private const val MESSAGE_ON_ACTIVITY = "app.parley.messaging.NumberActionActivity"
        private const val EXTRA_DIALPAD = "dialpad"
        fun intent(context: Context, dialpad: Boolean): Intent =
            Intent(context, InCallActivity::class.java).putExtra(EXTRA_DIALPAD, dialpad)
    }
}
