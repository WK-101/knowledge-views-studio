package app.parley.telecom

import android.annotation.SuppressLint
import app.parley.common.catching
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telecom.Call
import androidx.annotation.VisibleForTesting
import app.parley.common.BlockReason
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import app.parley.common.Decision
import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.AutoAnswer
import app.parley.common.calls.SelfSilenceEcho
import app.parley.common.calls.CallerHaptics
import app.parley.common.calls.CallBook
import app.parley.common.calls.CallDrop
import app.parley.common.calls.CallQualityFacts
import app.parley.common.calls.DropFacts
import app.parley.common.calls.CallFailure
import app.parley.common.calls.CallHandOff
import app.parley.common.calls.DriveProfile
import app.parley.common.calls.EmergencyPolicy
import app.parley.common.calls.EmergencyPolicy.Safeguard
import app.parley.common.calls.MenuStep
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingtoneSource
import app.parley.common.calltime.CallHaptic
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val texts = CallTexts(::contextOrNull)
    private val emergency = EmergencyCalls({ deps }, ::contextOrNull)
    private val accounts = CallAccounts(::contextOrNull)
    private val endRecorder = CallEndRecorder({ deps }, ::contextOrNull, texts, emergency)

    /** What the collaborators below see of the calls; the registry itself stays here. */
    private val live = object : LiveCalls {
        override val calls: List<Call> get() = this@CallManager.calls
        override val audio: AudioUi get() = _audio.value
        override fun idOf(call: Call) = this@CallManager.idOf(call)
        override fun find(id: String) = this@CallManager.find(id)
        override fun session(id: String) = this@CallManager.session(id)
        override fun sessionOrNull(id: String) = sessions[id]
        override fun isEmergencyCall(call: Call, number: String?) = emergency.isCall(call, number)
        override fun requestRoute(route: AudioRoute) = routeRequests(route)
        override fun publish() = this@CallManager.publish()
    }
    private val holdMode = HoldModeControl(scope, live)
    private val keys = MenuKeys(scope, live) { deps }
    private val speaker = SpeakerStart(live, { deps }) { ::appContext.isInitialized }
    private val ui = CallUiMapper(live, texts, accounts, emergency, drive, { book.heldSince(it) }, ::contextOrNull)

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

    /** Hold timers, last live states and which held call to resume (made on first use). */
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
        // A rescue call has no InCallService behind it: its screen-off at the ear follows the call screen here.
        RescueCall.updateProximity()
    }

    internal var onChanged: ((List<CallUi>) -> Unit)?
        get() = notifier.onChanged
        set(value) {
            notifier.onChanged = value
        }

    private lateinit var appContext: Context

    private fun contextOrNull(): Context? = if (::appContext.isInitialized) appContext else null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            // I11: the caller's name is never said over a call that stopped ringing.
            if (state != Call.STATE_RINGING) drive.quiet(idOf(call))
            keys.checkReplay()
            publish()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            keys.checkReplay()
            publish()
        }
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) {
            keys.checkReplay()
            publish()
        }
        override fun onParentChanged(call: Call, parent: Call?) {
            keys.checkReplay()
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
                emergency = emergency.isCall(call, number),
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
        // A real call always wins: a rescue call ringing or answered goes before this one is shown.
        RescueCall.yieldToRealCall()
        val id = idOf(call)
        val s = session(id)
        // A new call starts: an earlier call's failure banner (and its Retry) or "Blocked · Undo" card is stale.
        _lastEnded.value = null
        _declineBlock.value?.let { b -> if (calls.none { idOf(it) == b.callId }) _declineBlock.value = null }
        // A call that joins others (a second call, or the conference a merge creates) keeps the audio where it is:
        // "Start calls on speaker" only decides for a call that starts on its own.
        if (calls.any { mapState(it.stateCompat()) !in ENDING_STATES }) s.speakerDecided = true
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
        val emergency = emergency.facts(call, number, incoming)
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
                    if (incoming) savedCallerRings(call, s, found, number, accountId)
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
                emergency = EmergencyPolicy.bypasses(Safeguard.SCREENING, emergency.facts(call, number, incoming = true)),
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
            emergency = EmergencyPolicy.bypasses(Safeguard.SCREENING, emergency.facts(call, number, incoming = true)),
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

    /** A saved caller rings: what follows from knowing who it is. */
    private fun savedCallerRings(call: Call, s: CallSession, found: CallerDisplay, number: String, accountId: String?) {
        checkNeverCallsYou(call, s, number, accountId)
        // The caller's haptic caller ID: Parley's ringer takes over the ringing (or the tone playing).
        applyCallerVibration(call, s)
        considerAutoAnswer(call)
        announceInCar(call, s, found)
    }

    /**
     * "This number never calls you" for a saved caller: read off the main thread while it rings, within
     * [NEVER_CALLS_TIMEOUT_MS] (the contacts and one line's history); late, failing or an emergency call shows nothing.
     */
    private fun checkNeverCallsYou(call: Call, s: CallSession, number: String, accountId: String?) {
        if (emergency.isCall(call, number)) return
        scope.launch {
            val shows = withTimeoutOrNull(NEVER_CALLS_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { catching { deps.neverCallsYou(number, accountId) }.getOrDefault(false) }
            } == true
            if (!shows || !calls.contains(call)) return@launch
            s.neverCallsYou = true
            publish()
        }
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
        val base = ui.toUi(call)
        // An outgoing call that never went through: the reason and Retry stay on the call-ended screen.
        val failure = CallFailure.classify(endRecorder.endFacts(call, base, s, book.lastLiveState(id)))
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
            failure != null -> shown.copy(failure = failure, failureText = texts.failure(failure, call))
            drop != null -> shown.copy(drop = drop, dropText = texts.drop(drop, s), disconnectReason = str(R.string.call_drop_title))
            else -> shown
        }
        holdMode.stopReminders(id)
        val facts = endRecorder.quality(call, ended, s, drop, cause)
        keys.record(ended, s)
        if (keys.replay.value?.callId == id) keys.stopReplay()
        _lastEnded.value = ended
        // A call that failed before the caller lookup finished still shows the name on "Call ended".
        if (ended.name == null && !ended.hidden && !ended.number.isNullOrBlank()) lookUpEndedName(ended)
        lookUpSimTip(ended, facts)
        endRecorder.ended(call, ended, s)
        call.unregisterCallback(callback)
        calls -= call
        notifier.endTrace(s)
        // Everything known about the call goes at once.
        sessions.remove(id)
        if (calls.isEmpty()) {
            emergency.clear()
            // The routes belong to the call that ended: the next call waits for Telecom's own report.
            _audio.value = AudioUi()
        }
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
        // A SIM swapped while no call was up: its own number is read again.
        accounts.clear()
        book.clear()
        sessions.values.forEach { notifier.endTrace(it) }
        sessions.clear()
        _audio.value = AudioUi()
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
            ui.noteFacts(c, s, st)
            // Where an incoming call was answered, read again a moment later once the audio route has settled.
            if (st == CallState.ACTIVE && s.ringFacts != null && s.answeredRoute == null) {
                s.answeredRoute = RingSnapshot.route(_audio.value) ?: (AnswerRoute.EARPIECE to null)
                scope.launch {
                    delay(ROUTE_SETTLE_MS)
                    if (s.answeredRoute != null) RingSnapshot.route(_audio.value)?.let { s.answeredRoute = it }
                }
            }
        }
        val top = calls.filter { it.parent == null }.map { ui.toUi(it) }
        if (top.isNotEmpty()) _pendingOutgoing.value = null
        _calls.value = top
        CallClock.onCallsChanged(top)
        notifier.send(top)
        speaker.decide()
    }

    /** A UI text in the user's language; null before [add] (calls only arrive after it). */
    private fun str(res: Int): String? = texts.str(res)

    fun handleFor(accountId: String): PhoneAccountHandle? = accounts.handleFor(accountId)

    private fun find(id: String): Call? = calls.firstOrNull { idOf(it) == id } ?: calls.flatMap { it.children }.firstOrNull { idOf(it) == id }

    // ---- Actions ----

    fun answer(id: String) {
        if (RescueCall.owns(id)) return RescueCall.answer()
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
        if (!limits.mayEnd(mapState(call.stateCompat()), emergency.facts(call, call.details.handle?.schemeSpecificPart, incoming))) return
        session(id).endedByLimit = true
        call.disconnect()
    }

    /** Ends the call a "hang up" shortcut should end: the active one, else one being dialled, else a held one. */
    fun hangupForeground(): Boolean {
        val top = calls.filter { it.parent == null }
        if (top.isEmpty() && RescueCall.live) {
            RescueCall.end()
            return true
        }
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
        // A rescue call is declined like any other, but a reply is never sent and the messaging app never opens.
        if (RescueCall.owns(id)) return RescueCall.end()
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
        if (call.details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED || emergency.isCall(call, number)) return
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
        if (RescueCall.owns(id)) return RescueCall.silence()
        val s = session(id)
        s.silenced = true
        s.ignoredByUser = true
        autoAnswer.cancel(s)
        silenceRinger()
        ringer.stop()
        restoreBoost()
        publish()
    }

    // ---- Hold mode (I10, [HoldModeControl]) ----

    fun startHoldMode(id: String) = holdMode.start(id)

    fun stopHoldMode(id: String) = holdMode.stop(id)

    // ---- Call again, and calling a saved number back (P5, I3) ----

    /** Dismiss (or Call again) on the "Call dropped" card: it stays gone. */
    /** After a drop: a SIM that has gone better for this person, shown on the "Call dropped" card once it's known. */
    private fun lookUpSimTip(ended: CallUi, facts: CallQualityFacts?) {
        val number = ended.number?.takeIf { ended.drop != null && !ended.hidden && it.isNotBlank() } ?: return
        scope.launch {
            val tip = withTimeoutOrNull(SIM_TIP_TIMEOUT_MS) { catching { deps.simTipAfterDrop(number, facts) }.getOrNull() } ?: return@launch
            val now = _lastEnded.value
            if (now?.id == ended.id && now.drop != null) _lastEnded.value = now.copy(simTip = tip)
        }
    }

    /**
     * The SIM suggestion on the "Call dropped" card answered: [accept] remembers the SIM for the person, and "Call
     * again" then uses it.
     */
    fun answerSimTip(id: String, accept: Boolean) {
        val now = _lastEnded.value?.takeIf { it.id == id } ?: return
        val tip = now.simTip ?: return
        _lastEnded.value = now.copy(simTip = null, accountId = if (accept) tip.simId else now.accountId)
        runCatching { deps.answerSimTip(tip, accept) }
    }

    fun dismissDrop(id: String) {
        _lastEnded.value?.takeIf { it.id == id && it.drop != null }?.let { _lastEnded.value = it.copy(drop = null, dropText = null) }
    }

    /**
     * I3 "Check it's really them": ends the call [id] (declines it while it rings) and, once it's gone, dials [number],
     * the number saved for who the caller said they were. Runs here rather than on the screen, which closes as the
     * call ends. [onProblem] hears why the new call couldn't be placed.
     */
    fun hangUpAndCall(id: String, number: String, accountId: String?, onProblem: (String) -> Unit) {
        // Never a real call from a rescue call: it only ends.
        if (RescueCall.owns(id)) return RescueCall.end()
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
        if (RescueCall.owns(id)) return
        val call = find(id) ?: return
        runCatching { deps.saveCallNote(call.details.handle?.schemeSpecificPart, call.details.connectTimeMillis, text) }
    }

    fun hangup(id: String) {
        if (RescueCall.owns(id)) return RescueCall.end()
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

    // ---- The keypad and menu memory (I6, [MenuKeys]) ----

    /** One short DTMF tone (hardware keys, accessibility). */
    fun playDtmf(id: String, c: Char) = keys.play(id, c)

    /** Starts a DTMF tone held until [stopDtmf]; a token for it, or null when the call is gone. */
    fun startDtmf(id: String, c: Char): Long? = keys.start(id, c)

    /** Stops the tone started with [token] after [afterMs], unless another key started a tone since. */
    fun stopDtmf(id: String, token: Long, afterMs: Long = 0) = keys.stop(id, token, afterMs)

    /** The digits being replayed: in which call, which, and how many were sent so far. */
    data class MenuReplay(val callId: String, val steps: List<MenuStep>, val sent: Int, val token: Long)

    val menuReplay: StateFlow<MenuReplay?> get() = keys.replay

    /** "Last time: 2 › 1 › 4", sent again in the call [id]. */
    fun replayMenu(id: String, steps: List<MenuStep>) = keys.replay(id, steps)

    /** Stops a replay (Stop, a key pressed by hand, the call ended). */
    fun stopMenuReplay() = keys.stopReplay()

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
        if (rescueOnly()) return RescueCall.setMuted(muted)
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

    /** The user picked a route: from now on the audio is theirs, so "Start calls on speaker" never moves it. */
    fun setRoute(route: AudioRoute) {
        if (rescueOnly()) return RescueCall.setRoute(route)
        sessions.values.forEach { it.speakerDecided = true }
        routeRequests(route)
    }

    fun toggleSpeaker() {
        if (rescueOnly()) return RescueCall.toggleSpeaker()
        _audio.value.speakerToggleTarget()?.let { setRoute(it) }
    }

    /** Only a rescue call is up: the mute and audio buttons are its own (a real call would have made it give way). */
    private fun rescueOnly(): Boolean = calls.isEmpty() && RescueCall.live

    internal fun updateAudio(audio: AudioUi) {
        _audio.value = audio
        // Telecom reported the routes while these calls exist: they are this call's routes, not an earlier call's.
        sessions.values.forEach { it.routesReported = true }
        notifier.send(_calls.value)
        speaker.decide()
    }

    // ---- Send to another number (deflect) ----

    /**
     * "Send to another number": the ringing call [id] goes on to [number] unanswered (deflect). False when it can't be
     * asked (not offered for this call, or not a number to send a call to, such as an emergency number); [onProblem]
     * hears when the network didn't do it.
     */
    fun deflect(id: String, number: String, onProblem: (String) -> Unit = {}): Boolean {
        val call = find(id) ?: return false
        val target = CallHandOff.target(number)?.takeIf { !emergency.isNumber(it) } ?: return false
        if (!CallHandOff.deflectOffered(ui.handOffFacts(call))) return false
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

    private const val RESUME_DELAY_MS = 600L
    private const val ROUTE_SETTLE_MS = 1500L
    private const val PENDING_OUTGOING_MS = 8000L
    private const val LOOKUP_TIMEOUT_MS = 2000L

    /** The SIM suggestion reads the quality facts (sealed); it may come a moment after the card. */
    private const val SIM_TIP_TIMEOUT_MS = 5000L

    /** How long "This number never calls you" may take once the caller is known, before it is left out. */
    private const val NEVER_CALLS_TIMEOUT_MS = 1500L

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
