package app.parley.common.calls

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.ux.CallGlance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Calls an app logged in the call log (Android 14+): told apart by their phone account, called back through the app. */
class InternetCallsTest {
    private val sim = "com.android.phone/com.android.services.telephony.TelephonyConnectionService"
    private val whatsApp = "com.whatsapp/com.whatsapp.voipcalling.SelfManagedConnectionService"

    private fun call(id: Long, number: String, date: Long, type: CallType, app: String? = null) =
        CallEntry(id, number, null, type, date, 0, null, false, false, appPackage = app)

    @Test fun the_phone_network_rows_are_phone_calls() {
        assertNull(InternetCalls.appPackage(sim))
        assertNull(InternetCalls.appPackage("com.android.server.telecom/.EmergencyAccount"))
        // Rows from before Android kept an account, restored rows, and unreadable ones.
        assertNull(InternetCalls.appPackage(null))
        assertNull(InternetCalls.appPackage(""))
        assertNull(InternetCalls.appPackage("no-slash"))
        assertNull(InternetCalls.appPackage("/.Service"))
    }

    @Test fun an_app_row_names_its_app() {
        assertEquals("com.whatsapp", InternetCalls.appPackage(whatsApp))
        assertEquals("org.thoughtcrime.securesms", InternetCalls.appPackage("org.thoughtcrime.securesms/org.signal.Service"))
        assertEquals("WhatsApp", InternetCalls.knownLabel("com.whatsapp"))
        assertEquals("Signal", InternetCalls.knownLabel("org.thoughtcrime.securesms"))
        assertNull(InternetCalls.knownLabel("com.example.voip"))
    }

    @Test fun a_maker_s_own_sim_package_is_the_phone_network_too() {
        val oem = "com.maker.telephony/.SimService"
        assertEquals("com.maker.telephony", InternetCalls.appPackage(oem))
        assertNull(InternetCalls.appPackage(oem, InternetCalls.TELEPHONY_PACKAGES + "com.maker.telephony"))
    }

    @Test fun call_back_goes_through_the_app_best_way_first_never_the_phone_network() {
        assertEquals(InternetCalls.Route.CALL_ROW, InternetCalls.route("com.whatsapp", installed = true, hasCallRow = true, canChat = true))
        assertEquals(InternetCalls.Route.CHAT, InternetCalls.route("com.whatsapp", installed = true, hasCallRow = false, canChat = true))
        // An app Parley has no chat link for opens at its start screen.
        assertEquals(InternetCalls.Route.OPEN_APP, InternetCalls.route("com.example.voip", installed = true, hasCallRow = false, canChat = true))
        assertEquals(InternetCalls.Route.OPEN_APP, InternetCalls.route("com.whatsapp", installed = true, hasCallRow = false, canChat = false))
        // Uninstalled: nothing in the app; only "Call by phone", which the user picks.
        assertEquals(InternetCalls.Route.NONE, InternetCalls.route("com.whatsapp", installed = false, hasCallRow = true, canChat = true))
    }

    @Test fun an_app_call_is_internet_and_a_phone_call_is_not() {
        assertTrue(InternetCalls.isInternet(call(1, "1", 1, CallType.INCOMING, "com.whatsapp")))
        assertFalse(InternetCalls.isInternet(call(1, "1", 1, CallType.INCOMING)))
    }

    @Test fun a_call_missed_in_an_app_is_not_one_to_call_back_by_phone() {
        val key = { n: String -> n }
        val calls = listOf(
            call(3, "111", 300, CallType.MISSED, "com.whatsapp"),
            call(2, "222", 200, CallType.MISSED),
            // A WhatsApp call to 333 returns its earlier missed phone call: you did talk.
            call(1, "333", 150, CallType.OUTGOING, "com.whatsapp").copy(durationSec = 30),
            call(0, "333", 100, CallType.MISSED),
        )
        assertEquals(setOf(2L), CallGlance.unreturnedMissed(calls, key, now = 1000))
    }
}
