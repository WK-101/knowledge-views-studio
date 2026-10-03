package app.parley.telecom

import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio routes: the Speaker toggle, hold mode's speaker, and how Telecom's audio state is read. */
internal class AudioRoutingTest : CallPathTest() {
    private val earpiece = AudioRoute("e", RouteType.EARPIECE, "")
    private val speaker = AudioRoute("s", RouteType.SPEAKER, "")
    private val wired = AudioRoute("w", RouteType.WIRED, "")
    private val buds = AudioRoute("aa:bb", RouteType.BLUETOOTH, "Buds")

    @Test fun theSpeakerButtonTurnsTheSpeakerOn() {
        assertEquals(speaker, AudioUi(listOf(earpiece, speaker), earpiece).speakerToggleTarget())
        assertEquals(speaker, AudioUi(listOf(buds, earpiece, speaker), buds).speakerToggleTarget())
    }

    @Test fun offTheSpeakerItPrefersAHeadsetThenTheEarpiece() {
        assertEquals(buds, AudioUi(listOf(earpiece, speaker, wired, buds), speaker).speakerToggleTarget())
        assertEquals(wired, AudioUi(listOf(earpiece, speaker, wired), speaker).speakerToggleTarget())
        assertEquals(earpiece, AudioUi(listOf(earpiece, speaker), speaker).speakerToggleTarget())
    }

    @Test fun withoutASpeakerTheButtonDoesNothing() {
        assertNull(AudioUi(listOf(earpiece), earpiece).speakerToggleTarget())
        assertNull(AudioUi().speakerToggleTarget())
    }

    @Test fun toggleSpeakerAsksForTheRoute() {
        val asked = ArrayList<AudioRoute>()
        CallManager.routeRequests = { asked += it }
        CallManager.updateAudio(AudioUi(listOf(earpiece, speaker), earpiece))
        CallManager.toggleSpeaker()
        assertEquals(listOf(speaker), asked)
    }

    @Test fun holdModeTurnsTheSpeakerOnAndPutsTheAudioBackAfter() {
        val asked = ArrayList<AudioRoute>()
        CallManager.routeRequests = { asked += it }
        CallManager.updateAudio(AudioUi(listOf(buds, earpiece, speaker), buds))
        val c = active("t1")
        CallManager.startHoldMode(idOf(c))
        assertTrue(ui(c).holdModeSince > 0)
        assertEquals(listOf(speaker), asked)
        CallManager.updateAudio(AudioUi(listOf(buds, earpiece, speaker), speaker))
        CallManager.stopHoldMode(idOf(c))
        assertEquals(listOf(speaker, buds), asked)
        assertEquals(0L, ui(c).holdModeSince)
    }

    @Test fun afterHoldModeTheUsersOwnRouteChoiceStays() {
        val asked = ArrayList<AudioRoute>()
        CallManager.routeRequests = { asked += it }
        CallManager.updateAudio(AudioUi(listOf(buds, earpiece, speaker), earpiece))
        val c = active("t1")
        CallManager.startHoldMode(idOf(c))
        // The user picked the earbuds while on hold.
        CallManager.updateAudio(AudioUi(listOf(buds, earpiece, speaker), buds))
        CallManager.stopHoldMode(idOf(c))
        assertEquals(listOf(speaker), asked)
    }

    @Test fun endpointTypesReadAsRoutes() {
        assertEquals(RouteType.EARPIECE, AudioRouting.typeOf(CallEndpoint.TYPE_EARPIECE))
        assertEquals(RouteType.SPEAKER, AudioRouting.typeOf(CallEndpoint.TYPE_SPEAKER))
        assertEquals(RouteType.BLUETOOTH, AudioRouting.typeOf(CallEndpoint.TYPE_BLUETOOTH))
        assertEquals(RouteType.WIRED, AudioRouting.typeOf(CallEndpoint.TYPE_WIRED_HEADSET))
        assertEquals(RouteType.STREAMING, AudioRouting.typeOf(CallEndpoint.TYPE_STREAMING))
    }

    @Test fun olderAndroidsRoutesComeFromTheMask() {
        val mask = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER
        val a = AudioRouting.legacy(CallAudioState.ROUTE_SPEAKER, mask, muted = true, devices = emptyList(), activeAddress = null)
        assertEquals(listOf(RouteType.EARPIECE, RouteType.SPEAKER), a.routes.map { it.type })
        assertEquals(RouteType.SPEAKER, a.current?.type)
        assertTrue(a.muted)
    }

    @Test fun eachBluetoothDeviceIsARouteAndTheActiveOneIsCurrent() {
        val mask = CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_BLUETOOTH
        val devices = listOf(AudioRouting.Device("aa:bb", "Buds"), AudioRouting.Device("cc:dd", "Car"))
        val a = AudioRouting.legacy(CallAudioState.ROUTE_BLUETOOTH, mask, muted = false, devices = devices, activeAddress = "cc:dd")
        assertEquals(listOf("earpiece", "aa:bb", "cc:dd"), a.routes.map { it.key })
        assertEquals("Car", a.current?.name)
        assertTrue(a.hasExternal)
    }

    @Test fun bluetoothWithoutNamedDevicesIsOneRoute() {
        val a = AudioRouting.legacy(CallAudioState.ROUTE_BLUETOOTH, CallAudioState.ROUTE_BLUETOOTH, false, emptyList(), null)
        assertEquals(listOf(AudioRoute("bt", RouteType.BLUETOOTH, "")), a.routes)
        assertEquals("bt", a.current?.key)
    }

    @Test fun aRouteOutsideTheMaskIsNotCurrent() {
        val a = AudioRouting.legacy(CallAudioState.ROUTE_WIRED_HEADSET, CallAudioState.ROUTE_EARPIECE, false, emptyList(), null)
        assertNull(a.current)
    }
}
