package app.parley.telecom

import android.telecom.CallAudioState
import android.telecom.CallEndpoint

/** How Telecom's audio state reads as the call screen's routes ([AudioUi]). Pure, so it is tested without a call. */
internal object AudioRouting {
    /** A Bluetooth device that can carry the call: its address (the route's key) and its own name, or blank. */
    data class Device(val address: String, val name: String)

    /** A call endpoint's type (Android 14+) as a route type; anything else (streaming) is "other". */
    fun typeOf(endpointType: Int): RouteType = when (endpointType) {
        CallEndpoint.TYPE_EARPIECE -> RouteType.EARPIECE
        CallEndpoint.TYPE_SPEAKER -> RouteType.SPEAKER
        CallEndpoint.TYPE_BLUETOOTH -> RouteType.BLUETOOTH
        CallEndpoint.TYPE_WIRED_HEADSET -> RouteType.WIRED
        else -> RouteType.STREAMING
    }

    /**
     * The routes of Android 13 and older, from [CallAudioState]'s route and supported mask: each Bluetooth device by its
     * own name (one generic Bluetooth route when Android names none), the active device marked current.
     */
    fun legacy(route: Int, mask: Int, muted: Boolean, devices: List<Device>, activeAddress: String?): AudioUi {
        val routes = ArrayList<AudioRoute>()
        if (mask and CallAudioState.ROUTE_EARPIECE != 0) routes += AudioRoute("earpiece", RouteType.EARPIECE, "")
        if (mask and CallAudioState.ROUTE_WIRED_HEADSET != 0) routes += AudioRoute("wired", RouteType.WIRED, "")
        if (mask and CallAudioState.ROUTE_SPEAKER != 0) routes += AudioRoute("speaker", RouteType.SPEAKER, "")
        val bluetooth = when {
            mask and CallAudioState.ROUTE_BLUETOOTH == 0 -> emptyList()
            devices.isEmpty() -> listOf(AudioRoute("bt", RouteType.BLUETOOTH, ""))
            else -> devices.map { AudioRoute(it.address, RouteType.BLUETOOTH, it.name) }
        }
        routes += bluetooth
        val active = bluetooth.firstOrNull { activeAddress != null && it.key == activeAddress }
        val current = when (route) {
            CallAudioState.ROUTE_EARPIECE -> routes.firstOrNull { it.type == RouteType.EARPIECE }
            CallAudioState.ROUTE_SPEAKER -> routes.firstOrNull { it.type == RouteType.SPEAKER }
            CallAudioState.ROUTE_WIRED_HEADSET -> routes.firstOrNull { it.type == RouteType.WIRED }
            CallAudioState.ROUTE_BLUETOOTH -> active ?: routes.firstOrNull { it.type == RouteType.BLUETOOTH }
            else -> null
        }
        return AudioUi(routes, current, muted)
    }
}
