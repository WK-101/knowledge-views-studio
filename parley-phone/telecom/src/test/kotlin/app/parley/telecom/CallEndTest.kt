package app.parley.telecom

import android.telecom.Call
import android.telecom.DisconnectCause
import app.parley.common.calls.DropKind
import app.parley.common.calls.EndCode
import app.parley.common.calls.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a call's end reads: disconnect causes into failures, drops, reasons and the facts kept about the call. */
internal class CallEndTest : CallPathTest() {
    @Test fun aBusyLineSaysSoWithRetry() {
        dialling("t1")
        end("t1", DisconnectCause.BUSY)
        assertEquals(FailureKind.BUSY, ended().failure)
        assertEquals(str(R.string.call_disconnect_busy), ended().failureText)
    }

    @Test fun aNetworkErrorOnAnOutgoingCallIsAFailure() {
        dialling("t1")
        end("t1", DisconnectCause.ERROR)
        assertEquals(FailureKind.OTHER, ended().failure)
        assertEquals(str(R.string.call_failed_generic), ended().failureText)
    }

    @Test fun aCallEndedInTheSimPickerSaysNoSimWasChosen() {
        add(FakeTelecom.Spec("t1", Call.STATE_SELECT_PHONE_ACCOUNT, incoming = false))
        end("t1", DisconnectCause.ERROR)
        assertEquals(FailureKind.NO_SIM_SELECTED, ended().failure)
    }

    @Test fun aCallTheUserCancelledIsNeverAFailure() {
        val c = dialling("t1")
        CallManager.hangup(idOf(c))
        end("t1", DisconnectCause.ERROR)
        assertNull(ended().failure)
    }

    @Test fun anOutgoingCallTheOtherSideDeclinedIsNotAFailure() {
        dialling("t1")
        end("t1", DisconnectCause.REJECTED)
        assertNull(ended().failure)
    }

    @Test fun aConnectedCallLostToTheNetworkShowsTheDrop() {
        active("t1")
        end("t1", DisconnectCause.ERROR, "LOST_SIGNAL")
        assertEquals(DropKind.LOST_SIGNAL, ended().drop)
        assertEquals(str(R.string.call_drop_title), ended().disconnectReason)
        assertTrue(ended().dropText!!.startsWith(str(R.string.call_drop_lost_signal)))
        assertTrue(ended().canCallAgain)
    }

    @Test fun losingWifiCallingIsNamed() {
        active("t1")
        end("t1", DisconnectCause.ERROR, "WIFI_LOST")
        assertEquals(DropKind.WIFI_LOST, ended().drop)
    }

    @Test fun anErrorWithoutAReasonIsANetworkProblem() {
        active("t1")
        end("t1", DisconnectCause.ERROR)
        assertEquals(DropKind.NETWORK, ended().drop)
        assertNull("a connected call is never a failure", ended().failure)
    }

    @Test fun theOtherSideHangingUpIsNeitherDropNorFailure() {
        active("t1")
        end("t1", DisconnectCause.REMOTE)
        assertNull(ended().drop)
        assertNull(ended().failure)
    }

    @Test fun aCallTheUserEndedIsNeverADrop() {
        val c = active("t1")
        CallManager.hangup(idOf(c))
        end("t1", DisconnectCause.ERROR, "LOST_SIGNAL")
        assertNull(ended().drop)
    }

    private fun reasonFor(id: String, cause: Int): String? {
        val c = dialling(id)
        telecom.update(id) { it.copy(state = Call.STATE_DISCONNECTED, disconnectCause = DisconnectCause(cause)) }
        return ui(c).disconnectReason
    }

    @Test fun theReasonWhileTheCallEndsReadsPlainly() {
        assertEquals(str(R.string.call_disconnect_busy), reasonFor("t1", DisconnectCause.BUSY))
        assertEquals(str(R.string.call_disconnect_declined), reasonFor("t2", DisconnectCause.REJECTED))
        assertEquals(str(R.string.call_disconnect_missed), reasonFor("t3", DisconnectCause.MISSED))
        assertNull("hanging up needs no reason", reasonFor("t4", DisconnectCause.LOCAL))
        assertNull(reasonFor("t5", DisconnectCause.REMOTE))
    }

    @Test fun theCallsQualityFactsAreKept() {
        active("t1", properties = Call.Details.PROPERTY_WIFI or Call.Details.PROPERTY_HIGH_DEF_AUDIO)
        end("t1", DisconnectCause.REMOTE)
        val q = deps.quality.single()
        assertEquals(EndCode.REMOTE, q.end)
        assertTrue(q.connected && q.wifi && q.hd)
        assertEquals(listOf("+15550000001"), deps.ended)
    }

    @Test fun anIgnoredCallSaysWhyItWasQuiet() {
        val c = ringing()
        CallManager.ignore(idOf(c))
        end("t1", DisconnectCause.MISSED)
        assertEquals(str(R.string.call_silenced_ignored), deps.ringFacts.single().silencedBy)
    }

    @Test fun aNewCallClearsTheLastEndedOne() {
        dialling("t1")
        end("t1", DisconnectCause.BUSY)
        assertEquals(FailureKind.BUSY, ended().failure)
        ringing("t2")
        assertNull(CallManager.lastEnded.value)
    }

    @Test fun dismissingTheFailureKeepsItGone() {
        dialling("t1")
        end("t1", DisconnectCause.BUSY)
        CallManager.dismissFailure(ended().id)
        assertNull(ended().failure)
    }
}
