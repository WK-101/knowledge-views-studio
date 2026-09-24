package app.parley.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import app.parley.common.BlockAction
import app.parley.common.Decision
import app.parley.common.Verification
import app.parley.common.calltime.CallHaptic
import app.parley.common.calls.AnswerRoute
import app.parley.common.calls.KeyPressTracker
import app.parley.common.calls.RingFacts
import app.parley.common.calls.RingOutcome
import app.parley.common.calls.RingtoneSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.WeakHashMap

/**
 * Single source of truth for live calls. Fed by [ParleyInCallService] on the main thread and
 * observed by the in-call UI, notifications and the main app's "return to call" banner.
 */
// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
object CallManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val calls = ArrayList<Call>()
    private val ids = WeakHashMap<Call, String>()
    private var counter = 0
    private val info = HashMap<String, CallerDisplay>()
    private val silenced = HashSet<String>()
    private val screening = HashSet<String>()
    private val unknownCallers = HashSet<String>()
    private val locations = HashMap<String, String>()
    private var customRinger: android.media.Ringtone? = null
    private var customRingerFor: String? = null
    /** Screening outcome per call: verdict for the caller card and ringer plan (B2, B24). */
    private val outcomes = HashMap<String, ScreenOutcome>()
    private val ringStartedAt = HashMap<String, Long>()
    private var boostedFor: String? = null
    /** Calls whose caller lookup found no contact or private contact (V4 post-call card). */
    private val noContact = HashSet<String>()
    /** Ringer state when each incoming call started ringing, completed when it ends (V9). */
    private val ringFacts = HashMap<String, RingFacts>()
    private val ignoredByUser = HashSet<String>()
    private val loudFor = HashSet<String>()
    private val tonePlayed = HashMap<String, Pair<RingtoneSource, String?>>()
    private val answeredRoute = HashMap<String, Pair<AnswerRoute, String?>>()

    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val state: StateFlow<List<CallUi>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow(AudioUi())
    val audio: StateFlow<AudioUi> = _audio.asStateFlow()

    /** Last call that ended, for the brief "Call ended" screen. */
    private val _lastEnded = MutableStateFlow<CallUi?>(null)
    val lastEnded: StateFlow<CallUi?> = _lastEnded.asStateFlow()

    /** A call Parley just asked Telecom to place, until it shows up (A10). */
    private val _pendingOutgoing = MutableStateFlow<PendingOutgoing?>(null)
    val pendingOutgoing: StateFlow<PendingOutgoing?> = _pendingOutgoing.asStateFlow()

    /** When each held call was put on hold (A2). */
    private val heldSince = HashMap<String, Long>()
    /** Last live state of each top-level call, to know whether the call that ended was the one in front (A2). */
    private val lastLiveState = HashMap<String, CallState>()
    private val quotaSilenced = HashSet<String>()
    private val endedByLimit = HashSet<String>()

    internal var service: ParleyInCallService? = null

    /** Whether the in-call screen is in the foreground (proximity screen-off only applies then). */
    @Volatile
    var uiVisible: Boolean = false
        private set

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        onChanged?.invoke(_calls.value)
    }
    internal var onChanged: ((List<CallUi>) -> Unit)? = null
    private lateinit var appContext: Context

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = publish()
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
        override fun onChildrenChanged(call: Call, children: MutableList<Call>) = publish()
        override fun onParentChanged(call: Call, parent: Call?) = publish()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = publish()
        override fun onPostDialWait(call: Call, remainingPostDialSequence: String?) {
            // One callback per call, unregistered in remove()/clear() (F28: a second, anonymous callback leaked).
            val id = idOf(call)
            if (remainingPostDialSequence.isNullOrEmpty()) postDial.remove(id) else postDial[id] = remainingPostDialSequence
            publish()
        }
    }

    private val postDial = HashMap<String, String>()

    private val idPrefix = "c" + android.os.SystemClock.elapsedRealtime().toString(36) + "_"

    fun idOf(call: Call): String = ids.getOrPut(call) { idPrefix + (++counter) }

    internal fun add(context: Context, call: Call) {
        appContext = context.applicationContext
        val id = idOf(call)
        calls += call
        call.registerCallback(callback)

        val number = call.details.handle?.schemeSpecificPart
        val hidden = call.details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val deps = TelecomGraph.dependencies
        val incoming = call.stateCompat() == Call.STATE_RINGING
        if (calls.size == 1) RingBoost.restoreAsync(appContext) // a boost left behind by a crash
        if (incoming) {
            val now = System.currentTimeMillis()
            ringStartedAt[id] = now
            ringFacts[id] = runCatching { RingSnapshot.capture(appContext, now) }.getOrElse { RingFacts(now) }
        }

        // Screening runs before we show any UI, bounded by a hard timeout so ringing is never held up.
        // Never screen right after an emergency call (call-backs must get through).
        val earlierOutcome = if (incoming) ScreeningGuard.recallOutcome(number) else null
        val accountId = call.details.accountHandle?.id
        // The screening service decided without knowing the SIM: re-check when per-SIM rules exist (B9), always when
        // it only let the call through because a SIM-limited allow rule might apply.
        val earlier = earlierOutcome?.takeIf {
            !(it.decision == Decision.Allow && accountId != null && (it.deferredToSim || runCatching { deps.simRulesActive() }.getOrDefault(true)))
        }
        val active = runCatching { deps.screeningActive() }.getOrDefault(true)
        if (incoming && !ScreeningGuard.inEmergencyWindow(context) && (earlier != null || earlierOutcome != null || hidden || active)) {
            screening += id
            scope.launch {
                val callerName = call.details.callerDisplayName?.takeIf { it.isNotBlank() }
                // Any failure lets the call ring (fail open), like a timeout.
                val outcome = earlier ?: withTimeoutOrNull(SCREEN_TIMEOUT_MS) {
                    runCatching { deps.screenCall(number, hidden, verificationOf(call), accountId, callerName) }.getOrNull()
                } ?: earlierOutcome
                val decision = outcome?.decision
                outcome?.let { outcomes[id] = it }
                screening -= id
                if (decision is Decision.Block && calls.contains(call)) {
                    when (decision.action) {
                        BlockAction.REJECT -> rejectUnwanted(call)
                        BlockAction.SILENCE -> {
                            silenced += id
                            silenceRinger()
                        }
                    }
                } else {
                    if (outcome?.ringLoud == true && calls.contains(call) && call.stateCompat() == Call.STATE_RINGING) {
                        RingBoost.boostAsync(appContext)
                        boostedFor = id
                        loudFor += id
                    }
                    // The caller lookup may have finished first and held the custom tone back until screening allowed the call.
                    if (id in unknownCallers || outcome?.ringtone != null) maybePlayUnknownRingtone(call, id)
                }
                publish()
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

        // An incoming call whose allowance is used up rings silently when the user asked for that (T6).
        if (incoming && number != null && !hidden && !isEmergency(number) && !ScreeningGuard.inEmergencyWindow(context)) {
            scope.launch {
                val silence = withTimeoutOrNull(SCREEN_TIMEOUT_MS) { runCatching { deps.silenceOverQuota(number, call.details.accountHandle?.id) }.getOrDefault(false) } == true
                if (silence && calls.contains(call) && call.stateCompat() == Call.STATE_RINGING && id !in silenced) {
                    silenced += id
                    quotaSilenced += id
                    silenceRinger()
                    stopCustomRinger()
                    publish()
                }
            }
        }

        if (number != null && !hidden) {
            scope.launch {
                var looked = false
                val found = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { runCatching { deps.callerInfo(number, accountId) }.also { looked = it.isSuccess }.getOrNull() }
                if (found != null) {
                    info[id] = found
                } else {
                    // Only a lookup that finished and found nobody: a timeout or a failure must never offer "Block" for a contact.
                    if (looked && calls.contains(call)) noContact += id
                    if (incoming) {
                        unknownCallers += id
                        maybePlayUnknownRingtone(call, id)
                    }
                }
                publish()
            }
        } else if (incoming) {
            unknownCallers += id
            scope.launch { maybePlayUnknownRingtone(call, id) }
        }
        publish()
    }

    /**
     * Distinct ringtone for unknown callers: silence Telecom's ringer and play our own, only in
     * normal ringer mode with Do Not Disturb off (so we never ring when the system wouldn't).
     */
    private fun maybePlayUnknownRingtone(call: Call, id: String) {
        // A ringtone chosen by screening (rule, label, repeat caller, likely spam) wins over the unknown-caller tone.
        val uri = outcomes[id]?.ringtone
            ?: (if (id in unknownCallers) runCatching { TelecomGraph.dependencies.unknownRingtone() }.getOrNull() else null)
            ?: return
        if (id in screening || customRingerFor == id) return // played once screening allows the call
        if (!::appContext.isInitialized || id in silenced || !calls.contains(call) || call.stateCompat() != Call.STATE_RINGING) return
        val am = appContext.getSystemService(android.media.AudioManager::class.java)
        val nm = appContext.getSystemService(android.app.NotificationManager::class.java)
        if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
        if (nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (calls.any { it != call && mapState(it.stateCompat()) == CallState.ACTIVE }) return
        val tone = runCatching { android.media.RingtoneManager.getRingtone(appContext, android.net.Uri.parse(uri)) }.getOrNull() ?: return
        tone.audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= 28) tone.isLooping = true
        // Claimed now so a second lookup/screening result doesn't start another tone while we wait.
        customRinger = tone
        customRingerFor = id
        // F20: silence Telecom first and wait until its ringtone has actually stopped (bounded), so the two never
        // overlap. Everything is re-checked afterwards: the call may have been answered, silenced or ended meanwhile.
        silenceRinger()
        scope.launch {
            val waitedUntil = android.os.SystemClock.elapsedRealtime() + RINGER_STOP_MAX_MS
            delay(RINGER_STOP_MIN_MS)
            while (systemRingtonePlaying(am) && android.os.SystemClock.elapsedRealtime() < waitedUntil) delay(RINGER_POLL_MS)
            if (customRinger !== tone || id in silenced || !calls.contains(call) || call.stateCompat() != Call.STATE_RINGING) {
                if (customRinger === tone) { customRinger = null; customRingerFor = null }
                return@launch
            }
            if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) {
                customRinger = null
                customRingerFor = null
                return@launch
            }
            runCatching { tone.play() }
            val o = outcomes[id]
            tonePlayed[id] = if (o?.ringtone != null) (o.ringtoneSource ?: RingtoneSource.RULE) to o.ringtoneName else RingtoneSource.UNKNOWN_CALLER to null
            startRingVibration(am)
        }
    }

    /** Another player (Telecom's ringer) is still playing a ringtone. Our own tone isn't playing yet at this point. */
    private fun systemRingtonePlaying(am: android.media.AudioManager): Boolean = runCatching {
        am.activePlaybackConfigurations.any { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE }
    }.getOrDefault(false)

    private var ringVibrator: android.os.Vibrator? = null

    /**
     * Silencing Telecom also stops its vibration, so vibrate like it would: only when the system's "Vibrate for calls"
     * is on (the tone only plays in normal ringer mode, where that setting decides).
     */
    private fun startRingVibration(am: android.media.AudioManager) {
        if (am.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT) return
        val cr = appContext.contentResolver
        val vibrateWhenRinging = am.ringerMode == android.media.AudioManager.RINGER_MODE_VIBRATE ||
            runCatching { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.VIBRATE_WHEN_RINGING, 0) != 0 }.getOrDefault(false)
        // Android 13+ also has a ring vibration intensity; 0 means off.
        val intensityOff = runCatching { android.provider.Settings.System.getInt(cr, "ring_vibration_intensity", -1) == 0 }.getOrDefault(false)
        if (!vibrateWhenRinging || intensityOff) return
        val v = if (Build.VERSION.SDK_INT >= 31) {
            appContext.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(android.os.Vibrator::class.java)
        } ?: return
        if (!v.hasVibrator()) return
        val effect = android.os.VibrationEffect.createWaveform(RING_VIBRATION, 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            }
            ringVibrator = v
        }
    }

    internal fun onSystemSilence() {
        stopCustomRinger()
        restoreBoost()
    }

    private fun restoreBoost() {
        boostedFor = null
        if (::appContext.isInitialized) RingBoost.restoreAsync(appContext)
    }

    private fun stopCustomRinger() {
        runCatching { customRinger?.stop() }
        customRinger = null
        customRingerFor = null
        ringVibrator?.let { runCatching { it.cancel() } }
        ringVibrator = null
    }

    internal fun remove(call: Call) {
        val id = idOf(call)
        if (customRingerFor == id) stopCustomRinger()
        if (boostedFor == id) restoreBoost()
        unknownCallers -= id
        val ended = toUi(call)
        _lastEnded.value = ended
        ringStartedAt.remove(id)?.let { started ->
            val connected = ended.connectTimeMillis > 0
            val rang = (if (connected) ended.connectTimeMillis else System.currentTimeMillis()) - started
            runCatching { TelecomGraph.dependencies.onRingFinished(ended.number, started, rang.coerceAtLeast(0), connected) }
            ringFacts.remove(id)?.let { f ->
                runCatching { TelecomGraph.dependencies.onRingFacts(ended.number.takeIf { !ended.hidden }, finishRingFacts(id, call, f, rang.coerceAtLeast(0), connected)) }
            }
        }
        outcomes.remove(id)
        val extraEmergency = !ended.incoming && !ended.number.isNullOrBlank() &&
            runCatching { TelecomGraph.dependencies.startsEmergencyWindow(ended.number) }.getOrDefault(false)
        if ((ended.isEmergency || extraEmergency) && ::appContext.isInitialized) ScreeningGuard.noteEmergencyCall(appContext)
        runCatching { TelecomGraph.dependencies.onCallEnded(ended.number, ended.incoming, ended.connectTimeMillis) }
        call.unregisterCallback(callback)
        calls -= call
        info.remove(id)
        silenced -= id
        screening -= id
        postDial.remove(id)
        quotaSilenced -= id
        endedByLimit -= id
        heldSince.remove(id)
        noContact -= id
        ringFacts.remove(id)
        ignoredByUser -= id
        loudFor -= id
        tonePlayed.remove(id)
        answeredRoute.remove(id)
        val wasInFront = lastLiveState.remove(id) in FRONT_STATES
        publish()
        if (wasInFront) resumeHeldIfAlone()
    }

    /**
     * When the call in front ends and exactly one held call is left, resume it (A2), unless another call is
     * ringing, dialling or active. Checked after a moment, since some networks resume on their own.
     */
    private fun resumeHeldIfAlone() {
        scope.launch {
            delay(RESUME_DELAY_MS)
            val top = calls.filter { it.parent == null }
            val busy = top.any { mapState(it.stateCompat()) in BUSY_STATES }
            val held = top.filter { mapState(it.stateCompat()) == CallState.HOLDING }
            if (!busy && held.size == 1) held.first().unhold()
        }
    }

    /** Re-sends the current state to the notification and proximity observers (e.g. a countdown changed). */
    internal fun notifyObservers() {
        onChanged?.invoke(_calls.value)
    }

    /** Called when Parley places a call, so the UI can say "Calling via Work SIM…" before the call exists. */
    fun expectOutgoing(number: String, simLabel: String?) {
        val p = PendingOutgoing(number, simLabel, android.os.SystemClock.elapsedRealtime())
        _pendingOutgoing.value = p
        scope.launch {
            delay(PENDING_OUTGOING_MS)
            if (_pendingOutgoing.value == p) _pendingOutgoing.value = null
        }
    }

    /** The InCallService unbound: no call is left, so nothing may keep ringing, stay boosted or linger in the maps. */
    internal fun clear() {
        stopCustomRinger()
        if (boostedFor != null) restoreBoost()
        calls.toList().forEach { it.unregisterCallback(callback) }
        calls.clear()
        accountLabels.clear()
        heldSince.clear()
        lastLiveState.clear()
        outcomes.clear()
        quotaSilenced.clear()
        endedByLimit.clear()
        ringStartedAt.clear()
        noContact.clear()
        ringFacts.clear()
        ignoredByUser.clear()
        loudFor.clear()
        tonePlayed.clear()
        answeredRoute.clear()
        silenced.clear()
        screening.clear()
        unknownCallers.clear()
        info.clear()
        locations.clear()
        postDial.clear()
        publish()
    }

    fun isScreening(id: String) = id in screening

    private fun publish() {
        customRingerFor?.let { rid ->
            val c = calls.firstOrNull { idOf(it) == rid }
            if (c == null || c.stateCompat() != Call.STATE_RINGING || rid in silenced) stopCustomRinger()
        }
        boostedFor?.let { rid ->
            val c = calls.firstOrNull { idOf(it) == rid }
            if (c == null || c.stateCompat() != Call.STATE_RINGING || rid in silenced) restoreBoost()
        }
        val now = android.os.SystemClock.elapsedRealtime()
        calls.filter { it.parent == null }.forEach { c ->
            val id = idOf(c)
            val st = mapState(c.stateCompat())
            if (st == CallState.HOLDING) heldSince.getOrPut(id) { now } else heldSince.remove(id)
            // V9: where an incoming call was answered, read again a moment later once the audio route has settled.
            if (st == CallState.ACTIVE && id in ringFacts && id !in answeredRoute) {
                answeredRoute[id] = RingSnapshot.route(_audio.value) ?: (AnswerRoute.EARPIECE to null)
                scope.launch {
                    delay(ROUTE_SETTLE_MS)
                    if (id in answeredRoute) RingSnapshot.route(_audio.value)?.let { answeredRoute[id] = it }
                }
            }
            if (st != CallState.DISCONNECTING && st != CallState.DISCONNECTED) lastLiveState[id] = st
        }
        val top = calls.filter { it.parent == null }.map { toUi(it) }
        if (top.isNotEmpty()) _pendingOutgoing.value = null
        _calls.value = top
        CallClock.onCallsChanged(top)
        onChanged?.invoke(top)
    }

    private fun toUi(call: Call): CallUi {
        val d = call.details
        val id = idOf(call)
        val found = info[id]
        val number = d.handle?.schemeSpecificPart
        val hidden = d.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val caps = d.callCapabilities
        fun can(c: Int) = (caps and c) != 0
        val conferenceable = call.conferenceableCalls.isNotEmpty()
        val state = mapState(call.stateCompat())
        // Before Telecom picks the account, the one Parley asked for is in the intent extras (A10).
        val account = d.accountHandle ?: requestedAccount(d)
        return CallUi(
            id = id,
            state = state,
            number = number,
            hidden = hidden,
            name = found?.name ?: d.contactDisplayNameCompat() ?: d.callerDisplayName?.takeIf { it.isNotBlank() },
            label = found?.label,
            photoUri = found?.photoUri,
            backgroundUri = found?.backgroundUri,
            contactId = found?.contactId,
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
            disconnectReason = if (id in endedByLimit) "Call time limit reached" else d.disconnectCause?.let { disconnectText(it) },
            postDialWait = postDial[id],
            silenced = id in silenced,
            silenceReason = if (id in quotaSilenced) "Silenced: your call time with this person is used up" else null,
            accountId = account?.id,
            heldSinceElapsed = heldSince[id] ?: 0L,
            isEmergency = isEmergency(number),
            note = found?.note,
            lastCall = found?.lastCall,
            unknown = id in unknownCallers,
            location = if (id in unknownCallers && number != null) locations.getOrPut(id) { runCatching { TelecomGraph.dependencies.describeNumber(number) }.getOrNull().orEmpty() }.ifEmpty { null } else null,
            verdict = outcomes[id]?.verdict,
            verdictWarn = outcomes[id]?.warn == true,
            noContact = id in noContact && found == null,
            accountNumber = accountNumber(account),
        )
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
        DisconnectCause.BUSY -> "Busy"
        DisconnectCause.MISSED -> "Missed call"
        DisconnectCause.REJECTED -> "Declined"
        DisconnectCause.ERROR -> c.label?.toString()?.ifBlank { null } ?: "Call failed"
        else -> c.label?.toString()?.ifBlank { null }
    }

    private fun isEmergency(number: String?): Boolean = try {
        number != null && ::appContext.isInitialized &&
            appContext.getSystemService(android.telephony.TelephonyManager::class.java).isEmergencyNumber(number)
    } catch (_: Exception) {
        false
    }

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

    /** The SIM's own number (V5), only on dual-SIM phones and only when Android knows it. */
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
     * Completes the ring facts captured when [call] started ringing (V9): which tone played, whether Parley kept it
     * quiet and why, and how the call ended.
     */
    private fun finishRingFacts(id: String, call: Call, f: RingFacts, rang: Long, connected: Boolean): RingFacts {
        val o = outcomes[id]
        val block = o?.decision as? Decision.Block
        val silenceReason = when {
            id !in silenced -> null
            id in quotaSilenced -> "Silenced: your call time with this person is used up"
            block?.action == BlockAction.SILENCE -> o.verdict?.takeIf { it.isNotBlank() } ?: "Silenced by your blocking rules"
            id in ignoredByUser -> "Ignored: you stopped the ringing"
            else -> "Silenced"
        }
        val tone = when {
            block != null -> RingtoneSource.NONE to null
            // "Ignore" after the tone started: the tone that played still counts.
            tonePlayed[id] != null -> tonePlayed.getValue(id)
            silenceReason != null && id !in ignoredByUser -> RingtoneSource.NONE to null
            else -> RingtoneSource.SYSTEM to null
        }
        val cause = call.details.disconnectCause?.code
        val outcome = when {
            connected -> RingOutcome.ANSWERED
            block?.action == BlockAction.REJECT -> RingOutcome.BLOCKED
            cause == DisconnectCause.ANSWERED_ELSEWHERE || cause == DisconnectCause.CALL_PULLED -> RingOutcome.ANSWERED_ELSEWHERE
            cause == DisconnectCause.REJECTED -> RingOutcome.DECLINED
            else -> RingOutcome.MISSED
        }
        val route = if (connected) answeredRoute[id] else null
        return f.copy(
            ringMillis = rang,
            ringtone = tone.first,
            ringtoneDetail = tone.second,
            silencedBy = silenceReason,
            ringLoud = id in loudFor,
            outcome = outcome,
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
        find(id)?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /**
     * Puts the active call on hold and answers the waiting one (A1). Telecom would end an active call that
     * can't be held, so the UI only offers this when holding is possible.
     */
    fun holdAndAnswer(id: String) {
        val waiting = find(id) ?: return
        calls.filter { it != waiting && it.parent == null && mapState(it.stateCompat()) == CallState.ACTIVE }
            .forEach { if ((it.details.callCapabilities and Call.Details.CAPABILITY_HOLD) != 0) it.hold() }
        waiting.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    /**
     * Ends the call whose time limit ran out (T5). Only that call: a waiting or held call is never touched, and
     * `TelecomManager.endCall()` (which picks a call on its own) is never used.
     */
    internal fun endForLimit(id: String) {
        val call = find(id) ?: return
        val st = mapState(call.stateCompat())
        if (st == CallState.RINGING || st == CallState.DISCONNECTED || st == CallState.DISCONNECTING) return
        if (isEmergency(call.details.handle?.schemeSpecificPart)) return
        // Never during the emergency window: this may be the operator calling back.
        if (::appContext.isInitialized && ScreeningGuard.inEmergencyWindow(appContext)) return
        endedByLimit += id
        call.disconnect()
    }

    /** Ends the call a "hang up" shortcut should end (A11): the active one, else one being dialled, else a held one. */
    fun hangupForeground(): Boolean {
        val top = calls.filter { it.parent == null }
        val pick = top.firstOrNull { mapState(it.stateCompat()) == CallState.ACTIVE }
            ?: top.firstOrNull { mapState(it.stateCompat()) in DIALLING_STATES }
            ?: top.firstOrNull { mapState(it.stateCompat()) == CallState.HOLDING }
            ?: return false
        pick.disconnect()
        return true
    }

    /** Ends the current active call, then answers the waiting one. */
    fun endAndAnswer(id: String) {
        val waiting = find(id) ?: return
        calls.filter { it != waiting && it.parent == null && mapState(it.stateCompat()) == CallState.ACTIVE }.forEach { it.disconnect() }
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
                    android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null))
                        .putExtra("sms_body", message)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun rejectUnwanted(call: Call) {
        if (Build.VERSION.SDK_INT >= 30) call.reject(Call.REJECT_REASON_UNWANTED) else call.reject(false, null)
    }

    fun silenceRinger() {
        try {
            appContext.getSystemService(TelecomManager::class.java).silenceRinger()
        } catch (_: Exception) {
        }
    }

    /** Stop ringing but leave the call waiting (the caller hears it ring until they give up). */
    fun ignore(id: String) {
        silenced += id
        ignoredByUser += id
        silenceRinger()
        stopCustomRinger()
        restoreBoost()
        publish()
    }

    fun saveNote(id: String, text: String) {
        val call = find(id) ?: return
        runCatching { TelecomGraph.dependencies.saveCallNote(call.details.handle?.schemeSpecificPart, call.details.connectTimeMillis, text) }
    }

    fun hangup(id: String) {
        val call = find(id) ?: return
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
    private var dtmfPlaying = false

    /**
     * Starts the DTMF tone for [c] and keeps it playing until [stopDtmf] (V7: held while the key is pressed, for phone
     * menus that want a long tone). A tone still playing from another key is stopped first (key roll-over).
     * Returns a token for [stopDtmf], or null when the call is gone.
     */
    fun startDtmf(id: String, c: Char): Long? {
        val call = find(id) ?: return null
        if (dtmfPlaying) runCatching { call.stopDtmfTone() }
        runCatching { call.playDtmfTone(c) }
        dtmfPlaying = true
        return ++dtmfToken
    }

    /** Stops the tone started with [token] after [afterMs], unless another key started a tone since. */
    fun stopDtmf(id: String, token: Long, afterMs: Long = 0) {
        scope.launch {
            if (afterMs > 0) delay(afterMs)
            if (token != dtmfToken || !dtmfPlaying) return@launch
            dtmfPlaying = false
            find(id)?.let { runCatching { it.stopDtmfTone() } }
        }
    }

    fun postDialContinue(id: String, proceed: Boolean) {
        find(id)?.postDialContinue(proceed)
        postDial.remove(id)
        publish()
    }

    fun selectAccount(id: String, accountId: String) {
        val handle = handleFor(accountId) ?: return
        find(id)?.phoneAccountSelected(handle, false)
    }

    fun setMuted(muted: Boolean) {
        service?.setMuted(muted)
    }

    fun setRoute(route: AudioRoute) {
        service?.requestRoute(route)
    }

    fun toggleSpeaker() {
        val a = _audio.value
        val target = if (a.current?.type == RouteType.SPEAKER) {
            a.routes.firstOrNull { it.type == RouteType.BLUETOOTH } ?: a.routes.firstOrNull { it.type == RouteType.WIRED }
                ?: a.routes.firstOrNull { it.type == RouteType.EARPIECE }
        } else {
            a.routes.firstOrNull { it.type == RouteType.SPEAKER }
        }
        target?.let { setRoute(it) }
    }

    internal fun updateAudio(audio: AudioUi) {
        _audio.value = audio
        onChanged?.invoke(_calls.value)
    }

    private val DIALLING_STATES = setOf(CallState.NEW, CallState.DIALING, CallState.CONNECTING)
    private val FRONT_STATES = DIALLING_STATES + CallState.ACTIVE
    private val BUSY_STATES = FRONT_STATES + setOf(CallState.RINGING, CallState.SELECT_ACCOUNT)
    private const val RESUME_DELAY_MS = 600L
    private const val ROUTE_SETTLE_MS = 1500L
    private const val PENDING_OUTGOING_MS = 8000L
    private const val SCREEN_TIMEOUT_MS = 1500L
    private const val LOOKUP_TIMEOUT_MS = 2000L
    /** F20: after silencing Telecom, wait at least this long, and at most the max for its ringtone to stop. */
    private const val RINGER_STOP_MIN_MS = 120L
    private const val RINGER_STOP_MAX_MS = 700L
    private const val RINGER_POLL_MS = 40L
    /** Like the platform ringer: 1 s on, 1 s off, repeated. */
    private val RING_VIBRATION = longArrayOf(0, 1000, 1000)
}
