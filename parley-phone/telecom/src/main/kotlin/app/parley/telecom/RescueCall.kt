package app.parley.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Vibrator
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.annotation.VisibleForTesting
import app.parley.common.Verification
import app.parley.common.calls.RescuePlan
import app.parley.common.calls.RingVibration
import app.parley.telecom.ui.InCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Who a rescue call shows, resolved by the app the way a real call from them would show: their name, photo and label,
 * the tone and vibration their call rings with, and a sound the user picked to hear once answered (none: silence).
 */
class RescueCaller(
    val name: String?,
    val number: String? = null,
    val label: String? = null,
    val photoUri: String? = null,
    val backgroundUri: String? = null,
    val contactId: Long? = null,
    val lookupKey: String? = null,
    val subtitle: String? = null,
    val pronouns: String? = null,
    /** The tone their real call rings with (their own, a label's), or null for the phone's default. */
    val ringtone: String? = null,
    /** Their haptic caller ID as a repeating waveform, or null for the usual ring vibration. */
    val vibration: LongArray? = null,
    /** A sound file the user picked, played at the ear once answered; null: silence. */
    val clip: String? = null,
)

/** What the call screen shows of a rescue call: the call while it rings or is answered, then briefly as ended. */
data class RescueState(val call: CallUi?, val ended: CallUi?, val audio: AudioUi)

/**
 * Rescue call: a believable incoming call on Parley's own call screen, to leave a situation. It is not a call. No
 * Telecom call or connection exists, nothing is dialled, nothing reaches the network, and nothing is kept: not in the
 * call log or Recents, call notes, the call archive, statistics, case files, Recall or backups. [CallManager] hands
 * every action on its id (answer, decline, hang up, mute, speaker) here before anything reaches Telecom or the app.
 *
 * A real call always wins ([RescuePlan.mayRing]): it never starts while one is up, and gives way at once when one
 * arrives ([CallManager] tells it, and a watch asks Android every second, for calls another phone app handles).
 *
 * It rings the way the person's call would: their tone and vibration, following the ringer switch (on vibrate it only
 * vibrates, on silent only the screen shows). Do Not Disturb doesn't stop it, since the user asked for this call; the
 * tone plays on the ring stream, so Android may still keep it quiet. Main thread only.
 */
// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
object RescueCall {
    /** Every rescue call's id starts with this; a real call's never does. */
    const val ID_PREFIX = "rescue_"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<RescueState?>(null)
    val state: StateFlow<RescueState?> = _state.asStateFlow()

    private var appContext: Context? = null
    private var caller: RescueCaller? = null
    private var counter = 0
    private var tone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var clip: RescueClip? = null

    /** The screen off at the ear once answered on the earpiece, as on a real call (same setting, same wake lock). */
    private var proximity: ProximityController? = null
    private var watch: Job? = null
    private var clearEnded: Job? = null

    /** `elapsedRealtime` when it started ringing, and when it was answered (0: not yet). */
    private var ringSince = 0L
    private var answeredAt = 0L

    private val telecomInCall: (Context) -> Boolean = { c ->
        runCatching { c.getSystemService(TelecomManager::class.java)?.isInCall == true }.getOrDefault(false)
    }

    /** Asks Android whether a call is up, for calls Parley doesn't see itself (another phone app's); tests replace it. */
    @VisibleForTesting
    internal var systemInCall: (Context) -> Boolean = telecomInCall

