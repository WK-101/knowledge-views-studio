package app.parley.telecom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import androidx.annotation.RequiresApi
import app.parley.common.calls.ScreenAtEar
import app.parley.telecom.ui.InCallActivity

/**
 * Bound by Telecom while there are calls. Telecom binds it with foreground/top-app priority, so no
 * extra foreground service is needed. Everything here runs on the main thread and must be quick.
 */
// Telephony calls here are covered by the default-dialer role and each one handles SecurityException.
@SuppressLint("MissingPermission")
class ParleyInCallService : InCallService() {

    private lateinit var notifier: CallNotifier
    private lateinit var proximity: ProximityController
    private lateinit var flip: FlipSilencer
    private var endpoints: List<CallEndpoint> = emptyList()
    private var currentEndpoint: CallEndpoint? = null
    private var muted = false
    private var btDevices = HashMap<String, BluetoothDevice>()

    override fun onCreate() {
        super.onCreate()
        notifier = CallNotifier(this)
        proximity = ProximityController(this)
        flip = FlipSilencer(this)
        CallManager.service = this
        CallClock.attach(this)
        CallManager.onChanged = { calls ->
            notifier.update(calls)
            proximity.update(calls, CallManager.audio.value, CallManager.uiVisible, proximityMode())
            flip.update(calls, runCatching { TelecomGraph.dependencies.flipToSilence() }.getOrDefault(false))
        }
    }

    /** Settings › Calls › "Turn the screen off at your ear", read from memory. */
    private fun proximityMode(): ScreenAtEar.Mode = runCatching {
        val d = TelecomGraph.dependencies
        ScreenAtEar.mode(d.proximityEnabled(), d.proximityOnceAnswered())
    }.getOrDefault(ScreenAtEar.Mode.DURING_CALLS)

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.add(this, call)
        CallRtt.attach(this, call, CallManager.idOf(call))
        val state = CallManager.state.value.firstOrNull { it.id == CallManager.idOf(call) }?.state
        // Outgoing calls open the call screen straight away. Incoming calls are shown by
        // CallNotifier (full-screen notification, or a direct launch when that is not allowed).
        if (state in setOf(CallState.NEW, CallState.DIALING, CallState.CONNECTING, CallState.ACTIVE, CallState.HOLDING, CallState.SELECT_ACCOUNT)) {
            launchUi(false)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallRtt.detach(call, CallManager.idOf(call))
        CallManager.remove(call)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        launchUi(showDialpad)
    }

    override fun onSilenceRinger() {
        // The user silenced the ringer (volume or power key): Parley's own tone or vibration stops too. Telecom also
        // calls this back for Parley's own silenceRinger(); CallManager tells those echoes apart.
        CallManager.onSystemSilence()
    }

    override fun onDestroy() {
        CallManager.clear()
        CallRtt.release()
        CallClock.detach()
        CallManager.service = null
        CallManager.onChanged = null
        notifier.release()
        proximity.release()
        flip.stop()
        super.onDestroy()
    }

    private fun launchUi(showDialpad: Boolean) {
        try {
            startActivity(InCallActivity.intent(this, showDialpad).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    // ---- Audio ----

    fun requestRoute(route: AudioRoute) {
        if (Build.VERSION.SDK_INT >= 34) {
            val ep = endpoints.firstOrNull { it.identifier.toString() == route.key } ?: return
            requestCallEndpointChange(ep, mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) {}
                override fun onError(error: CallEndpointException) {}
            })
        } else {
            legacyRoute(route)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyRoute(route: AudioRoute) {
        when (route.type) {
            RouteType.EARPIECE -> setAudioRoute(CallAudioState.ROUTE_EARPIECE)
            RouteType.SPEAKER -> setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            RouteType.WIRED -> setAudioRoute(CallAudioState.ROUTE_WIRED_HEADSET)
            RouteType.BLUETOOTH -> {
                val dev = btDevices[route.key]
                if (dev != null && Build.VERSION.SDK_INT >= 28) requestBluetoothAudio(dev) else setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
            }
            RouteType.STREAMING -> Unit
        }
    }

    @RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        currentEndpoint = callEndpoint
        publishEndpoints()
    }

    @RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        endpoints = availableEndpoints.toList()
        publishEndpoints()
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        muted = isMuted
        if (Build.VERSION.SDK_INT >= 34) publishEndpoints()
    }

    @RequiresApi(34)
    private fun publishEndpoints() {
        fun map(e: CallEndpoint) = AudioRoute(
            key = e.identifier.toString(),
            type = AudioRouting.typeOf(e.endpointType),
            name = e.endpointName.toString(),
        )
        CallManager.updateAudio(AudioUi(endpoints.map(::map), currentEndpoint?.let(::map), muted))
    }

    @Deprecated("Replaced by CallEndpoint APIs on 34+")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        muted = audioState.isMuted
        if (Build.VERSION.SDK_INT >= 34) {
            publishEndpoints()
            return
        }
        btDevices.clear()
        val devices = if (Build.VERSION.SDK_INT >= 28) audioState.supportedBluetoothDevices.toList() else emptyList()
        devices.forEach { btDevices[it.address] = it }
        val named = devices.map { d -> AudioRouting.Device(d.address, try { d.name } catch (_: SecurityException) { null }.orEmpty()) }
        val active = if (Build.VERSION.SDK_INT >= 28) audioState.activeBluetoothDevice?.address else null
        CallManager.updateAudio(AudioRouting.legacy(audioState.route, audioState.supportedRouteMask, audioState.isMuted, named, active))
    }
}
