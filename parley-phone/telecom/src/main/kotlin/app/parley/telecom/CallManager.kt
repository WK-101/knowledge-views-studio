package app.parley.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.telecom.Call
import androidx.annotation.VisibleForTesting
import app.parley.common.BlockReason
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import app.parley.common.BlockAction
import app.parley.common.Decision
import app.parley.common.RangThrough
import app.parley.common.RangThroughKind
import app.parley.common.Verification
import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.SelfSilenceEcho
import app.parley.common.calls.CallerHaptics
import app.parley.common.calls.CallBook
import app.parley.common.calls.CallDrop
import app.parley.common.calls.CallQualityCodec
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.CallSubject
import app.parley.common.calls.DropFacts
import app.parley.common.calls.DropKind
import app.parley.common.calls.HoldMode
import app.parley.common.calls.CallFailure
import app.parley.common.calls.CallHandOff
import app.parley.common.calls.SpeakerDefault
import app.parley.common.calls.SpeakerOnStart
import app.parley.common.calls.DriveProfile
import app.parley.common.calls.EmergencyPolicy
import app.parley.common.calls.EmergencyPolicy.Safeguard
import app.parley.common.calls.EndCode
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.EndFacts
import app.parley.common.calls.FailureKind
import app.parley.common.calls.KeyPressTracker
import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuPress
import app.parley.common.calls.MenuStep
import app.parley.common.calls.RingEnd
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingtoneSource
import app.parley.common.calltime.CallHaptic
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single source of truth for live calls. Fed by [ParleyInCallService] on the main thread and observed by the in-call
 * UI, notifications and the main app's "return to call" banner.
 *
 * What it knows about each call lives in one [CallSession], dropped as a whole when the call leaves Telecom. The
 * work around a call is done by collaborators: [ScreeningCoordinator] (the verdict), [CallRinger] (Parley's own
 * tone and "Ring loud"), [CallLimitsGate] (allowances and limits), [NotifierBridge] (observers) and the pure
 * [CallBook] (hold timers, which held call to resume).
 */
// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
object CallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val calls = ArrayList<Call>()
    private val ids = WeakHashMap<Call, String>()
    private var counter = 0

    /** One session per call id, for every call Telecom reported (children of a conference too). */
    private val sessions = HashMap<String, CallSession>()

    private val deps: TelecomDependencies get() = TelecomGraph.dependencies
    private val ringer = CallRinger(scope) { silenceRinger() }
    private val screening = ScreeningCoordinator(scope) { deps }
    private val limits = CallLimitsGate(scope) { deps }
    private val autoAnswer = AutoAnswerGate(scope) { deps.autoAnswer() }
    private val drive = DriveGate { deps.driveProfile() }
    private val notifier = NotifierBridge()

    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val state: StateFlow<List<CallUi>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow(AudioUi())
    val audio: StateFlow<AudioUi> = _audio.asStateFlow()

    /** Last call that ended, for the brief "Call ended" screen. */
    private val _lastEnded = MutableStateFlow<CallUi?>(null)
    val lastEnded: StateFlow<CallUi?> = _lastEnded.asStateFlow()

    /** A call Parley just asked Telecom to place, until it shows up. */
    private val _pendingOutgoing = MutableStateFlow<PendingOutgoing?>(null)
    val pendingOutgoing: StateFlow<PendingOutgoing?> = _pendingOutgoing.asStateFlow()

    /** Hold timers, last live states and which held call to resume (lazy: the state sets are declared below). */
    private val book by lazy { CallBook(CallState.HOLDING, FRONT_STATES, BUSY_STATES, setOf(CallState.DISCONNECTING, CallState.DISCONNECTED)) }

    /** The last call declined with "Block & decline", for Undo on the call-ended screen. */
    private val _declineBlock = MutableStateFlow<DeclineBlock?>(null)
    val declineBlock: StateFlow<DeclineBlock?> = _declineBlock.asStateFlow()

    internal var service: ParleyInCallService? = null

    /** Whether the in-call screen is in the foreground (proximity screen-off only applies then). */
    val uiVisible: Boolean get() = notifier.uiVisible

    fun setUiVisible(visible: Boolean) {
        notifier.uiVisible = visible
        notifier.send(_calls.value)
    }

    internal var onChanged: ((List<CallUi>) -> Unit)?
        get() = notifier.onChanged
        set(value) {
            notifier.onChanged = value
        }

    private lateinit var appContext: Context

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            // I11: the caller's name is never said over a call that stopped ringing.
            if (state != Call.STATE_RINGING) drive.quiet(idOf(call))
            checkMenuReplay()
            publish()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            checkMenuReplay()
            publish()
        }
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) {
            checkMenuReplay()
            publish()
        }
        override fun onParentChanged(call: Call, parent: Call?) {
            checkMenuReplay()
            publish()
        }
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = publish()
        override fun onPostDialWait(call: Call, remainingPostDialSequence: String?) {
            // One callback per call, unregistered in remove()/clear() (a second, anonymous callback leaked).
            session(idOf(call)).postDial = remainingPostDialSequence?.takeIf { it.isNotEmpty() }
            publish()
        }
    }

    private val idPrefix = "c" + SystemClock.elapsedRealtime().toString(36) + "_"

    fun idOf(call: Call): String = ids.getOrPut(call) { idPrefix + (++counter) }

    private fun session(id: String): CallSession = sessions.getOrPut(id) { CallSession(id) }

    private fun callOf(session: CallSession): Call? = calls.firstOrNull { idOf(it) == session.id }

    private fun ringing(call: Call): Boolean = calls.contains(call) && call.stateCompat() == Call.STATE_RINGING

    /** What the screening verdict does to a call. */
    private val screeningHost = object : ScreeningCoordinator.Host {
        override fun stillPresent(session: CallSession) = callOf(session) != null
        override fun rejectUnwanted(session: CallSession) {
            val call = callOf(session) ?: return
            // A private contact's "Send to voicemail" is a plain decline (the network sends them to voicemail), not "unwanted".
            if ((session.outcome?.decision as? Decision.Block)?.reason == BlockReason.SEND_TO_VOICEMAIL) call.reject(false, null) else rejectUnwanted(call)
        }
        override fun silence(session: CallSession) {
            session.silenced = true
            silenceRinger()
        }
        override fun ringLoud(session: CallSession) {
            val call = callOf(session) ?: return
            if (!ringing(call)) return
            ringer.boost(appContext, session.id)
            session.loud = true
        }
        override fun playTone(session: CallSession) {
            callOf(session)?.let { maybePlayUnknownRingtone(it, session) }
        }
        override fun changed() {
            // Screening has answered: a known caller's call may now be armed for auto-answer.
            calls.firstOrNull { sessions[idOf(it)]?.screening == false && ringing(it) }?.let { c ->
                considerAutoAnswer(c)
                // I11: a contact whose name waited for the verdict.
                val s = session(idOf(c))
                s.info?.let { if (::appContext.isInitialized) announceInCar(c, s, it) }
            }
            publish()
        }
    }

    /** What auto-answer needs to know about a ringing call, asked when it is armed and again at its deadline. */
    private val autoAnswerHost = object : AutoAnswerGate.Host {
        override fun facts(session: CallSession): AutoAnswer.Facts? {
            val call = callOf(session)?.takeIf { ringing(it) } ?: return null
            val number = call.details.handle?.schemeSpecificPart
            val o = session.outcome
            return AutoAnswer.Facts(
                knownCaller = session.info != null && !session.unknownCaller,
                hidden = call.details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || number.isNullOrBlank(),
                blockedOrSpam = session.silenced || o?.decision is Decision.Block || o?.warn == true,
                otherCall = calls.any { it != call && it.parent == null && mapState(it.stateCompat()) != CallState.DISCONNECTED },
                emergency = isEmergencyCall(call, number),
                headsetConnected = AutoAnswerGate.headsetConnected(appContext),
                simpleMode = runCatching { deps.appearance.value.simpleMode }.getOrDefault(false),
                chosen = session.info?.autoAnswerChosen == true,
                favourite = session.info?.favourite == true,
                drive = drive.answerScope(appContext),
            )
        }
        override fun driving(): Boolean = ::appContext.isInitialized && drive.answerScope(appContext) != null
        override fun answer(session: CallSession) = answer(session.id)
        override fun changed() = publish()
    }

    private fun considerAutoAnswer(call: Call) {
        if (!::appContext.isInitialized) return
        autoAnswer.consider(session(idOf(call)), autoAnswerHost)
    }

    /** Cancel on the call screen's countdown: the call rings on as usual. */
    fun cancelAutoAnswer(id: String) {
        sessions[id]?.let { autoAnswer.cancel(it) }
        publish()
    }

    internal fun add(context: Context, call: Call) {
        appContext = context.applicationContext
        val id = idOf(call)
        val s = session(id)
        // A new call starts: an earlier call's failure banner (and its Retry) or "Blocked · Undo" card is stale.
        _lastEnded.value = null
        _declineBlock.value?.let { b -> if (calls.none { idOf(it) == b.callId }) _declineBlock.value = null }
        calls += call
        call.registerCallback(callback)
        // Never answer a call on its own while another one exists.
        if (calls.size > 1) autoAnswer.cancelAll(sessions.values)

        val number = call.details.handle?.schemeSpecificPart
        val hidden = call.details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val incoming = call.stateCompat() == Call.STATE_RINGING
        s.startedAt = System.currentTimeMillis()
        if (calls.size == 1) RingBoost.restoreAsync(appContext) // a boost left behind by a crash
        if (incoming) {
            notifier.traceIncoming(s)
            val now = System.currentTimeMillis()
            s.ringStartedAt = now
            s.ringFacts = runCatching { RingSnapshot.capture(appContext, now) }.getOrElse { RingFacts(now) }
        }

        // An emergency call opens the emergency window as soon as it exists, so a call-back arriving during it, or
        // after the process died before the call ended, still gets through.
        val emergency = emergencyFacts(call, number, incoming)
        if (EmergencyPolicy.startsWindow(emergency, incoming)) runCatching { ScreeningGuard.noteEmergencyCall(appContext) }

        // Screening runs before we show any UI. Never an emergency call or right after one (call-backs must get through).
        val accountId = call.details.accountHandle?.id
        if (incoming && !EmergencyPolicy.bypasses(Safeguard.SCREENING, emergency)) {
            val earlier = ScreeningCoordinator.Earlier(ScreeningGuard.recallOutcome(number), accountId)
            if (screening.applies(earlier, hidden)) {
                val callerName = call.details.callerDisplayName?.takeIf { it.isNotBlank() }
                screening.start(s, number, hidden, verificationOf(call), callerName, earlier, screeningHost)
            }
        }

        // Selecting a SIM automatically if the user pinned one for this number.
        if (call.stateCompat() == Call.STATE_SELECT_PHONE_ACCOUNT && number != null) {
            scope.launch {
                val pref = runCatching { deps.preferredAccountId(number) }.getOrNull()
                val handle = pref?.let { handleFor(it) }
                if (handle != null) call.phoneAccountSelected(handle, false)
            }
        }

        // An incoming call whose allowance is used up rings silently when the user asked for that.
        if (incoming && number != null && !hidden) {
            limits.checkAllowance(s, number, accountId, emergency, stillRinging = { ringing(call) }) {
                silenceRinger()
                ringer.stop()
                publish()
            }
        }

        if (number != null && !hidden) {
            scope.launch {
                var looked = false
                val found = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { deps.callerInfo(number, accountId) }.also { looked = it.isSuccess }.getOrNull() }
                if (looked) lookedUp(s, found, number, accountId)
                if (found != null) {
                    s.info = found
                    if (incoming) {
                        // The caller's haptic caller ID: Parley's ringer takes over the ringing (or the tone playing).
                        applyCallerVibration(call, s)
                        considerAutoAnswer(call)
                        announceInCar(call, s, found)
                    }
                } else {
                    // Only a lookup that finished and found nobody: a timeout or a failure must never offer "Block" for a contact.
                    if (looked && calls.contains(call)) s.noContact = true
                    // I1: what Parley remembers about the number, after the call is up (never delays the ringing).
                    if (looked) rememberNumber(call, s, number, accountId)
                    if (incoming) {
                        s.unknownCaller = true
                        if (!silenceInCar(call, s, number, accountId)) maybePlayUnknownRingtone(call, s)
                        // Show "unknown caller" with Block and Save now; the place fills in when the geocoder answers.
                        publish()
                        // "Where is this number from": the geocoder loads large data files the first time, so never
                        // on the main thread while the phone rings.
                        val where = withContext(Dispatchers.IO) { runCatching { deps.describeNumber(number) }.getOrNull().orEmpty() }
                        if (calls.contains(call)) s.location = where
                    }
                }
                publish()
            }
        } else {
            // Nothing to look up.
            s.lookupDone = true
            if (incoming) {
                s.unknownCaller = true
                scope.launch { if (!silenceInCar(call, s, null, accountId)) maybePlayUnknownRingtone(call, s) }
            }
        }
        publish()
    }

    /**
     * The caller lookup answered (after a timeout nobody knows whether the caller is saved). Discreet mode hides private
     * contacts from the lookup, yet they are saved: "Start calls on speaker" asks before it treats the number as unknown.
     */
    private fun lookedUp(s: CallSession, found: CallerDisplay?, number: String, accountId: String?) {
        if (found != null) {
            s.lookupDone = true
            return
        }
        scope.launch {
            s.savedPrivately = runCatching { deps.isSavedCaller(number, accountId) }.getOrDefault(false)
            s.lookupDone = true
            publish()
        }
    }

    /** I11: in the car, the caller's name once through its speakers (a contact, or a private contact discreet mode shows). */
    private fun announceInCar(call: Call, s: CallSession, found: CallerDisplay) {
        // Screening first: a blocked or flagged contact isn't announced (the verdict calls this again).
        if (!ringing(call) || s.screening) return
        val number = call.details.handle?.schemeSpecificPart
        val o = s.outcome
        drive.announce(
            appContext, s.id, found.name,
            DriveProfile.Caller(
                known = true,
                hidden = call.details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED,
                blockedOrSpam = o?.decision is Decision.Block || o?.warn == true,
                otherCall = calls.any { it != call && it.parent == null && mapState(it.stateCompat()) != CallState.DISCONNECTED },
                emergency = EmergencyPolicy.bypasses(Safeguard.SCREENING, emergencyFacts(call, number, incoming = true)),
                quiet = s.silenced || s.systemSilenced,
            ),
            // L2: under Do Not Disturb's Priority, a caller it lets through (a favourite) is still announced.
            handle = call.details.handle,
            starred = found.favourite,
        )
    }

    /**
     * I11: in the car, with "Silence unknown callers" on, a caller who is neither a contact nor a private contact rings
     * silently (still a missed call). Never an emergency call-back or a call screening let through on purpose; a
     * number that can't be checked in time rings. Returns whether it silenced the call.
     */
    private suspend fun silenceInCar(call: Call, s: CallSession, number: String?, accountId: String?): Boolean {
        if (!::appContext.isInitialized || s.silenced) return false
        if (!ringing(call) || !drive.mightSilence(appContext)) return false
        // Screening's verdict first: a repeat caller or an expected call it lets through must ring.
        withTimeoutOrNull(ScreeningCoordinator.SCREEN_TIMEOUT_MS + SCREEN_GRACE_MS) { while (s.screening && ringing(call)) delay(SCREEN_POLL_MS) }
        val saved = number != null &&
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { deps.isSavedCaller(number, accountId) }.getOrNull() } != false
        val caller = DriveProfile.Caller(
            known = false, saved = saved, hidden = number == null,
            emergency = EmergencyPolicy.bypasses(Safeguard.SCREENING, emergencyFacts(call, number, incoming = true)),
            rangThrough = s.outcome?.rangThrough != null,
        )
        if (!ringing(call) || !drive.silences(appContext, caller)) return false
        s.silenced = true
        drive.markSilenced(s.id)
        ringer.stop()
        silenceRinger()
        autoAnswer.cancel(s)
        publish()
        return true
    }

    /** I1: looks up number memory off the main thread, within the caller lookup's time; fails open (no line). */
    private fun rememberNumber(call: Call, s: CallSession, number: String, accountId: String?) {
        scope.launch {
            val line = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { runCatching { deps.numberMemory(number, accountId) }.getOrNull() }
            } ?: return@launch
            if (!calls.contains(call)) return@launch
            s.numberMemory = line
            publish()
        }
    }

    /**
     * Distinct ringtone for unknown callers (or the one screening chose, or a caller's haptic caller ID): silence
     * Telecom's ringer and play our own, only in normal ringer mode (on vibrate, only the caller's vibration) with Do Not Disturb off (so we never ring when the system wouldn't).
     */
    private fun maybePlayUnknownRingtone(call: Call, s: CallSession) {
        val pattern = vibrationOf(s)
        val uri = toneFor(s, pattern) ?: return
        if (s.screening || ringer.toneFor == s.id) return // played once screening allows the call
        if (!::appContext.isInitialized || s.silenced || !ringing(call)) return
        val otherActive = calls.any { it != call && mapState(it.stateCompat()) == CallState.ACTIVE }
        // On vibrate, only the vibration is ours: no tone plays.
        if (pattern != null) {
            ringer.vibrateOnly(appContext, s, pattern, otherActive, stillRinging = { ringing(call) }) {}
            if (ringer.toneFor == s.id) return
        }
        ringer.play(appContext, s, uri, otherActive, stillRinging = { ringing(call) }, pattern = pattern) { s.tonePlayed = playedSource(s) }
    }

    /**
     * The tone Parley's ringer plays, or null to leave the ringing to Telecom: a ringtone chosen by screening (rule,
     * label, repeat caller, likely spam), else the unknown-caller tone, else, for a caller with a haptic caller ID
     * ([pattern]), their own tone or the phone's default, so the call vibrates their way.
     */
    private fun toneFor(s: CallSession, pattern: LongArray?): String? {
        s.outcome?.ringtone?.let { return it }
        if (s.unknownCaller) return runCatching { deps.unknownRingtone() }.getOrNull()
        if (pattern == null) return null
        // Only the vibration would differ, and the call doesn't vibrate now (normal mode with "Vibrate for calls" off):
        // Telecom keeps ringing, with no gap while Parley's tone starts over.
        if (!::appContext.isInitialized || !CallRinger.ringVibrates(appContext)) return null
        return s.info?.ownRingtone ?: Settings.System.DEFAULT_RINGTONE_URI?.toString()
    }

    /** What "Why did my phone ring?" says about the tone Parley played. */
    private fun playedSource(s: CallSession): Pair<RingtoneSource, String?> {
        val o = s.outcome
        return when {
            o?.ringtone != null -> (o.ringtoneSource ?: RingtoneSource.RULE) to o.ringtoneName
            s.unknownCaller -> RingtoneSource.UNKNOWN_CALLER to null
            s.info?.ownRingtone != null -> RingtoneSource.CONTACT to null
            else -> RingtoneSource.DEFAULT to null
        }
    }

    /** The caller's haptic caller ID as a repeating waveform, or null for the phone's usual vibration. */
    private fun vibrationOf(s: CallSession): LongArray? {
        val info = s.info ?: return null
        val p = CallerHaptics.decode(info.vibration) ?: return null
        return CallerHaptics.repeating(p, info.name)
    }

    /** The caller was found with a haptic caller ID: a tone Parley already plays vibrates their way, else Parley rings. */
    private fun applyCallerVibration(call: Call, s: CallSession) {
        val pattern = vibrationOf(s) ?: return
        if (ringer.toneFor == s.id) ringer.useVibration(appContext, s.id, pattern) else maybePlayUnknownRingtone(call, s)
    }

    /** Parley's own silenceRinger() requests, whose onSilenceRinger() echo is not the user's. */
    private val selfSilence = SelfSilenceEcho()

    internal fun onSystemSilence() {
        // Telecom calls every in-call service back for any silenceRinger(), Parley's own included: that echo would
        // stop the tone or vibration Parley just took over, and cancel auto-answer. Only the user's silence counts.
        if (selfSilence.consumed(SystemClock.elapsedRealtime())) return
        ringer.stop()
        drive.quiet()
        // Silencing a call says "not now": it isn't answered on its own either.
        calls.filter { it.stateCompat() == Call.STATE_RINGING }.forEach { autoAnswer.cancel(session(idOf(it))) }
        restoreBoost()
        // A silent phone stays silent: the spoken caller name stops with the ringer.
        calls.filter { it.stateCompat() == Call.STATE_RINGING }.forEach { session(idOf(it)).systemSilenced = true }
        publish()
    }

    private fun restoreBoost() {
        ringer.restoreBoost(if (::appContext.isInitialized) appContext else null)
    }

    internal fun remove(call: Call) {
        val id = idOf(call)
        val s = session(id)
        if (ringer.toneFor == id) ringer.stop()
        if (ringer.boostedFor == id) restoreBoost()
        autoAnswer.forget(id)
        drive.forget(id)
        val base = toUi(call)
        // An outgoing call that never went through: the reason and Retry stay on the call-ended screen.
        val failure = CallFailure.classify(endFacts(call, base, s))
        // The unknown-caller extras (tone, "where from") end with the ringing.
        val shown = base.copy(unknown = false, location = null, holdModeSince = 0)
        // A connected call the network dropped: the reason in plain words, and "Call again" on the call-ended screen.
        val cause = call.details.disconnectCause
        val drop = CallDrop.classify(
            DropFacts(
                connected = base.connectTimeMillis > 0, userEnded = s.userEnded, emergency = base.isEmergency,
                code = endCode(cause), reason = cause?.reason, endedByLimit = s.endedByLimit,
            ),
        )
        val ended = when {
            failure != null -> shown.copy(failure = failure, failureText = failureText(failure, call))
            drop != null -> shown.copy(drop = drop, dropText = dropText(drop, s), disconnectReason = str(R.string.call_drop_title))
            else -> shown
        }
        stopHoldReminders(id)
        recordQuality(ended, s, drop, cause)
        recordMenuKeys(ended, s)
        if (_menuReplay.value?.callId == id) stopMenuReplay()
        _lastEnded.value = ended
        // A call that failed before the caller lookup finished still shows the name on "Call ended".
        if (ended.name == null && !ended.hidden && !ended.number.isNullOrBlank()) lookUpEndedName(ended)
        s.ringStartedAt?.let { started ->
            val connected = ended.connectTimeMillis > 0
            val rang = ((if (connected) ended.connectTimeMillis else System.currentTimeMillis()) - started).coerceAtLeast(0)
            runCatching { deps.onRingFinished(ended.number, started, rang, connected) }
            s.ringFacts?.let { f ->
                runCatching { deps.onRingFacts(ended.number.takeIf { !ended.hidden }, finishRingFacts(s, call, f, rang, connected)) }
            }
        }
        // The window started when the call was added; it runs for its full length from the end of the call too.
        if (::appContext.isInitialized && EmergencyPolicy.startsWindow(emergencyFacts(call, ended.number, ended.incoming), ended.incoming)) {
            runCatching { ScreeningGuard.noteEmergencyCall(appContext) }
        }
        // The allowance ledger: every connected call except emergency ones (never limited, so never counted).
        if (ended.connectTimeMillis > 0 && !ended.isEmergency && !ended.isConference) {
            val talkedSec = ((System.currentTimeMillis() - ended.connectTimeMillis) / 1000).coerceAtLeast(0)
            runCatching {
                deps.onCallUsage(ended.number.takeIf { !ended.hidden }, call.details.accountHandle?.id, ended.incoming, ended.connectTimeMillis, talkedSec)
            }
        }
        runCatching { deps.onCallEnded(ended.number, ended.incoming, ended.connectTimeMillis) }
        call.unregisterCallback(callback)
        calls -= call
        notifier.endTrace(s)
        // Everything known about the call goes at once.
        sessions.remove(id)
        if (calls.isEmpty()) emergencyNumbers.clear()
        val wasInFront = book.remove(id)
        publish()
        if (wasInFront) resumeHeldIfAlone()
    }

    /**
     * When the call in front ends and exactly one held call is left, resume it, unless another call is
     * ringing, dialling or active. Checked after a moment, since some networks resume on their own.
     */
    private fun resumeHeldIfAlone() {
        scope.launch {
            delay(RESUME_DELAY_MS)
            book.toResume(calls.filter { it.parent == null }.map { it to mapState(it.stateCompat()) })?.unhold()
        }
    }

    /** Re-sends the current state to the notification and proximity observers (e.g. a countdown changed). */
    internal fun notifyObservers() {
        notifier.send(_calls.value)
    }

    /** Called when Parley places a call, so the UI can say "Calling via Work SIM…" before the call exists. */
    fun expectOutgoing(number: String, simLabel: String?) {
        val p = PendingOutgoing(number, simLabel, SystemClock.elapsedRealtime())
        _pendingOutgoing.value = p
        scope.launch {
            delay(PENDING_OUTGOING_MS)
            if (_pendingOutgoing.value == p) _pendingOutgoing.value = null
        }
    }

    /** The InCallService unbound: no call is left, so nothing may keep ringing, stay boosted or linger. */
    internal fun clear() {
        ringer.stop()
        if (ringer.boostedFor != null) restoreBoost()
        calls.toList().forEach { it.unregisterCallback(callback) }
        calls.clear()
        accountLabels.clear()
        // A SIM swapped while no call was up: its own number is read again.
        accountNumbers.clear()
        book.clear()
        sessions.values.forEach { notifier.endTrace(it) }
        sessions.clear()
        publish()
    }

    fun isScreening(id: String) = sessions[id]?.screening == true

    /** The first notification for an incoming call was posted: ends its add → notification trace section. */
    internal fun onNotificationShown(id: String) {
        sessions[id]?.let { notifier.endTrace(it) }
    }

    private fun publish() {
        ringer.follow(if (::appContext.isInitialized) appContext else null) { rid ->
            val c = calls.firstOrNull { idOf(it) == rid }
            c != null && c.stateCompat() == Call.STATE_RINGING && sessions[rid]?.silenced != true
        }
        val now = SystemClock.elapsedRealtime()
        calls.filter { it.parent == null }.forEach { c ->
            val s = session(idOf(c))
            val st = mapState(c.stateCompat())
            book.update(s.id, st, now)
            noteFacts(c, s, st)
            // Where an incoming call was answered, read again a moment later once the audio route has settled.
            if (st == CallState.ACTIVE && s.ringFacts != null && s.answeredRoute == null) {
                s.answeredRoute = RingSnapshot.route(_audio.value) ?: (AnswerRoute.EARPIECE to null)
                scope.launch {
                    delay(ROUTE_SETTLE_MS)
                    if (s.answeredRoute != null) RingSnapshot.route(_audio.value)?.let { s.answeredRoute = it }
                }
            }
        }
        val top = calls.filter { it.parent == null }.map { toUi(it) }
        if (top.isNotEmpty()) _pendingOutgoing.value = null
        _calls.value = top
        CallClock.onCallsChanged(top)
        notifier.send(top)
        speakerOnStart()
    }

    private fun toUi(call: Call): CallUi {
        val d = call.details
        val id = idOf(call)
        val s = sessions[id] ?: CallSession(id)
        val found = s.info
        val number = d.handle?.schemeSpecificPart
        val hidden = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val caps = d.callCapabilities
        fun can(c: Int) = (caps and c) != 0
        val conferenceable = call.conferenceableCalls.isNotEmpty()
        val state = mapState(call.stateCompat())
        // Before Telecom picks the account, the one Parley asked for is in the intent extras.
        val account = d.accountHandle ?: requestedAccount(d)
        return CallUi(
            id = id,
            state = state,
            number = number,
            hidden = hidden,
            name = found?.name ?: d.contactDisplayNameCompat() ?: d.callerDisplayName?.takeIf { it.isNotBlank() },
            savedCaller = savedCaller(found, d),
            label = found?.label,
            photoUri = found?.photoUri,
            backgroundUri = found?.backgroundUri,
            contactId = found?.contactId,
            lookupKey = found?.lookupKey,
            incoming = d.callDirection == Call.Details.DIRECTION_INCOMING,
            connectTimeMillis = d.connectTimeMillis,
            isConference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
            children = call.children.map { toUi(it) },
            canHold = can(Call.Details.CAPABILITY_HOLD),
            canMerge = can(Call.Details.CAPABILITY_MERGE_CONFERENCE) || conferenceable,
            canSwap = can(Call.Details.CAPABILITY_SWAP_CONFERENCE),
            canMute = can(Call.Details.CAPABILITY_MUTE),
            canSeparate = can(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE),
            canDisconnectChild = can(Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE),
            canRespondViaText = can(Call.Details.CAPABILITY_RESPOND_VIA_TEXT),
            accountLabel = accountLabel(account),
            verification = verificationOf(call),
            disconnectReason = endedReason(s) ?: d.disconnectCause?.let { disconnectText(it) },
            postDialWait = s.postDial,
            silenced = s.silenced,
            silenceReason = silenceReasonOf(s),
            accountId = account?.id,
            heldSinceElapsed = book.heldSince(id),
            isEmergency = isEmergencyCall(call, number),
            note = found?.note,
            lastCall = found?.lastCall,
            subtitle = found?.subtitle,
            context = found?.context,
            memory = found?.memory,
            memoryPrompt = found?.memoryPrompt == true,
            unknown = s.unknownCaller,
            location = if (s.unknownCaller) s.location?.ifEmpty { null } else null,
            verdict = s.outcome?.verdict,
            verdictWarn = s.outcome?.warn == true,
            noContact = s.noContact && found == null,
            accountNumber = accountNumber(account),
            fallbackTitle = str(if (hidden) R.string.call_private_number else R.string.call_unknown).orEmpty(),
            systemSilenced = s.systemSilenced,
            blockingDecline = s.blockingDecline,
            hdAudio = d.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO),
            wifi = d.hasProperty(Call.Details.PROPERTY_WIFI),
            videoAsVoice = s.videoOffered || incomingVideo(d),
            pronouns = found?.pronouns,
            autoAnswerAt = if (state == CallState.RINGING) s.autoAnswerAt else 0,
            subject = s.subject,
            urgent = s.urgent,
            holdModeSince = s.holdModeSince,
            reputation = reputationTag(s, call, number, hidden),
            // Only ever set for a number the lookup found no contact for.
            numberMemory = s.numberMemory,
            driving = drivingNow(state),
            handOff = handOffFacts(call),
        ).withRangThrough(s)
    }

    /** Why the call ended, when Parley ended it on purpose (a limit, sent to another number). */
    private fun endedReason(s: CallSession): String? = when {
        s.endedByLimit -> str(R.string.call_limit_reached)
        s.handedOff == HandOff.DEFLECTED -> str(R.string.handoff_ended_deflected)
        else -> null
    }

    /** The caller is a contact or a private contact (found by the lookup, or named by Telecom from the contacts). */
    private fun savedCaller(found: CallerDisplay?, d: Call.Details): Boolean = found != null || d.contactDisplayNameCompat() != null

    /** Why a call rings silently when it isn't a blocking rule: an allowance used up, or the drive profile (I11). */
    private fun silenceReasonOf(s: CallSession): String? = when {
        s.quotaSilenced -> str(R.string.call_silenced_quota)
        drive.silencedHere(s.id) -> str(R.string.drive_silenced)
        else -> null
    }

    /** I11: "Drive profile on" for a live call while the marked car is connected. */
    private fun drivingNow(state: CallState): Boolean =
        state != CallState.DISCONNECTED && state != CallState.DISCONNECTING && ::appContext.isInitialized && drive.driving(appContext)

    /** I2's tag, for an unknown, visible, non-emergency caller only. */
    private fun reputationTag(s: CallSession, call: Call, number: String?, hidden: Boolean) =
        s.outcome?.reputation?.takeIf { !hidden && s.info == null && !isEmergencyCall(call, number) }

    /** P1 while it rings; the "rang through" line says it better than the quiet "Allowed by …" tag (a warning stays). */
    private fun CallUi.withRangThrough(s: CallSession): CallUi {
        if (state != CallState.RINGING || s.silenced) return this
        val text = rangThroughText(s.outcome?.rangThrough) ?: return this
        return copy(rangThrough = text, rangThroughUnlocked = expectedNoteText(s.outcome?.rangThrough), verdict = verdict.takeIf { verdictWarn })
    }

    /** An incoming call offered with video (answered audio-only all the same). */
    private fun incomingVideo(d: Call.Details): Boolean = d.callDirection == Call.Details.DIRECTION_INCOMING && VideoProfile.isVideo(d.videoState)

    /**
     * What a call reports while it goes on, kept for its facts: Wi-Fi calling, HD voice and the SIM while connected
     * (Telecom clears them as the call ends), and the caller's subject and priority, which some networks send late.
     */
    private fun noteFacts(c: Call, s: CallSession, st: CallState) {
        val d = c.details
        // Kept once seen: answering audio-only turns the call's video state off.
        if (incomingVideo(d)) s.videoOffered = true
        if (st == CallState.ACTIVE || st == CallState.HOLDING) {
            if (d.hasProperty(Call.Details.PROPERTY_WIFI)) s.wifiSeen = true
            if (d.hasProperty(Call.Details.PROPERTY_HIGH_DEF_AUDIO)) s.hdSeen = true
            if (s.simLabel == null) s.simLabel = accountLabel(d.accountHandle)
        }
        if (s.subject == null) s.subject = CallSubject.clean(runCatching { d.extras?.getCharSequence(TelecomManager.EXTRA_CALL_SUBJECT) }.getOrNull())
            ?: CallSubject.clean(runCatching { d.intentExtras?.getCharSequence(TelecomManager.EXTRA_CALL_SUBJECT) }.getOrNull())
        if (!s.urgent && Build.VERSION.SDK_INT >= 31) {
            s.urgent = runCatching { d.extras?.getInt(TelecomManager.EXTRA_PRIORITY, TelecomManager.PRIORITY_NORMAL) == TelecomManager.PRIORITY_URGENT }
                .getOrDefault(false)
        }
    }

    /** P1: "Rang through: called twice in 3 min", "Rang through: expecting a call", … */
    private fun rangThroughText(r: RangThrough?): String? {
        if (r == null || !::appContext.isInitialized) return null
        val res = appContext.resources
        return when (r.kind) {
            RangThroughKind.REPEAT_CALLER ->
                if (r.calls <= 2) res.getString(R.string.call_rang_repeat_twice, r.minutes) else res.getString(R.string.call_rang_repeat, r.calls, r.minutes)
            RangThroughKind.EXPECTING -> res.getString(expectingText(r.expected))
            RangThroughKind.ALLOW_RULE -> allowRuleText(r)
            RangThroughKind.LABEL -> r.name?.let { res.getString(R.string.call_rang_label, it) } ?: res.getString(R.string.call_rang_allowed)
            RangThroughKind.DIALLED -> res.getString(R.string.call_rang_dialled)
            RangThroughKind.ANSWERED -> res.getString(R.string.call_rang_answered)
        }
    }

    /** I7: what turned "Expecting a call" on; the note's name shows only while unlocked (see [expectedNoteText]). */
    private fun expectingText(source: ExpectedSource?): Int = when (source) {
        ExpectedSource.NOTE -> R.string.call_rang_expecting_notes
        ExpectedSource.TO_CALL -> R.string.call_rang_expecting_to_call
        ExpectedSource.DELIVERY_QR -> R.string.call_rang_expecting_delivery
        null -> R.string.call_rang_expecting
    }

    /** I7: "Rang through: expecting a call (note on Dentist)", for the unlocked screen only; null for anything else. */
    private fun expectedNoteText(r: RangThrough?): String? {
        if (r?.kind != RangThroughKind.EXPECTING || r.expected != ExpectedSource.NOTE || !::appContext.isInitialized) return null
        val name = r.name?.takeIf { it.isNotBlank() } ?: return null
        return appContext.getString(R.string.call_rang_expecting_note_on, name)
    }

    /** "Rang through: allowed until 18:40" for a temporary rule, else the rule's name. */
    private fun allowRuleText(r: RangThrough): String {
        val res = appContext.resources
        val until = r.until
        val name = r.name
        return when {
            until != null -> {
                val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(until)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY
                res.getString(R.string.call_rang_until, DateUtils.formatDateTime(appContext, until, flags))
            }
            name != null -> res.getString(R.string.call_rang_rule, name)
            else -> res.getString(R.string.call_rang_allowed)
        }
    }

    /** "Lost signal · Wi-Fi calling · Work": why a call dropped and what it was on. */
    private fun dropText(kind: DropKind, s: CallSession): String? {
        val reason = when (kind) {
            DropKind.LOST_SIGNAL -> str(R.string.call_drop_lost_signal)
            DropKind.WIFI_LOST -> str(R.string.call_drop_wifi_lost)
            DropKind.NO_SERVICE -> str(R.string.call_drop_no_service)
            DropKind.NETWORK -> str(R.string.call_drop_network)
        }
        val wifi = str(R.string.incall_wifi_calling)?.takeIf { s.wifiSeen && kind != DropKind.WIFI_LOST }
        return listOfNotNull(reason, wifi, s.simLabel).joinToString(str(R.string.tc_separator) ?: " · ").ifBlank { null }
    }

    /** L2: the call's quality facts, for the number history (and a quality diary later). Never for emergency calls. */
    private fun recordQuality(ended: CallUi, s: CallSession, drop: DropKind?, cause: DisconnectCause?) {
        if (ended.isEmergency || ended.isConference || s.startedAt == 0L) return
        val talked = if (ended.connectTimeMillis > 0) ((System.currentTimeMillis() - ended.connectTimeMillis) / 1000).coerceAtLeast(0) else 0
        val facts = CallQualityFacts(
            startedAt = s.startedAt,
            incoming = ended.incoming,
            durationSec = talked,
            connected = ended.connectTimeMillis > 0,
            sim = s.simLabel ?: ended.accountLabel,
            wifi = s.wifiSeen,
            hd = s.hdSeen,
            end = endCode(cause),
            cause = CallQualityCodec.causeName(cause?.reason)?.takeIf { drop != null },
            drop = drop,
            subject = s.subject,
        )
        runCatching { deps.onCallQuality(ended.number.takeIf { !ended.hidden }, facts) }
    }

    @Suppress("DEPRECATION")
    private fun requestedAccount(d: Call.Details): PhoneAccountHandle? = try {
        if (Build.VERSION.SDK_INT >= 33) d.intentExtras?.getParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle::class.java)
        else d.intentExtras?.getParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE)
    } catch (_: Exception) {
        null
    }

    private fun Call.Details.contactDisplayNameCompat(): String? =
        if (Build.VERSION.SDK_INT >= 30) contactDisplayName?.takeIf { it.isNotBlank() } else null

    @Suppress("DEPRECATION")
    private fun Call.stateCompat(): Int = if (Build.VERSION.SDK_INT >= 31) details.state else state

    private fun mapState(s: Int): CallState = when (s) {
        Call.STATE_NEW -> CallState.NEW
        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallState.RINGING
        Call.STATE_DIALING -> CallState.DIALING
        Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> CallState.CONNECTING
        Call.STATE_ACTIVE -> CallState.ACTIVE
        Call.STATE_HOLDING -> CallState.HOLDING
        Call.STATE_DISCONNECTING -> CallState.DISCONNECTING
        Call.STATE_DISCONNECTED -> CallState.DISCONNECTED
        Call.STATE_SELECT_PHONE_ACCOUNT -> CallState.SELECT_ACCOUNT
        else -> CallState.OTHER
    }

    private fun verificationOf(call: Call): Verification = if (Build.VERSION.SDK_INT < 30) Verification.NOT_VERIFIED else when (call.details.callerNumberVerificationStatus) {
        Connection.VERIFICATION_STATUS_PASSED -> Verification.PASSED
        Connection.VERIFICATION_STATUS_FAILED -> Verification.FAILED
        else -> Verification.NOT_VERIFIED
    }

    private fun disconnectText(c: DisconnectCause): String? = when (c.code) {
        DisconnectCause.LOCAL, DisconnectCause.REMOTE -> null
        DisconnectCause.BUSY -> str(R.string.call_disconnect_busy)
        DisconnectCause.MISSED -> str(R.string.call_disconnect_missed)
        DisconnectCause.REJECTED -> str(R.string.call_disconnect_declined)
        DisconnectCause.ERROR -> c.label?.toString()?.ifBlank { null } ?: str(R.string.call_disconnect_failed)
        else -> c.label?.toString()?.ifBlank { null }
    }

    /** A UI text in the user's language; null before [add] (calls only arrive after it). */
    private fun str(res: Int): String? = if (::appContext.isInitialized) appContext.getString(res) else null

    /** Platform answers per number, while calls exist (the list depends on the SIM and network, so not for longer). */
    private val emergencyNumbers = ConcurrentHashMap<String, Boolean>()

    private fun isEmergency(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        return emergencyNumbers.getOrPut(number) {
            runCatching { deps.isEmergencyNumber(number) }.getOrElse { EmergencyPolicy.isFallbackEmergencyNumber(number) }
        }
    }

    /** An emergency number, or a call the network identified as one, or one taking place in emergency callback mode. */
    private fun isEmergencyCall(call: Call, number: String?): Boolean =
        isEmergency(number) || hasEmergencyProperty(call)

    private fun hasEmergencyProperty(call: Call): Boolean = runCatching {
        call.details.hasProperty(Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL) ||
            call.details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)
    }.getOrDefault(false)

    /** What [EmergencyPolicy] needs about [call]. */
    private fun emergencyFacts(call: Call, number: String?, incoming: Boolean): EmergencyPolicy.Facts = EmergencyPolicy.Facts(
        emergencyNumber = isEmergency(number),
        emergencyCallProperty = hasEmergencyProperty(call),
        inWindow = ::appContext.isInitialized && runCatching { ScreeningGuard.inEmergencyWindow(appContext) }.getOrDefault(false),
        userListed = !incoming && !number.isNullOrBlank() && runCatching { deps.startsEmergencyWindow(number) }.getOrDefault(false),
    )

    private val accountLabels = HashMap<PhoneAccountHandle, String?>()

    private fun accountLabel(h: PhoneAccountHandle?): String? {
        if (h == null || !::appContext.isInitialized) return null
        return accountLabels.getOrPut(h) {
            try {
                val tm = appContext.getSystemService(TelecomManager::class.java)
                if (tm.callCapablePhoneAccounts.size < 2) null else tm.getPhoneAccount(h)?.label?.toString()
            } catch (_: SecurityException) {
                null
            }
        }
    }

    private val accountNumbers = HashMap<PhoneAccountHandle, String?>()

    /** The SIM's own number, only on dual-SIM phones and only when Android knows it. */
    private fun accountNumber(h: PhoneAccountHandle?): String? {
        if (h == null || !::appContext.isInitialized || accountLabel(h) == null) return null
        return accountNumbers.getOrPut(h) {
            try {
                val a = appContext.getSystemService(TelecomManager::class.java).getPhoneAccount(h)
                (a?.subscriptionAddress ?: a?.address)?.schemeSpecificPart?.takeIf { n -> n.count { it.isDigit() } >= 4 }
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Completes the ring facts captured when the call started ringing: which tone played, whether Parley kept it
     * quiet and why, and how the call ended (the rules are [RingEnd]'s).
     */
    private fun finishRingFacts(s: CallSession, call: Call, f: RingFacts, rang: Long, connected: Boolean): RingFacts {
        val o = s.outcome
        val block = o?.decision as? Decision.Block
        val cause = call.details.disconnectCause?.code
        val end = RingEnd.of(
            RingEnd.Facts(
                silenced = s.silenced,
                quotaSilenced = s.quotaSilenced,
                ignoredByUser = s.ignoredByUser,
                blocked = block != null,
                blockedReject = block?.action == BlockAction.REJECT,
                tonePlayed = s.tonePlayed,
                connected = connected,
                disconnect = when (cause) {
                    DisconnectCause.ANSWERED_ELSEWHERE, DisconnectCause.CALL_PULLED -> RingEnd.Disconnect.ANSWERED_ELSEWHERE
                    DisconnectCause.REJECTED -> RingEnd.Disconnect.REJECTED
                    else -> RingEnd.Disconnect.OTHER
                },
            ),
        )
        val silencedBy = when (end.silence) {
            null -> null
            RingEnd.SilenceReason.QUOTA -> str(R.string.call_silenced_quota)
            RingEnd.SilenceReason.RULES -> o?.verdict?.takeIf { it.isNotBlank() } ?: str(R.string.call_silenced_rules)
            RingEnd.SilenceReason.IGNORED -> str(R.string.call_silenced_ignored)
            RingEnd.SilenceReason.OTHER -> str(R.string.call_silenced)
        }
        val route = if (connected) s.answeredRoute else null
        return f.copy(
            ringMillis = rang,
            ringtone = end.ringtone,
            ringtoneDetail = end.ringtoneDetail,
            silencedBy = silencedBy,
            ringLoud = s.loud,
            outcome = end.outcome,
            answeredRoute = route?.first,
            answeredDevice = route?.second,
        )
    }

    fun handleFor(accountId: String): PhoneAccountHandle? = try {
        appContext.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.firstOrNull { it.id == accountId }
    } catch (_: Exception) {
        null
    }

    private fun find(id: String): Call? = calls.firstOrNull { idOf(it) == id } ?: calls.flatMap { it.children }.firstOrNull { idOf(it) == id }

    // ---- Actions ----

    fun answer(id: String) {
        if (sessions[id]?.blockingDecline == true) return
        val call = find(id) ?: return
        // Always audio-only: video needs the camera, which Parley doesn't ask for. A video call says so on screen.
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        answered(id)
    }

    /** The answer buzz, and no connect buzz right after it. */
    private fun answered(id: String) {
        session(id).answeredByUser = true
        CallClock.haptic(CallHaptic.ANSWER)
        // L3: "Answer with RTT" asks to switch once the call is up.
        CallRtt.onAnswered(id)
    }

    internal fun wasAnsweredByUser(id: String) = sessions[id]?.answeredByUser == true

    /**
     * Puts the active call on hold and answers the waiting one. Telecom would end an active call that
     * can't be held, so the UI only offers this when holding is possible.
     */
    fun holdAndAnswer(id: String) {
        if (sessions[id]?.blockingDecline == true) return
        val waiting = find(id) ?: return
        calls.filter { it != waiting && it.parent == null && mapState(it.stateCompat()) == CallState.ACTIVE }
            .forEach { if ((it.details.callCapabilities and Call.Details.CAPABILITY_HOLD) != 0) it.hold() }
        waiting.answer(VideoProfile.STATE_AUDIO_ONLY)
        answered(id)
    }

    /**
     * Ends the call whose time limit ran out. Only that call: a waiting or held call is never touched, and
     * `TelecomManager.endCall()` (which picks a call on its own) is never used.
     */
    internal fun endForLimit(id: String) {
        val call = find(id) ?: return
        val incoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        if (!limits.mayEnd(mapState(call.stateCompat()), emergencyFacts(call, call.details.handle?.schemeSpecificPart, incoming))) return
        session(id).endedByLimit = true
        call.disconnect()
    }

    /** Ends the call a "hang up" shortcut should end: the active one, else one being dialled, else a held one. */
    fun hangupForeground(): Boolean {
        val top = calls.filter { it.parent == null }
        val pick = top.firstOrNull { mapState(it.stateCompat()) == CallState.ACTIVE }
            ?: top.firstOrNull { mapState(it.stateCompat()) in DIALLING_STATES }
            ?: top.firstOrNull { mapState(it.stateCompat()) == CallState.HOLDING }
            ?: return false
        session(idOf(pick)).userEnded = true
        pick.disconnect()
        return true
    }

    /** Ends the current active call, then answers the waiting one. */
    fun endAndAnswer(id: String) {
        if (sessions[id]?.blockingDecline == true) return
        val waiting = find(id) ?: return
        calls.filter { it != waiting && it.parent == null && mapState(it.stateCompat()) == CallState.ACTIVE }.forEach {
            session(idOf(it)).userEnded = true
            it.disconnect()
        }
        answered(id)
        scope.launch {
            // Answer once the ended call is really gone (or after 3 s at most).
            var waited = 0
            while (waited < 3000 && calls.any { it != waiting && it.parent == null && mapState(it.stateCompat()) == CallState.ACTIVE }) {
                delay(100)
                waited += 100
            }
            waiting.answer(VideoProfile.STATE_AUDIO_ONLY)
        }
    }

    /**
     * Declines a call. With a [message], Telecom sends it through the SMS app when the carrier
     * supports "respond via text"; otherwise the SMS app is opened with the text pre-filled.
     */
    fun reject(id: String, message: String? = null) {
        val call = find(id) ?: return
        session(id).userEnded = true
        // Declining has its own buzz, different from answering.
        CallClock.haptic(CallHaptic.DECLINE)
        val canText = (call.details.callCapabilities and Call.Details.CAPABILITY_RESPOND_VIA_TEXT) != 0
        if (message != null && canText) {
            call.reject(true, message)
            return
        }
        call.reject(false, null)
        val number = call.details.handle?.schemeSpecificPart
        if (message != null && !number.isNullOrBlank()) {
            try {
                appContext.startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                        .putExtra("sms_body", message)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun rejectUnwanted(call: Call) {
        if (Build.VERSION.SDK_INT >= 30) call.reject(Call.REJECT_REASON_UNWANTED) else call.reject(false, null)
    }

    /**
     * "Block & decline". The ringing stops at once and Parley's answer controls go away; the block rule is
     * written first (waited for a bounded time, so the call can't ring on while the database is slow), then the call
     * is declined as unwanted. The write itself is never cancelled: when it takes longer, the card says "Blocking…"
     * and shows the real outcome (with Undo) once the write is done.
     */
    fun blockAndDecline(id: String) {
        val call = find(id) ?: return
        val number = call.details.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() } ?: return
        if (call.details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || isEmergencyCall(call, number)) return
        val s = session(id)
        if (s.blockingDecline) return
        s.blockingDecline = true
        s.userEnded = true
        s.silenced = true
        silenceRinger()
        ringer.stop()
        restoreBoost()
        CallClock.haptic(CallHaptic.DECLINE)
        publish()
        // Its own job in the long-lived scope: the timeout below only stops waiting for it.
        val write = scope.async { runCatching { deps.blockForDecline(number) }.getOrNull() }
        scope.launch {
            withTimeoutOrNull(BLOCK_TIMEOUT_MS) { write.join() }
            val stillRinging = ringing(call)
            // Answered anyway (a headset button goes straight to Telecom): blocked for next time, but not declined.
            val answered = calls.contains(call) && !stillRinging && mapState(call.stateCompat()) in ANSWERED_STATES
            val done = write.isCompleted
            _declineBlock.value = DeclineBlock(id, number, if (done) write.await() else null, pending = !done, answered = answered)
            if (stillRinging) rejectUnwanted(call)
            s.blockingDecline = false
            publish()
            if (!done) {
                val ruleId = write.await()
                _declineBlock.value?.takeIf { it.callId == id }?.let { _declineBlock.value = it.copy(ruleId = ruleId, pending = false) }
            }
        }
    }

    /** Undo on the call-ended screen. */
    fun undoDeclineBlock() {
        val b = _declineBlock.value ?: return
        val rule = b.ruleId?.takeIf { it > 0 } ?: return
        if (b.undone || b.pending) return
        _declineBlock.value = b.copy(undone = true)
        scope.launch { runCatching { deps.undoBlockForDecline(rule) } }
    }

    /** Done on the "Blocked · Undo" card shown above a call that goes on. */
    fun dismissDeclineBlock() {
        _declineBlock.value = null
    }

    /** Dismiss (or Retry) on the failure banner: it stays gone, whichever screen shows the ended call next. */
    fun dismissFailure(id: String) {
        _lastEnded.value?.takeIf { it.id == id && it.failure != null }?.let { _lastEnded.value = it.copy(failure = null, failureText = null) }
    }

    /** Retry couldn't place the call: the banner comes back, unless another call has started since. */
    fun restoreFailure(failed: CallUi) {
        // A new call clears the last ended one (see [add]), so an id match means none has started.
        if (_lastEnded.value?.id == failed.id) _lastEnded.value = failed
    }

    /** Retry on the failure banner. The reason it still failed, or null. */
    suspend fun redial(number: String, accountId: String?): String? =
        runCatching { deps.redial(number, accountId) }.getOrElse { it.message ?: str(R.string.call_disconnect_failed) }

    /** What's known about a call that just left Telecom. */
    private fun endFacts(call: Call, ended: CallUi, s: CallSession): EndFacts = EndFacts(
        outgoing = !ended.incoming,
        connected = ended.connectTimeMillis > 0,
        code = endCode(call.details.disconnectCause),
        endedInSimPicker = book.lastLiveState(s.id) == CallState.SELECT_ACCOUNT,
        userEnded = s.userEnded,
        airplaneMode = ::appContext.isInitialized &&
            runCatching { Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 }.getOrDefault(false),
        emergency = ended.isEmergency,
        hasNumber = !ended.hidden && !ended.number.isNullOrBlank(),
    )

    private fun endCode(c: DisconnectCause?): EndCode? = when (c?.code) {
        null -> null
        DisconnectCause.LOCAL -> EndCode.LOCAL
        DisconnectCause.REMOTE -> EndCode.REMOTE
        DisconnectCause.BUSY -> EndCode.BUSY
        DisconnectCause.ERROR, DisconnectCause.CONNECTION_MANAGER_NOT_SUPPORTED -> EndCode.ERROR
        DisconnectCause.RESTRICTED -> EndCode.RESTRICTED
        DisconnectCause.CANCELED -> EndCode.CANCELED
        DisconnectCause.MISSED -> EndCode.MISSED
        DisconnectCause.REJECTED -> EndCode.REJECTED
        DisconnectCause.OTHER -> EndCode.OTHER
        DisconnectCause.UNKNOWN -> EndCode.UNKNOWN
        else -> null // answered elsewhere, pulled: not a failure
    }

    private fun failureText(f: FailureKind, call: Call): String? = when (f) {
        FailureKind.AIRPLANE_MODE -> str(R.string.call_failed_airplane)
        FailureKind.NO_SIM_SELECTED -> str(R.string.call_failed_no_sim)
        FailureKind.BUSY -> str(R.string.call_disconnect_busy)
        FailureKind.OTHER -> call.details.disconnectCause?.let { c -> (c.description ?: c.label)?.toString()?.takeIf { it.isNotBlank() } }
            ?: str(R.string.call_failed_generic)
    }

    /** The caller lookup hadn't finished when the call ended: fill the name in on the call-ended screen. */
    private fun lookUpEndedName(ended: CallUi) {
        val number = ended.number ?: return
        scope.launch {
            val found = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { deps.callerInfo(number, ended.accountId) }.getOrNull() } ?: return@launch
            val now = _lastEnded.value
            if (now?.id == ended.id) {
                _lastEnded.value = now.copy(name = found.name, label = found.label, photoUri = found.photoUri, contactId = found.contactId, noContact = false)
            }
        }
    }

    fun silenceRinger() {
        try {
            appContext.getSystemService(TelecomManager::class.java).silenceRinger()
            // Noted once Telecom took it (a refused request echoes nothing). The echo is posted to this main thread,
            // so it can't arrive before this line.
            selfSilence.noted(SystemClock.elapsedRealtime())
        } catch (_: Exception) {
        }
    }

    /** Stop ringing but leave the call waiting (the caller hears it ring until they give up). */
    fun ignore(id: String) {
        val s = session(id)
        s.silenced = true
        s.ignoredByUser = true
        autoAnswer.cancel(s)
        silenceRinger()
        ringer.stop()
        restoreBoost()
        publish()
    }

    // ---- Hold mode (I10) ----

    private val holdReminders = HashMap<String, Job>()

    /**
     * "I'm on hold": the speaker comes on (so the phone can lie on the table), the screen shows a hold timer, and the
     * phone buzzes at [HoldMode.REMINDER_MINUTES]. Parley can't hear the call, so it never guesses when someone is back.
     */
    fun startHoldMode(id: String) {
        val call = find(id) ?: return
        if (mapState(call.stateCompat()) != CallState.ACTIVE || isEmergencyCall(call, call.details.handle?.schemeSpecificPart)) return
        val s = session(id)
        if (s.holdModeSince != 0L) return
        val since = SystemClock.elapsedRealtime()
        s.holdModeSince = since
        val a = _audio.value
        if (a.current?.type != RouteType.SPEAKER) {
            s.routeBeforeHold = a.current
            a.routes.firstOrNull { it.type == RouteType.SPEAKER }?.let { setRoute(it) }
        }
        stopHoldReminders(id)
        holdReminders[id] = scope.launch {
            var at = HoldMode.nextReminderAt(since, SystemClock.elapsedRealtime())
            // Cancelled when hold mode ends or the call goes; the check covers a restart of hold mode meanwhile.
            while (at != null && sessions[id]?.holdModeSince == since) {
                delay((at - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (sessions[id]?.holdModeSince == since) CallClock.remind(CallHaptic.WARN)
                at = HoldMode.nextReminderAt(since, SystemClock.elapsedRealtime())
            }
        }
        publish()
    }

    /** Leaves hold mode: the audio goes back where it was, unless the user moved it meanwhile. */
    fun stopHoldMode(id: String) {
        val s = sessions[id] ?: return
        if (s.holdModeSince == 0L) return
        s.holdModeSince = 0
        stopHoldReminders(id)
        val back = s.routeBeforeHold
        s.routeBeforeHold = null
        val a = _audio.value
        if (back != null && a.current?.type == RouteType.SPEAKER) a.routes.firstOrNull { it.key == back.key }?.let { setRoute(it) }
        publish()
    }

    private fun stopHoldReminders(id: String) {
        holdReminders.remove(id)?.cancel()
    }

    // ---- Call again, and calling a saved number back (P5, I3) ----

    /** Dismiss (or Call again) on the "Call dropped" card: it stays gone. */
    fun dismissDrop(id: String) {
        _lastEnded.value?.takeIf { it.id == id && it.drop != null }?.let { _lastEnded.value = it.copy(drop = null, dropText = null) }
    }

    /**
     * I3 "Check it's really them": ends the call [id] (declines it while it rings) and, once it's gone, dials [number],
     * the number saved for who the caller said they were. Runs here rather than on the screen, which closes as the
     * call ends. [onProblem] hears why the new call couldn't be placed.
     */
    fun hangUpAndCall(id: String, number: String, accountId: String?, onProblem: (String) -> Unit) {
        find(id)?.let { call ->
            session(id).userEnded = true
            if (mapState(call.stateCompat()) == CallState.RINGING) call.reject(false, null) else call.disconnect()
        }
        scope.launch {
            // Only once the call is gone: Telecom would hold a call that didn't end and place the new one beside it,
            // leaving the caller being checked connected. A slow end gets one more disconnect and more time.
            if (!gone(id, HANG_UP_WAIT_MS)) {
                find(id)?.disconnect()
                if (!gone(id, HANG_UP_WAIT_MS)) {
                    str(R.string.verify_still_connected)?.let(onProblem)
                    return@launch
                }
            }
            redial(number, accountId)?.let(onProblem)
        }
    }

    /** Waits up to [timeoutMs] for the call [id] to leave Telecom; true once it has. */
    private suspend fun gone(id: String, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (find(id) != null) delay(100)
        true
    } ?: false

    fun saveNote(id: String, text: String) {
        val call = find(id) ?: return
        runCatching { deps.saveCallNote(call.details.handle?.schemeSpecificPart, call.details.connectTimeMillis, text) }
    }

    fun hangup(id: String) {
        val call = find(id) ?: return
        session(id).userEnded = true
        if (mapState(call.stateCompat()) == CallState.RINGING) call.reject(false, null) else call.disconnect()
    }

    fun toggleHold(id: String) {
        val call = find(id) ?: return
        if (mapState(call.stateCompat()) == CallState.HOLDING) call.unhold() else call.hold()
    }

    fun merge(id: String) {
        val call = find(id) ?: return
        val caps = call.details.callCapabilities
        when {
            (caps and Call.Details.CAPABILITY_MERGE_CONFERENCE) != 0 -> call.mergeConference()
            call.conferenceableCalls.isNotEmpty() -> call.conference(call.conferenceableCalls.first())
            else -> return
        }
        CallClock.haptic(CallHaptic.MERGE)
    }

    /** Swaps between an active and a held call (or within a conference). */
    fun swap(id: String) {
        val call = find(id) ?: return
        if ((call.details.callCapabilities and Call.Details.CAPABILITY_SWAP_CONFERENCE) != 0) {
            call.swapConference()
            CallClock.haptic(CallHaptic.SWAP)
            return
        }
        val held = calls.firstOrNull { it.parent == null && mapState(it.stateCompat()) == CallState.HOLDING } ?: return
        held.unhold()
        CallClock.haptic(CallHaptic.SWAP)
    }

    fun separate(childId: String) {
        find(childId)?.splitFromConference()
    }

    /** One short DTMF tone (hardware keys, accessibility): [KeyPressTracker.MIN_TONE_MS] long. */
    fun playDtmf(id: String, c: Char) {
        val token = startDtmf(id, c) ?: return
        stopDtmf(id, token, KeyPressTracker.MIN_TONE_MS)
    }

    private var dtmfToken = 0L

    /**
     * Starts the DTMF tone for [c] and keeps it playing until [stopDtmf] (held while the key is pressed, for phone
     * menus that want a long tone). A tone still playing from another key is stopped first (key roll-over).
     * Returns a token for [stopDtmf], or null when the call is gone.
     */
    fun startDtmf(id: String, c: Char): Long? {
        val call = find(id) ?: return null
        val s = session(id)
        if (s.dtmfToken != null) runCatching { call.stopDtmfTone() }
        runCatching { call.playDtmfTone(c) }
        val token = ++dtmfToken
        s.dtmfToken = token
        noteMenuKey(call, s, c)
        return token
    }

    // ---- Menu memory (I6) ----

    /** A digit sent in a connected outgoing call, timed from the connect (kept for menu memory when the call ends). */
    private fun noteMenuKey(call: Call, s: CallSession, c: Char) {
        val d = call.details
        if (call.parent != null || d.callDirection != Call.Details.DIRECTION_OUTGOING || d.connectTimeMillis <= 0) return
        if (s.menuPresses.size >= MENU_PRESS_LIMIT) return
        s.menuPresses += MenuPress(c, System.currentTimeMillis() - d.connectTimeMillis)
    }

    /** I6: hands the digits of a connected outgoing call to menu memory. Never for emergency calls or conferences. */
    private fun recordMenuKeys(ended: CallUi, s: CallSession) {
        if (s.menuPresses.isEmpty() || ended.connectTimeMillis <= 0) return
        if (ended.incoming || ended.isConference || ended.hidden) return
        val number = ended.number?.takeIf { MenuMemory.remembers(it, ended.isEmergency) } ?: return
        val presses = s.menuPresses.toList()
        runCatching { deps.onMenuKeys(number, ended.accountId, presses) }
    }

    /** The digits being replayed: in which call, which, and how many were sent so far. */
    data class MenuReplay(val callId: String, val steps: List<MenuStep>, val sent: Int, val token: Long)

    private val _menuReplay = MutableStateFlow<MenuReplay?>(null)
    val menuReplay: StateFlow<MenuReplay?> = _menuReplay.asStateFlow()
    private var replayJob: Job? = null
    private var replayToken = 0L

    /**
     * "Last time: 2 › 1 › 4": sends [steps] again in the call [id], each after its recorded pause
     * ([MenuMemory.replayDelays]). It stops when the call is no longer active, on [stopMenuReplay], or when the user
     * presses a key. Never in an emergency call.
     */
    fun replayMenu(id: String, steps: List<MenuStep>) {
        val call = find(id) ?: return
        if (steps.isEmpty() || !replayMayGoOn(call) || call.details.connectTimeMillis <= 0) return
        if (isEmergencyCall(call, call.details.handle?.schemeSpecificPart)) return
        stopMenuReplay()
        val delays = MenuMemory.replayDelays(steps, (System.currentTimeMillis() - call.details.connectTimeMillis).coerceAtLeast(0))
        val token = ++replayToken
        _menuReplay.value = MenuReplay(id, steps, 0, token)
        replayJob = scope.launch {
            try {
                steps.forEachIndexed { i, step ->
                    delay(delays[i])
                    val c = find(id) ?: return@launch
                    if (!replayMayGoOn(c)) return@launch
                    val tone = startDtmf(id, step.tone) ?: return@launch
                    stopDtmf(id, tone, MenuMemory.REPLAY_TONE_MS)
                    _menuReplay.value = MenuReplay(id, steps, i + 1, token)
                }
            } finally {
                if (_menuReplay.value?.token == token) _menuReplay.value = null
            }
        }
    }

    /**
     * L3: whether a replay may send its next key into [c]: still active on its own, not merged into a conference (the
     * keys would go to everyone in it) and no other call became the active one.
     */
    private fun replayMayGoOn(c: Call): Boolean = MenuMemory.replayGoesOn(
        active = mapState(c.stateCompat()) == CallState.ACTIVE,
        inConference = c.parent != null || c.children.isNotEmpty() || c.details.hasProperty(Call.Details.PROPERTY_CONFERENCE),
        otherActive = calls.any { it !== c && it.parent == null && mapState(it.stateCompat()) == CallState.ACTIVE },
    )

    /** L3: a call changed (merged, held, another answered): the replay stops at once if it may not go on. */
    private fun checkMenuReplay() {
        val id = _menuReplay.value?.callId ?: return
        val c = find(id)
        if (c == null || !replayMayGoOn(c)) stopMenuReplay()
    }

    /** Stops a replay (Stop, a key pressed by hand, the call ended). */
    fun stopMenuReplay() {
        replayJob?.cancel()
        replayJob = null
        _menuReplay.value = null
    }

    /** Stops the tone started with [token] after [afterMs], unless another key started a tone since. */
    fun stopDtmf(id: String, token: Long, afterMs: Long = 0) {
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            val s = sessions[id] ?: return@launch
            if (s.dtmfToken != token) return@launch
            s.dtmfToken = null
            find(id)?.let { runCatching { it.stopDtmfTone() } }
        }
    }

    fun postDialContinue(id: String, proceed: Boolean) {
        find(id)?.postDialContinue(proceed)
        sessions[id]?.postDial = null
        publish()
    }

    fun selectAccount(id: String, accountId: String) {
        val handle = handleFor(accountId) ?: return
        find(id)?.phoneAccountSelected(handle, false)
    }

    fun setMuted(muted: Boolean) {
        service?.setMuted(muted)
    }

    private val serviceRoutes: (AudioRoute) -> Unit = { service?.requestRoute(it) }

    /** Where a route request goes: the in-call service, which asks Telecom (tests listen here instead). */
    internal var routeRequests: (AudioRoute) -> Unit = serviceRoutes

    /**
     * Puts back what outlives a test's calls in this object: pending silence echoes (the test clock starts again, so
     * an earlier test's request would read as a fresh echo) and the route listener a test installed.
     */
    @VisibleForTesting
    internal fun resetForTest() {
        selfSilence.forget()
        routeRequests = serviceRoutes
    }

    fun setRoute(route: AudioRoute) {
        routeRequests(route)
    }

    fun toggleSpeaker() {
        _audio.value.speakerToggleTarget()?.let { setRoute(it) }
    }

    internal fun updateAudio(audio: AudioUi) {
        _audio.value = audio
        notifier.send(_calls.value)
        speakerOnStart()
    }

    // ---- Start calls on speaker ----

    /**
     * Settings › Calls › "Start calls on speaker" ([SpeakerOnStart]): decided once per call, as soon as it is answered
     * or dialled and the audio routes are known; the Speaker button stays the user's after that.
     */
    private fun speakerOnStart() {
        if (!::appContext.isInitialized) return
        val top = calls.filter { it.parent == null }
        if (top.isEmpty()) return
        val choice = runCatching { deps.speakerDefault() }.getOrDefault(SpeakerDefault.OFF)
        val audio = _audio.value
        top.forEach { c ->
            val s = sessions[idOf(c)]?.takeIf { !it.speakerDecided } ?: return@forEach
            val facts = speakerFacts(c, s, choice, audio, otherCall = top.any { it != c && mapState(it.stateCompat()) !in ENDING_STATES })
            when (if (facts == null) SpeakerOnStart.Step.LEAVE else SpeakerOnStart.step(facts)) {
                SpeakerOnStart.Step.WAIT -> Unit
                SpeakerOnStart.Step.LEAVE -> s.speakerDecided = true
                SpeakerOnStart.Step.TURN_ON -> {
                    s.speakerDecided = true
                    audio.routes.firstOrNull { it.type == RouteType.SPEAKER }?.let { setRoute(it) }
                }
            }
        }
    }

    /** What [SpeakerOnStart] decides on for [c]; null once the call is ending (nothing left to decide). */
    private fun speakerFacts(c: Call, s: CallSession, choice: SpeakerDefault, audio: AudioUi, otherCall: Boolean): SpeakerOnStart.Facts? {
        val st = mapState(c.stateCompat())
        if (st in ENDING_STATES) return null
        val d = c.details
        val number = d.handle?.schemeSpecificPart
        val hidden = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || number.isNullOrBlank()
        val incoming = d.callDirection == Call.Details.DIRECTION_INCOMING
        return SpeakerOnStart.Facts(
            choice = choice,
            started = if (incoming) st == CallState.ACTIVE else st in SPEAKER_DIAL_STATES,
            emergency = isEmergencyCall(c, number),
            savedCaller = when {
                s.info != null || s.savedPrivately || d.contactDisplayNameCompat() != null -> true
                hidden || s.lookupDone -> false
                else -> null
            },
            otherCall = otherCall,
            route = speakerRoute(audio.current?.type),
            speakerAvailable = audio.routes.any { it.type == RouteType.SPEAKER },
            holdMode = s.holdModeSince != 0L,
        )
    }

    private fun speakerRoute(type: RouteType?): SpeakerOnStart.Route = when (type) {
        null -> SpeakerOnStart.Route.UNKNOWN
        RouteType.EARPIECE -> SpeakerOnStart.Route.EARPIECE
        RouteType.SPEAKER -> SpeakerOnStart.Route.SPEAKER
        else -> SpeakerOnStart.Route.HEADSET
    }

    // ---- Send to another number (deflect) ----

    /** What the network lets this ringing call do ("Send to another number"). */
    private fun handOffFacts(c: Call): CallHandOff.Facts {
        val d = c.details
        return CallHandOff.Facts(
            ringing = mapState(c.stateCompat()) == CallState.RINGING,
            emergency = isEmergencyCall(c, d.handle?.schemeSpecificPart),
            conference = d.hasProperty(Call.Details.PROPERTY_CONFERENCE),
            canDeflect = (d.callCapabilities and Call.Details.CAPABILITY_SUPPORT_DEFLECT) != 0,
        )
    }

    /**
     * "Send to another number": the ringing call [id] goes on to [number] unanswered (deflect). False when it can't be
     * asked (not offered for this call, or not a number to send a call to, such as an emergency number); [onProblem]
     * hears when the network didn't do it.
     */
    fun deflect(id: String, number: String, onProblem: (String) -> Unit = {}): Boolean {
        val call = find(id) ?: return false
        val target = CallHandOff.target(number)?.takeIf { !isEmergency(it) } ?: return false
        if (!CallHandOff.deflectOffered(handOffFacts(call))) return false
        handingOff(id, HandOff.DEFLECTED)
        silenceRinger()
        ringer.stop()
        call.deflect(Uri.fromParts(PhoneAccount.SCHEME_TEL, target, null))
        watchHandOff(id, onProblem)
        return true
    }

    private fun handingOff(id: String, how: HandOff) {
        val s = session(id)
        // Ended on purpose: never "Call dropped" or a failure.
        s.userEnded = true
        s.handedOff = how
        autoAnswer.cancel(s)
        publish()
    }

    /**
     * Telecom reports no outcome for a deflect: when the call is still there after [HAND_OFF_WAIT_MS], the network
     * didn't send it on, so it is the user's again and [onProblem] says so.
     */
    private fun watchHandOff(id: String, onProblem: (String) -> Unit) {
        scope.launch {
            if (gone(id, HAND_OFF_WAIT_MS)) return@launch
            val s = sessions[id] ?: return@launch
            s.userEnded = false
            s.handedOff = null
            publish()
            str(R.string.handoff_deflect_failed)?.let(onProblem)
        }
    }

    private val DIALLING_STATES = setOf(CallState.NEW, CallState.DIALING, CallState.CONNECTING)
    private val FRONT_STATES = DIALLING_STATES + CallState.ACTIVE
    private val BUSY_STATES = FRONT_STATES + setOf(CallState.RINGING, CallState.SELECT_ACCOUNT)
    private val ANSWERED_STATES = setOf(CallState.ACTIVE, CallState.HOLDING)
    private val ENDING_STATES = setOf(CallState.DISCONNECTING, CallState.DISCONNECTED)

    /** An outgoing call starts on the speaker while it is dialled (the ringback is heard there too). */
    private val SPEAKER_DIAL_STATES = setOf(CallState.DIALING, CallState.CONNECTING, CallState.ACTIVE)
    private const val RESUME_DELAY_MS = 600L
    private const val ROUTE_SETTLE_MS = 1500L
    private const val PENDING_OUTGOING_MS = 8000L
    private const val LOOKUP_TIMEOUT_MS = 2000L

    /** Digits kept per call for menu memory (it keeps far fewer; this only bounds the memory used). */
    private const val MENU_PRESS_LIMIT = 64

    /** I11: how long silencing an unknown caller in the car waits for screening beyond its own timeout, and how often it looks. */
    private const val SCREEN_GRACE_MS = 500L
    private const val SCREEN_POLL_MS = 50L

    /** How long sending a call on may take before it counts as still the user's. */
    private const val HAND_OFF_WAIT_MS = 10_000L

    /** How long "Check it's really them" waits for the call to end before dialling. */
    private const val HANG_UP_WAIT_MS = 3000L

    /** How long "Block & decline" waits for the rule before declining anyway. */
    private const val BLOCK_TIMEOUT_MS = 1500L
}
