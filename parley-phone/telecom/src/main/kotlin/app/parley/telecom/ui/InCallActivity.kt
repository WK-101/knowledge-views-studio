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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Rational
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.parley.common.NotificationRequests
import app.parley.common.calls.CallWaiting
import app.parley.common.calls.LockScreenCaller
import app.parley.common.calls.RingKeys
import app.parley.telecom.AudioUi
import app.parley.telecom.CallActionReceiver
import app.parley.telecom.CallManager
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.telecom.DeclineBlock
import app.parley.telecom.InCallAppearance
import app.parley.telecom.PostCallAction
import app.parley.telecom.R
import app.parley.telecom.RescueCall
import app.parley.telecom.RescueState
import app.parley.telecom.TelecomGraph
import app.parley.telecom.forLockScreen
import app.parley.telecom.live
import app.parley.ui.ParleyTheme
import app.parley.ui.startOrSay
import app.parley.ui.systemMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class InCallActivity : ComponentActivity() {

    private var showDialpad by mutableStateOf(false)

    /** Shown as a picture-in-picture window. */
    private var inPip by mutableStateOf(false)

    /** Parley is opening one of its own screens (Add call, a contact): no PiP then, the Return to call chip leads back. */
    private var leavingForApp = false

    /** The call whose notification Decline was tapped with "Confirm before declining" on: ask here first. */
    private var askDeclineFor by mutableStateOf<String?>(null)

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
            val shownNow = collectShown()
            val (calls, audio, ended) = shownNow
            val declineBlock = shownNow.declineBlock
            val rescue = shownNow.rescue
            var keypad by remember { mutableStateOf(showDialpad) }
            // "Hide screen content" covers the call screen too.
            LaunchedEffect(look.secureScreen, look.loaded) { applySecure(look) }
            // The PiP actions (mute state) and whether leaving may enter PiP follow the calls.
            LaunchedEffect(calls, audio.muted) { updatePip() }
            // An outgoing call that didn't go through keeps the screen (reason and Retry) until dismissed. CallManager
            // drops the failure once it's dismissed or a newer call starts, so it's never stale.
            val failed = ended?.takeIf { e -> e.failure != null && calls.none { it.id == e.id && it.isLive } }
            LaunchedEffect(failed?.id) { if (failed != null && liveCalls().none { it.isLive }) keepEnded = true }
            // On the call-ended screen for that call, or above a call that goes on (call waiting, a second call).
            val blockedHere = declineBlock?.takeIf { b ->
                calls.none { it.id == b.callId && it.isLive } && (b.callId == ended?.id || calls.any { it.isLive })
            }
            LaunchedEffect(calls.isNotEmpty()) { if (calls.isNotEmpty()) keepEnded = false }
            HoldModeEffects(calls)
            val ringing = calls.any { it.state == CallState.RINGING }
            LaunchedEffect(ringing) {
                if (ringing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            LaunchedEffect(calls.isEmpty(), keepEnded) {
                if (calls.isEmpty() && !keepEnded) {
                    // The post-call card for an unknown number stays a little longer, and for good once touched.
                    val last = lastEnded()
                    // So do the "Blocked · Undo" card after Block & decline and "Did you cover these?".
                    val lingers = last?.postCallCard == true || last?.memoryCard == true ||
                        (last != null && CallManager.declineBlock.value?.callId == last.id) || CallAgendas.asksAfter(last)
                    // A dropped call keeps "Call again" at hand for a few seconds.
                    delay(if (last?.drop != null) DROPPED_MS else if (lingers && !inPip) POST_CALL_CARD_MS else ENDED_MS)
                    if (liveCalls().isEmpty() && !keepEnded) finishAndRemoveTask()
                }
            }
            // The caller's name, spoken while it rings (simple mode, when chosen). Never for a call waiting during
            // another call (Telecom doesn't ring then), and never once the ringer was silenced, here or with a key.
            val ringingCall = calls.firstOrNull { it.state == CallState.RINGING }
                ?.takeIf { r -> !r.silenced && !r.systemSilenced && calls.none { it.id != r.id && it.isLive && it.state != CallState.RINGING } }
            // Over the lock screen, the caller as Settings › Privacy & security › Caller on the lock screen allows.
            val locked = rememberKeyguardLocked()
            val incoming = stringResource(R.string.notif_incoming_call)
            val ongoing = stringResource(R.string.notif_ongoing_call)
            fun shown(c: CallUi) = if (!locked) c else c.forLockScreen(look.lockScreen, if (c.state == CallState.RINGING) incoming else ongoing)
            val shownCalls = remember(calls, locked, look.lockScreen) { calls.map(::shown) }
            val shownEnded = remember(ended, locked, look.lockScreen) { ended?.let(::shown) }
            if (look.speakCallerName) SpeakCallerName(ringingCall?.id, spokenName(ringingCall, shownCalls))
            ParleyTheme(look.themeMode, look.amoled, look.dynamicColor, look.density) {
                if (inPip) PipCallCard(shownCalls, audio, shownEnded, look.callBackground) else InCallScreen(
                    calls = shownCalls,
                    audio = audio,
                    ended = shownEnded,
                    answerGesture = look.answerGesture,
                    quickReplies = look.quickReplies,
                    keypadOpen = keypad || showDialpad,
                    onKeypad = { keypad = it; showDialpad = false },
                    // A rescue call isn't a call to add to.
                    onAddCall = { if (rescue == null) unlockThen { startOwnScreen(deps.mainIntent(this, dialpad = true)) } },
                    onOpenContact = { c -> unlockThen { startOwnScreen(deps.contactIntent(this, c.contactId, c.number)) } },
                    onPostCall = ::onPostCall,
                    failed = failed?.let(::shown),
                    onRetry = ::retry,
                    onDismissFailure = ::dismissFailure,
                    declineBlock = blockedHere?.let { masked(it, calls + listOfNotNull(ended), locked, look.lockScreen) },
                    onUndoBlock = {
                        keepEnded = true
                        CallManager.undoDeclineBlock()
                    },
                    simple = look.simpleMode,
                    confirmDecline = look.confirmDecline,
                    askDeclineFor = askDeclineFor,
                    onAskDeclineDone = { askDeclineFor = null },
                    background = look.callBackground,
                    onDrop = ::onDrop,
                    onSimTip = ::onSimTip,
                    onUnlock = ::unlockKeepingEnded,
                )
            }
        }
    }

    /** What the screen shows: the real calls, or a rescue call while no real call is up (a real one makes it give way). */
    private data class Shown(val calls: List<CallUi>, val audio: AudioUi, val ended: CallUi?, val declineBlock: DeclineBlock?, val rescue: RescueState?)

    @Composable
    private fun collectShown(): Shown {
        val realCalls by CallManager.state.collectAsStateWithLifecycle()
        val realAudio by CallManager.audio.collectAsStateWithLifecycle()
        val realEnded by CallManager.lastEnded.collectAsStateWithLifecycle()
        val realBlock by CallManager.declineBlock.collectAsStateWithLifecycle()
        val rescueState by RescueCall.state.collectAsStateWithLifecycle()
        val rescue = rescueState?.takeIf { realCalls.isEmpty() } ?: return Shown(realCalls, realAudio, realEnded, realBlock, null)
        return Shown(listOfNotNull(rescue.call), rescue.audio, rescue.ended, null, rescue)
    }

    /** The calls this screen shows: Telecom's, else a rescue call's. */
    private fun liveCalls(): List<CallUi> = CallManager.state.value.ifEmpty { listOfNotNull(RescueCall.state.value?.call) }

    /**
     * A volume key silences a ringing call ([RingKeys]). Android does this before the key reaches any app on most
     * phones; on those where the call screen gets the key instead, it is done here, so the key never just changes the
     * volume while the phone rings on.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val live = liveCalls().filter { it.isLive }
        val ringing = live.firstOrNull { it.state == CallState.RINGING }
        if (ringing != null) {
            val other = live.any { it.id != ringing.id && it.state != CallState.RINGING }
            val silenced = ringing.silenced || ringing.systemSilenced
            if (RingKeys.silences(keyCode, down = true, ringing = true, silenced = silenced, otherCall = other)) {
                CallManager.ignore(ringing.id)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** The call that just ended, of the kind this screen shows. */
    private fun lastEnded(): CallUi? =
        if (CallManager.state.value.isEmpty() && RescueCall.state.value != null) RescueCall.state.value?.ended else CallManager.lastEnded.value

    /** The ringing contact's name to speak: none while the call is masked on the lock screen. */
    private fun spokenName(ringing: CallUi?, shown: List<CallUi>): String? =
        ringing?.takeIf { r -> r.contactId != null && shown.none { it.id == r.id && it.lockMasked } }?.name

    /**
     * "Blocked and declined" names the number only when its call isn't masked on the lock screen; a call no longer
     * known counts as a saved caller, to be safe.
     */
    private fun masked(block: DeclineBlock, calls: List<CallUi>, locked: Boolean, mode: LockScreenCaller): DeclineBlock =
        if (locked && mode.masks(calls.firstOrNull { it.id == block.callId }?.savedCaller ?: true)) block.copy(masked = true) else block

    /** Opens one of Parley's own screens without turning the call screen into a PiP window. */
    private fun startOwnScreen(intent: Intent) {
        leavingForApp = true
        updatePip()
        startOrSay(intent)
    }

    /** The failure banner dismissed: the screen closes when no call is left. */
    private fun dismissFailure(c: CallUi) {
        CallManager.dismissFailure(c.id)
        if (CallManager.state.value.none { it.isLive }) finishAndRemoveTask()
    }

    /** Retry on the failure banner: the same number, on the same SIM. */
    private fun retry(c: CallUi) {
        val number = c.number ?: return
        CallManager.dismissFailure(c.id)
        lifecycleScope.launch {
            val problem = CallManager.redial(number, c.accountId)
            if (problem != null) {
                CallManager.restoreFailure(c)
                systemMessage(this@InCallActivity, problem, long = true)
            } else {
                // The new call opens this screen again; if it never comes, the screen closes as usual.
                keepEnded = false
            }
        }
    }

    /** Hold mode dims the screen (the call goes on the speaker, the phone can lie on the table). */
    @Composable
    private fun HoldModeEffects(calls: List<CallUi>) {
        val holdMode = calls.any { it.isLive && it.holdModeSince > 0 }
        LaunchedEffect(holdMode, inPip) { dim(holdMode && !inPip) }
        LaunchedEffect(holdMode) { updatePip() }
    }

    /** Unlocks for the saved-number sheet; on the call-ended screen, it stays up meanwhile. */
    private fun unlockKeepingEnded(block: () -> Unit) {
        if (CallManager.state.value.none { it.isLive }) keepEnded = true
        unlockThen(block)
    }

    /** The "Call dropped" card: Call again ([again]) or dismissed. */
    private fun onDrop(c: CallUi, again: Boolean) {
        if (again) {
            callAgain(c)
        } else {
            CallManager.dismissDrop(c.id)
            if (CallManager.state.value.none { it.isLive }) finishAndRemoveTask()
        }
    }

    /** The SIM suggestion on the "Call dropped" card: remembering a SIM for someone waits for the unlock. */
    private fun onSimTip(c: CallUi, accept: Boolean) {
        if (accept) unlockKeepingEnded { CallManager.answerSimTip(c.id, true) } else CallManager.answerSimTip(c.id, false)
    }

    /** "Call again" after a drop: the same number, on the same SIM. */
    private fun callAgain(c: CallUi) {
        val number = c.number ?: return
        CallManager.dismissDrop(c.id)
        keepEnded = true
        lifecycleScope.launch {
            val problem = CallManager.redial(number, c.accountId)
            if (problem != null) systemMessage(this@InCallActivity, problem, long = true)
            // The new call opens this screen again; if it never comes, the screen closes as usual.
            keepEnded = false
        }
    }

    /** Hold mode's dim screen: the lowest comfortable brightness for this window only; back to normal after. */
    private fun dim(on: Boolean) {
        val lp = window.attributes
        val target = if (on) HOLD_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (lp.screenBrightness == target) return
        lp.screenBrightness = target
        window.attributes = lp
    }

    /** Like AppLock.applySecureFlag. Until the stored settings are read, the screen stays secure. */
    private fun applySecure(look: InCallAppearance) {
        if (!look.loaded || look.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ---- Picture-in-picture ----

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
            if (muted) R.drawable.ic_pip_mic_off else R.drawable.ic_pip_mic,
            getString(if (muted) R.string.notif_unmute else R.string.notif_mute),
            CallActionReceiver.ACTION_MUTE, NotificationRequests.PIP_MUTE,
        ).apply { isEnabled = call.canMute }
        val hangUp = action(R.drawable.ic_tile_hangup, getString(R.string.tile_end_call), CallActionReceiver.ACTION_HANGUP, NotificationRequests.PIP_HANG_UP)
        // In hold mode the window offers the way out ("They're back") first.
        val holdEnd = if (call.holdModeSince > 0) {
            action(R.drawable.ic_pip_hold_end, getString(R.string.holdmode_end), CallActionReceiver.ACTION_HOLD_MODE_END, NotificationRequests.PIP_HOLD_END)
        } else {
            null
        }
        // Android always shows at least three actions.
        return listOfNotNull(holdEnd, mute, hangUp)
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

    /** The call-ended screen stays up while the user uses the post-call card. */
    private var keepEnded by mutableStateOf(false)

    @Suppress("CyclomaticComplexMethod") // One branch per post-call action.
    private fun onPostCall(choice: PostCallChoice) {
        val deps = TelecomGraph.dependencies
        when (choice) {
            // Verify is handled on the call screen itself (the saved-number sheet); touching it keeps the screen up.
            PostCallChoice.Touched, is PostCallChoice.Verify, PostCallChoice.ScamCheck -> keepEnded = true
            PostCallChoice.Done -> finishAndRemoveTask()
            is PostCallChoice.Block -> openApp { deps.postCallIntent(this, PostCallAction.BLOCK, choice.number) }
            is PostCallChoice.Unblock -> openApp { deps.postCallIntent(this, PostCallAction.UNBLOCK, choice.number) }
            is PostCallChoice.Save -> openApp { deps.postCallIntent(this, PostCallAction.SAVE, choice.number, choice.name) }
            is PostCallChoice.AddToContact -> openApp { deps.postCallIntent(this, PostCallAction.ADD_TO_CONTACT, choice.number) }
            is PostCallChoice.Report -> openApp { deps.postCallIntent(this, PostCallAction.REPORT, choice.number) }
            is PostCallChoice.NumberMemory -> openApp { deps.postCallIntent(this, PostCallAction.NUMBER_MEMORY, choice.number) }
            // The messaging app, with the text ready for the user to send (Parley sends nothing itself).
            is PostCallChoice.NameReply -> openApp {
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", choice.number, null)).putExtra("sms_body", choice.text)
            }
            // Explicit intent into the app's "Message on…" sheet (this module can't depend on the app).
            is PostCallChoice.MessageOn -> openApp {
                Intent(ACTION_MESSAGE_ON).setClassName(packageName, MESSAGE_ON_ACTIVITY).putExtra("number", choice.number)
                    .apply { choice.accountId?.let { putExtra("account_id", it) } }
            }
            // Saved without unlocking (like a note during the call); nothing is shown back.
            is PostCallChoice.Remember -> {
                runCatching { deps.rememberAfterCall(choice.number, choice.connectTimeMillis, choice.note, choice.followUpDays) }
                systemMessage(this, getString(R.string.memory_saved))
                if (CallManager.state.value.isEmpty()) finishAndRemoveTask()
            }
            is PostCallChoice.SavePrivately -> unlockThen {
                lifecycleScope.launch {
                    val said = runCatching { deps.savePrivately(choice.number, choice.name) }.getOrNull()
                    systemMessage(this@InCallActivity, said ?: getString(R.string.incall_save_failed), long = true)
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
        // Decline from the notification with "Confirm before declining" on: the question, on this screen.
        if (intent?.action == ACTION_ASK_DECLINE) {
            askDeclineFor = intent.getStringExtra(CallActionReceiver.EXTRA_ID)
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
        const val ACTION_ASK_DECLINE = "app.parley.telecom.ui.ASK_DECLINE"
        private const val ENDED_MS = 1200L
        private const val POST_CALL_CARD_MS = 8000L
        private const val DROPPED_MS = 10_000L
        private const val HOLD_BRIGHTNESS = 0.05f
        private const val ACTION_MESSAGE_ON = "app.parley.action.MESSAGE_ON"
        private const val MESSAGE_ON_ACTIVITY = "app.parley.messaging.NumberActionActivity"
        private const val EXTRA_DIALPAD = "dialpad"
        fun intent(context: Context, dialpad: Boolean): Intent =
            Intent(context, InCallActivity::class.java).putExtra(EXTRA_DIALPAD, dialpad)
    }
}
