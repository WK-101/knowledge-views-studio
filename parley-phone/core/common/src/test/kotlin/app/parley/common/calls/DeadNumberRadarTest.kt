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
    @Suppress("LongParameterList")
    private fun failed(
        day: Int,
        cause: String? = "UNOBTAINABLE_NUMBER",
        after: Long = 3,
        hour: Int = 10,
        end: EndCode = EndCode.ERROR,
        roaming: Boolean? = false,
        offline: Boolean = false,
    ) = CallQualityFacts(
        startedAt = at(day, hour), incoming = false, connected = false, end = end, cause = cause, endedAfterSec = after,
        roaming = roaming, offline = offline,
    )

    private val national = "07700 900123"
    private val intl = "+44 7700 900123"

    private fun check(facts: List<CallQualityFacts>, number: String = national, alive: Long? = null, dismissedAt: Long? = null) =
        DeadNumberRadar.check(facts, number, zone, alive, dismissedAt)

    private fun connected(day: Int, incoming: Boolean = false) =
        CallQualityFacts(startedAt = at(day), incoming = incoming, connected = true, durationSec = 60, end = EndCode.LOCAL)

    @Test
    fun `two not-in-service failures on two days are a finding`() {
        val f = check(listOf(failed(3), failed(1)))
        assertNotNull(f)
        assertEquals(2, f!!.failures)
        assertEquals(2, f.days)
        assertEquals(DeadNumberRadar.Reason.NOT_IN_SERVICE, f.reason)
        assertEquals(at(3), f.lastFailureAt)
    }

    @Test
    fun `one failure is not enough`() {
        assertNull(check(listOf(failed(3))))
    }

    @Test
    fun `several failures on one day are not enough`() {
        assertNull(check(listOf(failed(3, hour = 9), failed(3, hour = 12), failed(3, hour = 18))))
    }

    @Test
    fun `a call that went through since clears it`() {
        assertNull(check(listOf(connected(4), failed(3), failed(1))))
        // Only failures after the last good call count.
        assertNull(check(listOf(failed(5), connected(4), failed(3), failed(1))))
        assertNotNull(check(listOf(failed(6), failed(5), connected(4))))
    }

    @Test
    fun `a call from the number shows it is alive`() {
        val missedFromThem = CallQualityFacts(startedAt = at(4), incoming = true, connected = false, end = EndCode.MISSED)
        assertNull(check(listOf(missedFromThem, failed(3), failed(1))))
    }

    @Test
    fun `a good call in the call history clears it too`() {
        assertNull(check(listOf(failed(3), failed(1)), alive = at(3, 15)))
        assertNotNull(check(listOf(failed(3), failed(1)), alive = at(1, 5)))
    }

    @Test
    fun `quick failures without a definite cause never count`() {
        assertNull(check(listOf(failed(3, cause = null, after = 2), failed(1, cause = "ERROR_UNSPECIFIED", after = 4))))
        assertNull(check(listOf(failed(3, cause = "IMS_SOMETHING", after = 1), failed(1, cause = "INVALID_NUMBER", after = 1))))
    }

    @Test
    fun `busy lines, the phone's own problems and wider network causes don't count`() {
        fun points(f: CallQualityFacts) = DeadNumberRadar.pointsAtNumber(f, international = false)
        assertFalse(points(failed(3, cause = "OUT_OF_SERVICE", after = 1)))
        assertFalse(points(failed(3, cause = "LOST_SIGNAL", after = 1)))
        assertFalse(points(failed(3, cause = "NO_ROUTE_TO_DESTINATION")))
        assertFalse(points(failed(3, cause = "INVALID_NUMBER")))
        assertFalse(points(failed(3, cause = null, after = 1, end = EndCode.BUSY)))
        assertFalse(points(connected(3)))
        // Placed in airplane mode: the phone's side, whatever the network answered.
        assertFalse(points(failed(3, offline = true)))
        // The network's own "unassigned" counts however long it took to say so.
        assertTrue(points(failed(3, cause = "UNASSIGNED_NUMBER", after = 30)))
        assertTrue(points(failed(3, cause = "NUMBER_CHANGED")))
    }

    @Test
    fun `a national number called while roaming or with roaming unknown doesn't count`() {
        assertNull(check(listOf(failed(3, roaming = true), failed(1, roaming = true))))
        assertNull(check(listOf(failed(3, roaming = null), failed(1, roaming = null))))
        // In international form it reads the same from anywhere.
        assertNotNull(check(listOf(failed(3, roaming = true), failed(1, roaming = null)), number = intl))
        assertNotNull(check(listOf(failed(3, roaming = true), failed(1, roaming = null)), number = "0044 7700 900123"))
        assertTrue(DeadNumberRadar.mayPointAtNumber(failed(3, roaming = true)))
    }

    @Test
    fun `older rows without a cause or roaming never count for a national number`() {
        val old = CallQualityFacts(startedAt = at(3), incoming = false, connected = false, end = EndCode.ERROR)
        assertFalse(DeadNumberRadar.pointsAtNumber(old, international = true))
        val oldWithCause = old.copy(cause = "UNOBTAINABLE_NUMBER")
        assertFalse(DeadNumberRadar.pointsAtNumber(oldWithCause, international = false))
        assertTrue(DeadNumberRadar.pointsAtNumber(oldWithCause, international = true))
    }

    @Test
    fun `a dismissal holds until a new failure`() {
        val facts = listOf(failed(3), failed(1))
        assertNull(check(facts, dismissedAt = at(4)))
        assertNotNull(check(listOf(failed(5)) + facts, dismissedAt = at(4)))
    }
}
