package app.parley.telecom

import android.telecom.Call
import app.parley.common.calls.SpeakerDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Start calls on speaker" on the call path: once per call, only instead of the earpiece. */
internal class SpeakerOnStartCallTest : CallPathTest() {
    private val earpiece = AudioRoute("e", RouteType.EARPIECE, "")
    private val speaker = AudioRoute("s", RouteType.SPEAKER, "")
    private val buds = AudioRoute("aa:bb", RouteType.BLUETOOTH, "Buds")
    private val asked = ArrayList<AudioRoute>()

    private fun listen(current: AudioRoute = earpiece, vararg routes: AudioRoute = arrayOf(earpiece, speaker)) {
        CallManager.routeRequests = { asked += it }
        CallManager.updateAudio(AudioUi(routes.toList(), current))
    }

    @Test fun offLeavesTheAudioAlone() {
        listen()
        active("t1")
        assertTrue(asked.isEmpty())
    }

    @Test fun alwaysTurnsTheSpeakerOnWhileDiallingOnce() {
        deps.speaker = SpeakerDefault.ALWAYS
        listen()
        dialling("t1")
        assertEquals(listOf(speaker), asked)
        // The user switched back to the earpiece: it stays there when the call connects.
        CallManager.updateAudio(AudioUi(listOf(earpiece, speaker), earpiece))
        telecom.update("t1") { it.copy(state = Call.STATE_ACTIVE, connectTimeMillis = System.currentTimeMillis()) }
        assertEquals(listOf(speaker), asked)
    }

    @Test fun anIncomingCallWaitsUntilItIsAnswered() {
        deps.speaker = SpeakerDefault.ALWAYS
        listen()
        ringing("t1")
        assertTrue(asked.isEmpty())
        telecom.update("t1") { it.copy(state = Call.STATE_ACTIVE, connectTimeMillis = System.currentTimeMillis()) }
        assertEquals(listOf(speaker), asked)
    }

    @Test fun aHeadsetWins() {
        deps.speaker = SpeakerDefault.ALWAYS
        listen(buds, earpiece, speaker, buds)
        dialling("t1")
        assertTrue(asked.isEmpty())
    }

    @Test fun neverForAnEmergencyCall() {
        deps.speaker = SpeakerDefault.ALWAYS
        listen()
        dialling("t1", number = "112")
        assertTrue(asked.isEmpty())
    }

    @Test fun neverWhileAnotherCallGoesOn() {
        deps.speaker = SpeakerDefault.ALWAYS
        listen(speaker)
        active("t1")
        // The user moved the first call to the earpiece; the second call doesn't take it back to the speaker.
        CallManager.updateAudio(AudioUi(listOf(earpiece, speaker), earpiece))
        asked.clear()
        dialling("t2")
        assertTrue(asked.isEmpty())
    }

    @Test fun unknownNumbersOnlyForNumbersThatAreNotSaved() {
        deps.speaker = SpeakerDefault.UNKNOWN_NUMBERS
        contact("+15550000003")
        listen()
        dialling("t1")
        assertTrue(asked.isEmpty())
        end("t1")
        dialling("t2", number = "+15550000099")
        assertEquals(listOf(speaker), asked)
    }

    @Test fun waitsForTheRoutesToBeKnown() {
        deps.speaker = SpeakerDefault.ALWAYS
        CallManager.routeRequests = { asked += it }
        dialling("t1")
        assertTrue(asked.isEmpty())
        CallManager.updateAudio(AudioUi(listOf(earpiece, speaker), earpiece))
        assertEquals(listOf(speaker), asked)
    }
}