    /** Whether [id] names a rescue call (live, ended or long gone): never a real call's. */
    fun owns(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** A rescue call rings or is answered. */
    val live: Boolean get() = _state.value?.call?.isLive == true

    /** No real call is up (Parley's or another phone app's): a rescue call may ring. */
    fun mayRing(context: Context): Boolean =
        RescuePlan.mayRing(CallManager.state.value.count { it.isLive }, systemInCall(context.applicationContext))

    /**
     * Rings the rescue call from [who]. False, and nothing happens, while a real call is up. One already up is
     * replaced. The call screen opens through the full-screen notification, like a real call's (a heads-up while the
     * phone is in use); directly when Android doesn't allow the full-screen alert.
     */
    fun start(context: Context, who: RescueCaller): Boolean {
        val app = context.applicationContext
        if (!mayRing(app)) return false
        stopAll(app)
        appContext = app
        caller = who
        val call = ringingUi(ID_PREFIX + (++counter), who, app)
        ringSince = SystemClock.elapsedRealtime()
        answeredAt = 0L
        _state.value = RescueState(call, null, AUDIO)
        ring(app, who)
        val direct = !RescueNotifier.incoming(app, call)
        if (direct) runCatching { app.startActivity(InCallActivity.intent(app, false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        watch(call.id)
        return true
    }

    /** Answer: the ringing stops, the call screen counts the time, and the picked sound (if any) plays at the ear. */
    fun answer() {
        val s = _state.value ?: return
        val c = s.call?.takeIf { it.state == CallState.RINGING } ?: return
        val app = appContext ?: return
        stopRinging()
        answeredAt = SystemClock.elapsedRealtime()
        val active = c.copy(state = CallState.ACTIVE, connectTimeMillis = System.currentTimeMillis(), silenced = false)
        _state.value = s.copy(call = active)
        RescueNotifier.ongoing(app, active)
        caller?.clip?.let { uri -> clip = RescueClip(app).also { it.play(uri, speaker = s.audio.current?.type == RouteType.SPEAKER) } }
        updateProximity()
    }

    /** Decline while it rings, or hang up once answered: "Call ended" for a moment, then nothing is left. */
    fun end() {
        val s = _state.value ?: return
        val c = s.call ?: return
        val app = appContext
        stopAll(app)
        val ended = c.copy(state = CallState.DISCONNECTED)
        _state.value = RescueState(null, ended, s.audio)
        clearEnded = scope.launch {
            delay(ENDED_LINGER_MS)
            if (_state.value?.ended?.id == ended.id) _state.value = null
        }
    }

    /** Stops the ringing but lets it ring on silently (the call screen's or a key's "stop ringing"). */
    fun silence() {
        val s = _state.value ?: return
        val c = s.call?.takeIf { it.state == CallState.RINGING } ?: return
        stopRinging()
        _state.value = s.copy(call = c.copy(silenced = true))
    }

    fun setMuted(muted: Boolean) {
        _state.value?.takeIf { it.call != null }?.let { _state.value = it.copy(audio = it.audio.copy(muted = muted)) }
    }

    fun toggleSpeaker() {
        _state.value?.audio?.speakerToggleTarget()?.let(::setRoute)
    }

    fun setRoute(route: AudioRoute) {
        val s = _state.value?.takeIf { it.call != null } ?: return
        val r = s.audio.routes.firstOrNull { it.key == route.key } ?: return
        _state.value = s.copy(audio = s.audio.copy(current = r))
        clip?.route(speaker = r.type == RouteType.SPEAKER)
        updateProximity()
    }

    /**
     * Holds the proximity screen-off while the answered call is on the earpiece with the call screen in front, so a
     * cheek can't press End call or Mute; let go otherwise. [CallManager] calls it when the call screen comes and goes.
     */
    internal fun updateProximity() {
        val s = _state.value
        val call = s?.call
        val app = appContext
        if (call == null || app == null) {
            proximity?.release()
            return
        }
        val p = proximity ?: ProximityController(app).also { proximity = it }
        p.update(listOf(call), s.audio, CallManager.uiVisible, screenAtEarMode())
    }

    /** A real call arrived: the rescue call goes at once, with no "Call ended", so the real call's screen takes over. */
    fun yieldToRealCall() {
        if (_state.value == null) return
        stopAll(appContext)
        _state.value = null
    }

    /** Ends the ringing, the sound, the notifications and the watch (the state is left to the caller). */
    private fun stopAll(context: Context?) {
        watch?.cancel()
        watch = null
        clearEnded?.cancel()
        clearEnded = null
        stopRinging()
        clip?.stop()
        clip = null
        proximity?.release()
        context?.let { RescueNotifier.cancel(it) }
    }

    /**
     * Once a second while it is up: a real call takes over; unanswered after [RescuePlan.RING_MS] it stops ringing
     * (like a call that went to voicemail, but no missed call is kept); answered, it ends by itself after
     * [RescuePlan.MAX_CALL_MS].
     */
    private fun watch(id: String) {
        watch?.cancel()
        watch = scope.launch {
            while (isActive) {
                delay(WATCH_MS)
                val c = _state.value?.call?.takeIf { it.id == id } ?: return@launch
                val app = appContext ?: return@launch
                val now = SystemClock.elapsedRealtime()
                when {
                    !mayRing(app) -> yieldToRealCall()
                    c.state == CallState.RINGING && now - ringSince >= RescuePlan.RING_MS -> end()
                    c.state == CallState.ACTIVE && answeredAt > 0 && now - answeredAt >= RescuePlan.MAX_CALL_MS -> end()
                }
            }
        }
    }

    /** Rings as the person's call would: tone and vibration in normal mode, vibration only on vibrate, nothing on silent. */
    private fun ring(context: Context, who: RescueCaller) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> {
                val uri = who.ringtone ?: Settings.System.DEFAULT_RINGTONE_URI?.toString()
                tone = uri?.let { u -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(u)) }.getOrNull() }?.also { t ->
                    runCatching {
                        t.audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        if (Build.VERSION.SDK_INT >= 28) t.isLooping = true
                        t.play()
                    }
                }
                vibrateFor(context, who)
            }
            AudioManager.RINGER_MODE_VIBRATE -> vibrateFor(context, who)
            else -> Unit
        }
    }

