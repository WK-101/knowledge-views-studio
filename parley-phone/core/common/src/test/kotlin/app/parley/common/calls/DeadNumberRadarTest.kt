package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class DeadNumberRadarTest {
    private val zone = ZoneOffset.UTC

    private fun at(day: Int, hour: Int = 10): Long = LocalDateTime.of(2026, 9, day, hour, 0).toInstant(zone).toEpochMilli()

    /** An outgoing call on September [day] that failed with telephony's [cause] after [after] seconds. */
    private fun failed(day: Int, cause: String? = "UNOBTAINABLE_NUMBER", after: Long = 3, hour: Int = 10, end: EndCode = EndCode.ERROR) =
        CallQualityFacts(startedAt = at(day, hour), incoming = false, connected = false, end = end, cause = cause, endedAfterSec = after)

    private fun connected(day: Int, incoming: Boolean = false) =
        CallQualityFacts(startedAt = at(day), incoming = incoming, connected = true, durationSec = 60, end = EndCode.LOCAL)

    @Test
    fun `two not-in-service failures on two days are a finding`() {
        val f = DeadNumberRadar.check(listOf(failed(3), failed(1)), zone)
        assertNotNull(f)
        assertEquals(2, f!!.failures)
        assertEquals(2, f.days)
        assertEquals(DeadNumberRadar.Reason.NOT_IN_SERVICE, f.reason)
        assertEquals(at(3), f.lastFailureAt)
    }

    @Test
    fun `one failure is not enough`() {
        assertNull(DeadNumberRadar.check(listOf(failed(3)), zone))
    }

    @Test
    fun `several failures on one day are not enough`() {
        assertNull(DeadNumberRadar.check(listOf(failed(3, hour = 9), failed(3, hour = 12), failed(3, hour = 18)), zone))
    }

    @Test
    fun `a call that went through since clears it`() {
        assertNull(DeadNumberRadar.check(listOf(connected(4), failed(3), failed(1)), zone))
        // Only failures after the last good call count.
        assertNull(DeadNumberRadar.check(listOf(failed(5), connected(4), failed(3), failed(1)), zone))
        assertNotNull(DeadNumberRadar.check(listOf(failed(6), failed(5), connected(4)), zone))
    }

    @Test
    fun `a call from the number shows it is alive`() {
        val missedFromThem = CallQualityFacts(startedAt = at(4), incoming = true, connected = false, end = EndCode.MISSED)
        assertNull(DeadNumberRadar.check(listOf(missedFromThem, failed(3), failed(1)), zone))
    }

    @Test
    fun `a good call in the call history clears it too`() {
        assertNull(DeadNumberRadar.check(listOf(failed(3), failed(1)), zone, lastAliveElsewhere = at(3, 15)))
        assertNotNull(DeadNumberRadar.check(listOf(failed(3), failed(1)), zone, lastAliveElsewhere = at(1, 5)))
    }

    @Test
    fun `quick failures without a cause on the phone's side count`() {
        val f = DeadNumberRadar.check(listOf(failed(3, cause = null, after = 2), failed(1, cause = "ERROR_UNSPECIFIED", after = 4)), zone)
        assertEquals(DeadNumberRadar.Reason.FAILS_AT_ONCE, f?.reason)
    }

    @Test
    fun `slow failures, busy lines and the phone's own problems don't count`() {
        assertFalse(DeadNumberRadar.pointsAtNumber(failed(3, cause = null, after = 40)))
        assertFalse(DeadNumberRadar.pointsAtNumber(failed(3, cause = "OUT_OF_SERVICE", after = 1)))
        assertFalse(DeadNumberRadar.pointsAtNumber(failed(3, cause = "LOST_SIGNAL", after = 1)))
        assertFalse(DeadNumberRadar.pointsAtNumber(failed(3, cause = null, after = 1, end = EndCode.BUSY)))
        assertFalse(DeadNumberRadar.pointsAtNumber(failed(3, cause = null, after = 1, end = EndCode.LOCAL)))
        assertFalse(DeadNumberRadar.pointsAtNumber(connected(3)))
        // The network's own "not in service" counts however long it took to say so.
        assertTrue(DeadNumberRadar.pointsAtNumber(failed(3, cause = "INVALID_NUMBER", after = 30)))
    }

    @Test
    fun `older rows without a failure time only count with a cause`() {
        val old = CallQualityFacts(startedAt = at(3), incoming = false, connected = false, end = EndCode.ERROR)
        assertFalse(DeadNumberRadar.pointsAtNumber(old))
    }

    @Test
    fun `a dismissal holds until a new failure`() {
        val facts = listOf(failed(3), failed(1))
        assertNull(DeadNumberRadar.check(facts, zone, dismissedAt = at(4)))
        assertNotNull(DeadNumberRadar.check(listOf(failed(5)) + facts, zone, dismissedAt = at(4)))
    }
}
