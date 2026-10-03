package app.parley.data.history

import android.provider.CallLog.Calls
import app.parley.common.CallType
import app.parley.common.backup.CallLogRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The call archive's forms of one call agree with each other. */
@RunWith(RobolectricTestRunner::class)
class ArchivedCallsTest {
    private val call = CallLogRecord(
        "+33612345678", 1_600_000_000_000, 42, Calls.INCOMING_TYPE, Calls.PRESENTATION_ALLOWED, "acc", "comp", "Anna",
        isNew = true, isRead = false, features = Calls.FEATURES_VIDEO,
    )

    @Test fun theSealedJsonRoundTripsAsReadHistory() {
        assertEquals(call.copy(isNew = false, isRead = true), ArchivedCalls.decode(ArchivedCalls.encode(call)))
        val hidden = CallLogRecord(null, 5, 0, Calls.MISSED_TYPE, Calls.PRESENTATION_RESTRICTED)
        assertEquals(hidden, ArchivedCalls.decode(ArchivedCalls.encode(hidden)))
        // Archives from before features were kept read as 0.
        assertEquals(0, ArchivedCalls.decode("""{"n":"1","d":1,"s":0,"t":1,"p":1,"a":null,"c":null,"m":null}""").features)
    }

    @Test fun aRecentsRowKeepsWhatItShows() {
        val e = ArchivedCalls.entry(call, 9)
        assertEquals(9, e.id)
        assertEquals(CallType.INCOMING, e.type)
        assertTrue(e.video)
        assertFalse(e.isNew)
        assertFalse(e.presentationHidden)
        assertTrue(ArchivedCalls.entry(call.copy(number = ""), 1).presentationHidden)
        val back = ArchivedCalls.record(e)
        assertEquals(call.number, back.number)
        assertEquals(Calls.INCOMING_TYPE, back.type)
        assertEquals(Calls.FEATURES_VIDEO, back.features)
    }

    @Test fun restoredRowsAreNotNews() {
        val v = ArchivedCalls.values(call)
        assertEquals(0, v.getAsInteger(Calls.NEW))
        assertEquals(1, v.getAsInteger(Calls.IS_READ))
        assertEquals("Anna", v.getAsString(Calls.CACHED_NAME))
    }
}
