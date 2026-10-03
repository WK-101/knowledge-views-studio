package app.parley.telecom

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Send to another number": Telecom's deflect, only where the network allows it and never to an emergency number. */
internal class DeflectCallTest : CallPathTest() {
    private fun ringingDeflectable(id: String = "t1", number: String = "+15551234567") =
        add(FakeTelecom.Spec(id, Call.STATE_RINGING, number, capabilities = Call.Details.CAPABILITY_SUPPORT_DEFLECT))

    @Test fun offeredOnlyWhereTheNetworkSupportsIt() {
        assertTrue(ui(ringingDeflectable()).canDeflect)
        assertFalse(ui(ringing("t2", "+15550000777")).canDeflect)
    }

    @Test fun neverForAnEmergencyCallBack() {
        val c = add(FakeTelecom.Spec("t1", Call.STATE_RINGING, "112", capabilities = Call.Details.CAPABILITY_SUPPORT_DEFLECT))
        assertFalse(ui(c).canDeflect)
        assertFalse(CallManager.deflect(idOf(c), "+15550000123"))
        assertFalse(sent("deflectCall"))
    }

    @Test fun neverDuringTheEmergencyCallBackWindow() {
        // The operator's call-back often comes from an ordinary-looking number, without any emergency mark.
        ScreeningGuard.noteEmergencyCall(context)
        val c = ringingDeflectable()
        assertFalse(ui(c).canDeflect)
        assertFalse(CallManager.deflect(idOf(c), "+15550000123"))
        assertFalse(sent("deflectCall"))
    }

    @Test fun sendsTheCallOnAndSaysSoWhenItEnds() {
        val c = ringingDeflectable()
        assertTrue(CallManager.deflect(idOf(c), "+1 555 000 0123"))
        assertTrue(telecom.sent.any { it.startsWith("deflectCall") && it.contains("15550000123") })
        end("t1", android.telecom.DisconnectCause.OTHER)
        assertEquals(str(R.string.handoff_ended_deflected), ended().disconnectReason)
        // Ended on purpose: no "Call dropped".
        assertNull(ended().drop)
    }

    @Test fun refusesWhatIsNotANumberOrAnEmergencyNumber() {
        val c = ringingDeflectable()
        assertFalse(CallManager.deflect(idOf(c), "12"))
        assertFalse(CallManager.deflect(idOf(c), "999"))
        assertFalse(sent("deflectCall"))
    }

    @Test fun aCallStillRingingAfterwardsIsTheUsersAgain() {
        val c = ringingDeflectable()
        val problems = ArrayList<String>()
        assertTrue(CallManager.deflect(idOf(c), "+15550000123") { problems += it })
        FakeTelecom.advance(11_000)
        assertEquals(listOf(str(R.string.handoff_deflect_failed)), problems)
        assertTrue(ui(c).canDeflect)
    }

    @Test fun notOnceAnswered() {
        val c = ringingDeflectable()
        telecom.update("t1") { it.copy(state = Call.STATE_ACTIVE, connectTimeMillis = System.currentTimeMillis()) }
        assertFalse(ui(c).canDeflect)
        assertFalse(CallManager.deflect(idOf(c), "+15550000123"))
    }
}
