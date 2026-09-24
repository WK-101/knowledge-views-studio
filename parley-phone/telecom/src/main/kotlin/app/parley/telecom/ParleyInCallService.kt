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
    private var endpoints: List<CallEndpoint> = emptyList()
    private var currentEndpoint: CallEndpoint? = null
    private var muted = false
    private var btDevices = HashMap<String, BluetoothDevice>()

    override fun onCreate() {
        super.onCreate()
        notifier = CallNotifier(this)
        proximity = ProximityController(this)
        CallManager.service = this
        CallClock.attach(this)
        CallManager.onChanged = { calls ->
            notifier.update(calls)
            proximity.update(calls, CallManager.audio.value, CallManager.uiVisible)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.add(this, call)
        val state = CallManager.state.value.firstOrNull { it.id == CallManager.idOf(call) }?.state
        // Outgoing calls open the call screen straight away. Incoming calls are shown by
        // CallNotifier (full-screen notification, or a direct launch when that is not allowed).
        if (state in setOf(CallState.NEW, CallState.DIALING, CallState.CONNECTING, CallState.ACTIVE, CallState.HOLDING, CallState.SELECT_ACCOUNT)) {
            launchUi(false)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallManager.remove(call)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        launchUi(showDialpad)
    }

    override fun onSilenceRinger() {
        // Telecom rings for us; only our optional "unknown caller" ringtone needs stopping.
        CallManager.onSystemSilence()
    }

    override fun onDestroy() {
        CallManager.clear()
        CallClock.detach()
        CallManager.service = null
        CallManager.onChanged = null
        notifier.release()
        proximity.release()
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
            type = when (e.endpointType) {
                CallEndpoint.TYPE_EARPIECE -> RouteType.EARPIECE
                CallEndpoint.TYPE_SPEAKER -> RouteType.SPEAKER
                CallEndpoint.TYPE_BLUETOOTH -> RouteType.BLUETOOTH
                CallEndpoint.TYPE_WIRED_HEADSET -> RouteType.WIRED
                else -> RouteType.STREAMING
            },
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
        val routes = ArrayList<AudioRoute>()
        val mask = audioState.supportedRouteMask
        if (mask and CallAudioState.ROUTE_EARPIECE != 0) routes += AudioRoute("earpiece", RouteType.EARPIECE, "Phone")
        if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) routes += AudioRoute("wired", RouteType.WIRED, "Wired headset")
        if (mask and CallAudioState.ROUTE_SPEAKER != 0) routes += AudioRoute("speaker", RouteType.SPEAKER, "Speaker")
        btDevices.clear()
        var active: AudioRoute? = null
        if (mask and CallAudioState.ROUTE_BLUETOOTH != 0) {
            val devices = if (Build.VERSION.SDK_INT >= 28) audioState.supportedBluetoothDevices.toList() else emptyList()
            if (devices.isEmpty()) {
                routes += AudioRoute("bt", RouteType.BLUETOOTH, "Bluetooth")
            } else {
                devices.forEach { d ->
                    val name = try { d.name } catch (_: SecurityException) { null } ?: "Bluetooth"
                    btDevices[d.address] = d
                    val r = AudioRoute(d.address, RouteType.BLUETOOTH, name)
                    routes += r
                    if (Build.VERSION.SDK_INT >= 28 && audioState.activeBluetoothDevice?.address == d.address) active = r
                }
            }
        }
        val current = when (audioState.route) {
            CallAudioState.ROUTE_EARPIECE -> routes.firstOrNull { it.type == RouteType.EARPIECE }
            CallAudioState.ROUTE_SPEAKER -> routes.firstOrNull { it.type == RouteType.SPEAKER }
            CallAudioState.ROUTE_WIRED_HEADSET -> routes.firstOrNull { it.type == RouteType.WIRED }
            CallAudioState.ROUTE_BLUETOOTH -> active ?: routes.firstOrNull { it.type == RouteType.BLUETOOTH }
            else -> null
        }
        CallManager.updateAudio(AudioUi(routes, current, audioState.isMuted))
    }
}
