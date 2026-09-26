package app.parley.telecom.ui

import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Rational
import android.view.Display
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
import app.parley.telecom.CallUi
import app.parley.telecom.InCallAppearance
import app.parley.telecom.live
import app.parley.telecom.PostCallAction
import app.parley.telecom.TelecomGraph
import app.parley.ui.ParleyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.parley.common.calls.CallWaiting

class InCallActivity : ComponentActivity() {
    // L1: the in-app language on Android 10-12 (Android 13+ applies per-app languages itself).
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        app.parley.ui.AppLocale.override(this, newBase)
    }

    private var showDialpad by mutableStateOf(false)

    /** P1: shown as a picture-in-picture window. */
    private var inPip by mutableStateOf(false)

    /** P1: Parley is opening one of its own screens (Add call, a contact): no PiP then, the Return to call chip leads back. */
    private var leavingForApp = false

    /** P6: the ended call whose failure banner the user dismissed (or retried). */
    private var failureDismissed by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        applySecure(TelecomGraph.dependencies.appearance.value)
        // Only act on a fresh launch: a re-created activity (or one opened from Recents) must never
        // replay an old "answer" action onto a different, newer call.
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) handleIntent(intent)
        val deps = TelecomGraph.dependencies
        setContent {
            val look by deps.appearance.collectAsStateWithLifecycle()
            val calls by CallManager.state.collectAsStateWithLifecycle()
            val audio by CallManager.audio.collectAsStateWithLifecycle()
            val ended by CallManager.lastEnded.collectAsStateWithLifecycle()
            val declineBlock by CallManager.declineBlock.collectAsStateWithLifecycle()
            var keypad by remember { mutableStateOf(showDialpad) }
            // G3/P3: "Hide screen content" covers the call screen too.
            LaunchedEffect(look.secureScreen, look.loaded) { applySecure(look) }
            // P1: the PiP actions (mute state) and whether leaving may enter PiP follow the calls.
            LaunchedEffect(calls, audio.muted) { updatePip() }
            // P6: an outgoing call that didn't go through keeps the screen (reason and Retry) until dismissed.
            val failed = ended?.takeIf { e -> e.failure != null && e.id != failureDismissed && calls.none { it.id == e.id && it.isLive } }
            LaunchedEffect(failed?.id) { if (failed != null && CallManager.state.value.none { it.isLive }) keepEnded = true }
            val blockedHere = declineBlock?.takeIf { it.callId == ended?.id }
            LaunchedEffect(calls.isNotEmpty()) { if (calls.isNotEmpty()) keepEnded = false }
            val ringing = calls.any { it.state == CallState.RINGING }
            LaunchedEffect(ringing) {
                if (ringing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(calls.isEmpty(), keepEnded) {
                if (calls.isEmpty() && !keepEnded) {
                    // V4: the post-call card for an unknown number stays a little longer, and for good once touched.
                    val last = CallManager.lastEnded.value
                    // P2: so does the "Blocked · Undo" card after Block & decline.
                    val lingers = last?.postCallCard == true || last?.memoryCard == true || (last != null && CallManager.declineBlock.value?.callId == last.id)
                    delay(if (lingers && !inPip) POST_CALL_CARD_MS else ENDED_MS)
                    if (CallManager.state.value.isEmpty() && !keepEnded) finishAndRemoveTask()
                }
            }
            // X4: the caller's name, spoken while it rings (simple mode, when chosen).
            val ringingCall = calls.firstOrNull { it.state == CallState.RINGING }
            if (look.speakCallerName) SpeakCallerName(ringingCall?.id?.takeIf { !ringingCall.silenced }, ringingCall?.name?.takeIf { ringingCall.contactId != null })
            ParleyTheme(look.themeMode, look.amoled, look.dynamicColor, look.density) {
                if (inPip) PipCallCard(calls, audio, ended) else InCallScreen(
                    calls = calls,
                    audio = audio,
                    ended = ended,
                    answerGesture = look.answerGesture,
                    quickReplies = look.quickReplies,
                    keypadOpen = keypad || showDialpad,
                    onKeypad = { keypad = it; showDialpad = false },
                    onAddCall = { unlockThen { startOwnScreen(deps.mainIntent(this, dialpad = true)) } },
                    onOpenContact = { c -> unlockThen { startOwnScreen(deps.contactIntent(this, c.contactId, c.number)) } },
                    onPostCall = ::onPostCall,
                    failed = failed,
                    onRetry = ::retry,
                    onDismissFailure = { c ->
                        failureDismissed = c.id
                        if (CallManager.state.value.none { it.isLive }) finishAndRemoveTask()
                    },
                    declineBlock = blockedHere,
                    onUndoBlock = {
                        keepEnded = true
                        CallManager.undoDeclineBlock()
                    },
                    simple = look.simpleMode,
                    confirmDecline = look.confirmDecline,
                )
            }
        }
    }

    /** P1: opens one of Parley's own screens without turning the call screen into a PiP window. */
    private fun startOwnScreen(intent: Intent) {
        leavingForApp = true
        updatePip()
        runCatching { startActivity(intent) }
    }

    /** P6: Retry on the failure banner: the same number, on the same SIM. */
    private fun retry(c: CallUi) {
        val number = c.number ?: return
        failureDismissed = c.id
        lifecycleScope.launch {
            val problem = CallManager.redial(number, c.accountId)
            if (problem != null) {
                failureDismissed = null
                Toast.makeText(this@InCallActivity, problem, Toast.LENGTH_LONG).show()
            } else {
                // The new call opens this screen again; if it never comes, the screen closes as usual.
                keepEnded = false
            }
        }
    }

    /** G3/P3: like AppLock.applySecureFlag. Until the stored settings are read, the screen stays secure. */
    private fun applySecure(look: InCallAppearance) {
        if (!look.loaded || look.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ---- P1: picture-in-picture ----

    private fun pipSupported(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Never for a ringing call or the SIM picker, never while Parley opens its own screens. */
    private fun pipAllowed(): Boolean {
        if (!pipSupported() || leavingForApp || isFinishing) return false
        val live = CallManager.state.value.filter { it.isLive }
        return CallWaiting.pipAllowed(live, { it.state.live() }, { it.state == CallState.SELECT_ACCOUNT })
    }

    /** False while the proximity sensor has turned the screen off at the ear. */
    private fun screenOn(): Boolean {
        if (getSystemService(PowerManager::class.java)?.isInteractive == false) return false
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return display == null || display.state == Display.STATE_ON
    }

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).setActions(pipActions())
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(pipAllowed()).setSeamlessResizeEnabled(false)
        return b.build()
    }

    /** Mute and Hang up in the PiP window, through the same receiver as the call notification. */
    private fun pipActions(): List<RemoteAction> {
        val live = CallManager.state.value.filter { it.isLive }
        val call = live.firstOrNull { it.state == CallState.ACTIVE } ?: live.firstOrNull() ?: return emptyList()
        val muted = CallManager.audio.value.muted
        fun action(icon: Int, title: String, action: String, req: Int) = RemoteAction(
            Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(
                this, req,
                Intent(this, CallActionReceiver::class.java).setAction(action).putExtra(CallActionReceiver.EXTRA_ID, call.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val mute = action(
            if (muted) app.parley.telecom.R.drawable.ic_pip_mic_off else app.parley.telecom.R.drawable.ic_pip_mic,
            getString(if (muted) app.parley.telecom.R.string.incall_unmute else app.parley.telecom.R.string.incall_mute),
            CallActionReceiver.ACTION_MUTE, PIP_MUTE_REQUEST,
        ).apply { isEnabled = call.canMute }
        val hangUp = action(app.parley.telecom.R.drawable.ic_tile_hangup, getString(app.parley.telecom.R.string.incall_end_call), CallActionReceiver.ACTION_HANGUP, PIP_HANGUP_REQUEST)
        return listOf(mute, hangUp)
    }

    private fun updatePip() {
        if (pipSupported()) runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Android 10 and 11 have no automatic PiP: enter it when the user leaves (Home, Recents). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pipAllowed() && screenOn()) runCatching { enterPictureInPictureMode(pipParams()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
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
            // R8: saved without unlocking (like a note during the call); nothing is shown back.
            is PostCallChoice.Remember -> {
                runCatching { deps.rememberAfterCall(choice.number, choice.connectTimeMillis, choice.note, choice.followUpDays) }
                Toast.makeText(this, getString(app.parley.telecom.R.string.memory_saved), Toast.LENGTH_SHORT).show()
                if (CallManager.state.value.isEmpty()) finishAndRemoveTask()
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
        startOwnScreen(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        leavingForApp = false
        applySecure(TelecomGraph.dependencies.appearance.value)
        updatePip()
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
        private const val PIP_MUTE_REQUEST = 40
        private const val PIP_HANGUP_REQUEST = 41
        fun intent(context: Context, dialpad: Boolean): Intent =
            Intent(context, InCallActivity::class.java).putExtra(EXTRA_DIALPAD, dialpad)
    }
}
