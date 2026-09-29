package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallQualityCodecTest {
    @Test fun round_trip_and_bad_input() {
        val f = CallQualityFacts(1_000, incoming = true, durationSec = 65, connected = true, sim = "Work", wifi = true, hd = true,
            end = EndCode.ERROR, cause = "LOST_SIGNAL", drop = DropKind.LOST_SIGNAL, subject = "About the parcel")
        assertEquals(listOf(f), CallQualityCodec.decode(CallQualityCodec.encode(listOf(f))))
        assertEquals(emptyList<CallQualityFacts>(), CallQualityCodec.decode("not json"))
        // Fields a later version adds are ignored.
        assertEquals(1_000L, CallQualityCodec.decode("""[{"startedAt":1000,"incoming":false,"future":1}]""").single().startedAt)
    }

    @Test fun cause_name_from_the_reason() {
        assertEquals("LOST_SIGNAL", CallQualityCodec.causeName("LOST_SIGNAL"))
        assertEquals("WIFI_LOST", CallQualityCodec.causeName("Call ended by network, WIFI_LOST"))
        assertNull(CallQualityCodec.causeName("ended"))
        assertNull(CallQualityCodec.causeName(null))
    }

    @Test fun near_matches_a_call_log_date() {
        val a = CallQualityFacts(100_000, true)
        val b = CallQualityFacts(400_000, true)
        assertEquals(a, CallQualityCodec.near(listOf(a, b), 130_000))
        assertNull(CallQualityCodec.near(listOf(a, b), 900_000))
    }
}
