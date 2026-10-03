package app.parley.telecom

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merging, swapping, and a conference's people: separate one, end one. */
internal class ConferenceCallTest : CallPathTest() {
    @Test fun mergeUsesTheNetworksConferenceWhenItOffersOne() {
        val a = active("t1", Call.Details.CAPABILITY_MERGE_CONFERENCE)
        assertTrue(ui(a).canMerge)
        CallManager.merge(idOf(a))
        assertEquals(listOf("mergeConference(t1)"), telecom.sent)
    }

    @Test fun mergeJoinsAConferenceableCallOtherwise() {
        held("t2")
        val a = add(FakeTelecom.Spec("t1", Call.STATE_ACTIVE, incoming = false, conferenceable = listOf("t2"), connectTimeMillis = 1))
        assertTrue(ui(a).canMerge)
        CallManager.merge(idOf(a))
        assertEquals(listOf("conference(t1, t2)"), telecom.sent)
    }

    @Test fun mergeDoesNothingWhenTheCallsCantBeJoined() {
        val a = active("t1")
        assertFalse(ui(a).canMerge)
        CallManager.merge(idOf(a))
        assertTrue(telecom.sent.isEmpty())
    }

    @Test fun swapUsesTheConferencesOwnSwap() {
        val a = active("t1", Call.Details.CAPABILITY_SWAP_CONFERENCE)
        assertTrue(ui(a).canSwap)
        CallManager.swap(idOf(a))
        assertEquals(listOf("swapConference(t1)"), telecom.sent)
    }

    @Test fun swapOtherwiseResumesTheHeldCall() {
        val a = active("t1")
        held("t2")
        CallManager.swap(idOf(a))
        assertEquals(listOf("unholdCall(t2)"), telecom.sent)
    }

    private fun conference(caps: Int = Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE or Call.Details.CAPABILITY_DISCONNECT_FROM_CONFERENCE): Call {
        add(FakeTelecom.Spec("p1", Call.STATE_ACTIVE, "+15550000011", incoming = false, parent = "conf", capabilities = caps, connectTimeMillis = 1))
        add(FakeTelecom.Spec("p2", Call.STATE_ACTIVE, "+15550000012", incoming = false, parent = "conf", capabilities = caps, connectTimeMillis = 1))
        return add(
            FakeTelecom.Spec(
                "conf", Call.STATE_ACTIVE, null, incoming = false, children = listOf("p1", "p2"),
                properties = Call.Details.PROPERTY_CONFERENCE, connectTimeMillis = 1,
            ),
        )
    }

    @Test fun aConferenceShowsOnceWithItsPeopleInside() {
        val conf = conference()
        val top = CallManager.state.value
        assertEquals("only the conference itself is a call on screen", 1, top.size)
        val u = ui(conf)
        assertTrue(u.isConference)
        assertEquals(listOf("+15550000011", "+15550000012"), u.children.map { it.number })
        assertTrue(u.children.all { it.canSeparate && it.canDisconnectChild })
    }

    @Test fun separateTakesOnePersonOutOfTheConference() {
        val conf = conference()
        CallManager.separate(ui(conf).children.first().id)
        assertEquals(listOf("splitFromConference(p1)"), telecom.sent)
    }

    @Test fun endingOnePersonLeavesTheOthersOnTheCall() {
        val conf = conference()
        CallManager.hangup(ui(conf).children.last().id)
        assertEquals(listOf("disconnectCall(p2)"), telecom.sent)
    }

    @Test fun peopleWhoCantBeSeparatedSaySo() {
        val conf = conference(caps = 0)
        assertTrue(ui(conf).children.none { it.canSeparate || it.canDisconnectChild })
    }
}
