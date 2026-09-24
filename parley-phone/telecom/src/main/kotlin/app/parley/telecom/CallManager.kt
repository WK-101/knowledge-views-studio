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

    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val state: StateFlow<List<CallUi>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow(AudioUi())
    val audio: StateFlow<AudioUi> = _audio.asStateFlow()

    /** Last call that ended, for the brief "Call ended" screen. */
    private val _lastEnded = MutableStateFlow<CallUi?>(null)
    val lastEnded: StateFlow<CallUi?> = _lastEnded.asStateFlow()

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
        override fun onPostDialWait(call: Call, remainingPostDialSequence: String?) = publish()
    }

    private val postDial = HashMap<String, String>()

    private val idPrefix = "c" + android.os.SystemClock.elapsedRealtime().toString(36) + "_"

    fun idOf(call: Call): String = ids.getOrPut(call) { idPrefix + (++counter) }

    internal fun add(context: Context, call: Call) {
        appContext = context.applicationContext
        val id = idOf(call)
        calls += call
        call.registerCallback(object : Call.Callback() {
            override fun onPostDialWait(call: Call, remaining: String?) {
                if (remaining.isNullOrEmpty()) postDial.remove(id) else postDial[id] = remaining
                publish()
            }
        })
        call.registerCallback(callback)

        val number = call.details.handle?.schemeSpecificPart
        val hidden = call.details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val deps = TelecomGraph.dependencies
        val incoming = call.stateCompat() == Call.STATE_RINGING

        // Screening runs before we show any UI, bounded by a hard timeout so ringing is never held up.
        // Never screen right after an emergency call (call-backs must get through).
        val earlier = if (incoming) ScreeningGuard.recall(number) else null
        if (incoming && !ScreeningGuard.inEmergencyWindow(context) && (earlier != null || hidden || deps.screeningActive())) {
            screening += id
            scope.launch {
                val decision = earlier ?: withTimeoutOrNull(SCREEN_TIMEOUT_MS) { deps.screen(number, hidden, verificationOf(call)) }
                screening -= id
                if (decision is Decision.Block && calls.contains(call)) {
                    when (decision.action) {
                        BlockAction.REJECT -> rejectUnwanted(call)
                        BlockAction.SILENCE -> {
                            silenced += id
                            silenceRinger()
                        }
                    }
                } else if (id in unknownCallers) {
                    // The caller lookup finished first and held the custom tone back until screening allowed the call.
                    maybePlayUnknownRingtone(call, id)
                }
                publish()
            }
        }

        // Selecting a SIM automatically if the user pinned one for this number.
        if (call.stateCompat() == Call.STATE_SELECT_PHONE_ACCOUNT && number != null) {
            scope.launch {
                val pref = deps.preferredAccountId(number)
                val handle = pref?.let { handleFor(it) }
                if (handle != null) call.phoneAccountSelected(handle, false)
            }
        }

        if (number != null && !hidden) {
            scope.launch {
                val found = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { deps.callerInfo(number) }
                if (found != null) {
                    info[id] = found
                } else if (incoming) {
                    unknownCallers += id
                    maybePlayUnknownRingtone(call, id)
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
        val uri = runCatching { TelecomGraph.dependencies.unknownRingtone() }.getOrNull() ?: return
        if (id in screening || customRingerFor == id) return // played once screening allows the call
        if (!::appContext.isInitialized || id in silenced || !calls.contains(call) || call.stateCompat() != Call.STATE_RINGING) return
        val am = appContext.getSystemService(android.media.AudioManager::class.java)
        val nm = appContext.getSystemService(android.app.NotificationManager::class.java)
        if (am.ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL) return
        if (nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL) return
        if (calls.any { it != call && mapState(it.stateCompat()) == CallState.ACTIVE }) return
        val tone = runCatching { android.media.RingtoneManager.getRingtone(appContext, android.net.Uri.parse(uri)) }.getOrNull() ?: return
        silenceRinger()
        tone.audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (Build.VERSION.SDK_INT >= 28) tone.isLooping = true
        tone.play()
        customRinger = tone
        customRingerFor = id
    }

    internal fun onSystemSilence() = stopCustomRinger()

    private fun stopCustomRinger() {
        customRinger?.stop()
        customRinger = null
        customRingerFor = null
    }

    internal fun remove(call: Call) {
        val id = idOf(call)
        if (customRingerFor == id) stopCustomRinger()
        unknownCallers -= id
        val ended = toUi(call)
        _lastEnded.value = ended
        if (ended.isEmergency && ::appContext.isInitialized) ScreeningGuard.noteEmergencyCall(appContext)
        runCatching { TelecomGraph.dependencies.onCallEnded(ended.number, ended.incoming, ended.connectTimeMillis) }
        call.unregisterCallback(callback)
        calls -= call
        info.remove(id)
        silenced -= id
        screening -= id
        postDial.remove(id)
        publish()
    }

    internal fun clear() {
        calls.toList().forEach { it.unregisterCallback(callback) }
        calls.clear()
        accountLabels.clear()
        publish()
    }

    fun isScreening(id: String) = id in screening

    private fun publish() {
        customRingerFor?.let { rid ->
            val c = calls.firstOrNull { idOf(it) == rid }
            if (c == null || c.stateCompat() != Call.STATE_RINGING || rid in silenced) stopCustomRinger()
        }
        val top = calls.filter { it.parent == null }.map { toUi(it) }
        _calls.value = top
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
        return CallUi(
            id = id,
            state = state,
            number = number,
            hidden = hidden,
            name = found?.name ?: d.contactDisplayNameCompat() ?: d.callerDisplayName?.takeIf { it.isNotBlank() },
            label = found?.label,
            photoUri = found?.photoUri,
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
            accountLabel = accountLabel(d.accountHandle),
            verification = verificationOf(call),
            disconnectReason = d.disconnectCause?.let { disconnectText(it) },
            postDialWait = postDial[id],
            silenced = id in silenced,
            isEmergency = isEmergency(number),
            note = found?.note,
            lastCall = found?.lastCall,
            unknown = id in unknownCallers,
            location = if (id in unknownCallers && number != null) locations.getOrPut(id) { runCatching { TelecomGraph.dependencies.describeNumber(number) }.getOrNull().orEmpty() }.ifEmpty { null } else null,
        )
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
        silenceRinger()
        stopCustomRinger()
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
        }
    }

    /** Swaps between an active and a held call (or within a conference). */
    fun swap(id: String) {
        val call = find(id) ?: return
        if ((call.details.callCapabilities and Call.Details.CAPABILITY_SWAP_CONFERENCE) != 0) {
            call.swapConference()
            return
        }
        calls.firstOrNull { it.parent == null && mapState(it.stateCompat()) == CallState.HOLDING }?.unhold()
    }

    fun separate(childId: String) {
        find(childId)?.splitFromConference()
    }

    fun playDtmf(id: String, c: Char) {
        val call = find(id) ?: return
        call.playDtmfTone(c)
        scope.launch {
            delay(150)
            call.stopDtmfTone()
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

    private const val SCREEN_TIMEOUT_MS = 1500L
    private const val LOOKUP_TIMEOUT_MS = 2000L
}
