package app.parley.telecom

import android.telecom.Call
import app.parley.common.calls.SpeakerDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Start calls on speaker" on the call path: once per call, only instead of the earpiece, and only from the routes
 * Telecom reports for this call (never an earlier call's).
 */
internal class SpeakerOnStartCallTest : CallPathTest() {
    private val earpiece = AudioRoute("e", RouteType.EARPIECE, "")
    private val speaker = AudioRoute("s", RouteType.SPEAKER, "")
    private val buds = AudioRoute("aa:bb", RouteType.BLUETOOTH, "Buds")
    private val asked = ArrayList<AudioRoute>()

    @Before fun listen() {
        CallManager.routeRequests = { asked += it }
    }

    /** Telecom reports where the call's audio goes. */
    private fun routes(current: AudioRoute = earpiece, vararg routes: AudioRoute = arrayOf(earpiece, speaker)) {
        CallManager.updateAudio(AudioUi(routes.toList(), current))
    }

    @Test fun offLeavesTheAudioAlone() {
        active("t1")
        routes()
        assertTrue(asked.isEmpty())
    }

    @Test fun alwaysTurnsTheSpeakerOnWhileDiallingOnce() {
        deps.speaker = SpeakerDefault.ALWAYS
        dialling("t1")
        routes()
        assertEquals(listOf(speaker), asked)
        // The user switched back to the earpiece: it stays there when the call connects.
        routes(earpiece)
        telecom.update("t1") { it.copy(state = Call.STATE_ACTIVE, connectTimeMillis = System.currentTimeMillis()) }
        assertEquals(listOf(speaker), asked)
    }

    @Test fun anIncomingCallWaitsUntilItIsAnswered() {
        deps.speaker = SpeakerDefault.ALWAYS
        ringing("t1")
        routes()
        assertTrue(asked.isEmpty())
        telecom.update("t1") { it.copy(state = Call.STATE_ACTIVE, connectTimeMillis = System.currentTimeMillis()) }
        assertEquals(listOf(speaker), asked)
    }

    @Test fun aHeadsetWins() {
        deps.speaker = SpeakerDefault.ALWAYS
        dialling("t1")
        routes(buds, earpiece, speaker, buds)
        assertTrue(asked.isEmpty())
    }

    @Test fun neverForAnEmergencyCall() {
        deps.speaker = SpeakerDefault.ALWAYS
        dialling("t1", number = "112")
        routes()
        assertTrue(asked.isEmpty())
    }

    @Test fun neverWhileAnotherCallGoesOn() {
        deps.speaker = SpeakerDefault.ALWAYS
        active("t1")
        routes(speaker)
        // The user moved the first call to the earpiece; the second call doesn't take it back to the speaker.
        routes(earpiece)
        asked.clear()
        dialling("t2")
        routes(earpiece)
        assertTrue(asked.isEmpty())
    }

    @Test fun unknownNumbersOnlyForNumbersThatAreNotSaved() {
        deps.speaker = SpeakerDefault.UNKNOWN_NUMBERS
        contact("+15550000003")
        dialling("t1")
        routes()
        assertTrue(asked.isEmpty())
        end("t1")
        dialling("t2", number = "+15550000099")
        routes()
        assertEquals(listOf(speaker), asked)
    }

    @Test fun aPrivateContactHiddenByDiscreetModeIsStillSaved() {
        deps.speaker = SpeakerDefault.UNKNOWN_NUMBERS
        deps.savedHidden += "+15550000003"
        dialling("t1")
        routes()
        assertTrue(asked.isEmpty())
    }

    @Test fun waitsForTheRoutesToBeKnown() {
        deps.speaker = SpeakerDefault.ALWAYS
        dialling("t1")
        assertTrue(asked.isEmpty())
        routes()
        assertEquals(listOf(speaker), asked)
    }

    @Test fun anOutgoingCallWaitsForItsOwnRoutesNotOnesReportedBeforeIt() {
        deps.speaker = SpeakerDefault.ALWAYS
        // Routes reported while no call was up (or left from an earlier service) say nothing about this call.
        routes(earpiece)
        dialling("t1")
        assertTrue(asked.isEmpty())
        // Only half reported (the current route, no list yet, as with the first endpoint callback): still waiting.
        CallManager.updateAudio(AudioUi(emptyList(), earpiece))
        assertTrue(asked.isEmpty())
        routes()
        assertEquals(listOf(speaker), asked)
    }

    @Test fun theLastCallEndingOnTheSpeakerDoesNotStopTheNextOne() {
        deps.speaker = SpeakerDefault.ALWAYS
        dialling("t1")
        routes()
        routes(speaker)
        end("t1")
        asked.clear()
        // The next call: until Telecom reports its routes, the old "on the speaker" mustn't decide it.
        dialling("t2")
        assertTrue(asked.isEmpty())
        routes(earpiece)
        assertEquals(listOf(speaker), asked)
    }

    @Test fun aHeadsetConnectedSinceTheLastCallKeepsTheNextOne() {
        deps.speaker = SpeakerDefault.ALWAYS
        active("t1")
        routes(earpiece)
        end("t1")
        asked.clear()
        // Earbuds connected between the calls: the earpiece and speaker left from the first call mustn't move the audio.
        dialling("t2")
        assertTrue(asked.isEmpty())
        routes(buds, earpiece, speaker, buds)
        assertTrue(asked.isEmpty())
    }

    @Test fun theAudioIsForgottenWhenTheCallsEnd() {
        active("t1")
        routes(speaker)
        end("t1")
        assertEquals(AudioUi(), CallManager.audio.value)
    }

    @Test fun theUsersOwnChoiceWhileTheLookupRunsStays() {
        deps.speaker = SpeakerDefault.UNKNOWN_NUMBERS
        deps.savedDelayMs = 1000
        dialling("t1", number = "+15550000099")
        routes()
        // Before the lookup answers, the user turns the speaker on and off again.
        CallManager.setRoute(speaker)
        CallManager.setRoute(earpiece)
        FakeTelecom.advance(1500)
        routes()
        assertEquals(listOf(speaker, earpiece), asked)
    }

    @Test fun mergingCallsLeavesTheAudioWhereItIs() {
        deps.speaker = SpeakerDefault.UNKNOWN_NUMBERS
        val caps = Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE
        add(FakeTelecom.Spec("p1", Call.STATE_ACTIVE, "+15550000011", incoming = false, capabilities = caps, connectTimeMillis = 1))
        add(FakeTelecom.Spec("p2", Call.STATE_HOLDING, "+15550000012", incoming = false, capabilities = caps, connectTimeMillis = 1))
        routes(earpiece)
        asked.clear()
        // Merge: the calls join a new conference call (no number of its own), still on the earpiece.
        telecom.update("p1") { it.copy(parent = "conf") }
        telecom.update("p2") { it.copy(state = Call.STATE_ACTIVE, parent = "conf") }
        add(
            FakeTelecom.Spec(
                "conf", Call.STATE_ACTIVE, null, incoming = false, children = listOf("p1", "p2"),
                properties = Call.Details.PROPERTY_CONFERENCE, connectTimeMillis = 1,
            ),
        )
        routes(earpiece)
        assertTrue(asked.isEmpty())
    }

    @Test fun aConferenceTheNetworkHandsOverAloneIsNotMoved() {
        deps.speaker = SpeakerDefault.ALWAYS
        // Some networks end the merged calls first and then add the conference on its own.
        add(FakeTelecom.Spec("conf", Call.STATE_ACTIVE, null, incoming = false, properties = Call.Details.PROPERTY_CONFERENCE, connectTimeMillis = 1))
        routes(earpiece)
        assertTrue(asked.isEmpty())
    }
}