    /**
     * The ring vibration a real call from [who] would have ([RingVibration.decide], as Parley's ringer decides it), as
     * an alarm through Do Not Disturb, which a ringtone vibration wouldn't get past.
     */
    private fun vibrateFor(context: Context, who: RescueCaller) {
        val d = CallRinger.decide(context, who.vibration, rescue = true) as? RingVibration.Decision.Vibrate ?: return
        vibrator = CallRinger.vibrate(context, d.timings, d.usage)
    }

    private fun stopRinging() {
        tone?.let { runCatching { it.stop() } }
        tone = null
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
    }

    /** The call as the call screen shows a ringing call from [who]: a saved caller, with nothing a real call could do. */
    private fun ringingUi(id: String, who: RescueCaller, context: Context) = CallUi(
        id = id,
        state = CallState.RINGING,
        number = who.number,
        hidden = false,
        name = who.name,
        label = who.label,
        photoUri = who.photoUri,
        backgroundUri = who.backgroundUri,
        contactId = who.contactId,
        incoming = true,
        connectTimeMillis = 0,
        isConference = false,
        children = emptyList(),
        canHold = false,
        canMerge = false,
        canSwap = false,
        canMute = true,
        canSeparate = false,
        canDisconnectChild = false,
        canRespondViaText = false,
        accountLabel = null,
        verification = Verification.NOT_VERIFIED,
        disconnectReason = null,
        postDialWait = null,
        silenced = false,
        isEmergency = false,
        lookupKey = who.lookupKey,
        subtitle = who.subtitle,
        pronouns = who.pronouns,
        savedCaller = true,
        fallbackTitle = context.getString(R.string.call_unknown),
        simulated = true,
    )

    /** Forgets everything between tests. */
    @VisibleForTesting
    internal fun resetForTest() {
        stopAll(appContext)
        _state.value = null
        caller = null
        proximity = null
        systemInCall = telecomInCall
    }

    /** The earpiece and the speaker: a rescue call has no headset routes of its own. */
    private val AUDIO = AudioUi(
        routes = listOf(AudioRoute("earpiece", RouteType.EARPIECE, ""), AudioRoute("speaker", RouteType.SPEAKER, "")),
        current = AudioRoute("earpiece", RouteType.EARPIECE, ""),
    )

    private const val WATCH_MS = 1000L

    /** "Call ended" shows this long (the call screen closes a little before). */
    private const val ENDED_LINGER_MS = 3000L
}
