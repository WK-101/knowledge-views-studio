package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimAdviceTest {
    private val sims = setOf("sim1", "sim2")
    private var t = 1_000_000L

    private fun dropped(sim: String) = SimAdvice.SimCall(
        sim,
        CallQualityFacts(startedAt = t++, incoming = false, connected = true, durationSec = 30, end = EndCode.ERROR, drop = DropKind.LOST_SIGNAL),
    )

    private fun failed(sim: String) = SimAdvice.SimCall(sim, CallQualityFacts(startedAt = t++, incoming = false, connected = false, end = EndCode.ERROR))

    private fun good(sim: String, incoming: Boolean = false) =
        SimAdvice.SimCall(sim, CallQualityFacts(startedAt = t++, incoming = incoming, connected = true, durationSec = 120, end = EndCode.REMOTE))

    private fun many(n: Int, make: () -> SimAdvice.SimCall) = List(n) { make() }

    @Test
    fun `three drops on one SIM and three good calls on the other suggest the other`() {
        val s = SimAdvice.suggest(many(3) { dropped("sim1") } + many(3) { good("sim2") }, sims, current = null)
        assertEquals("sim2", s?.simId)
        assertEquals("sim1", s?.fromSimId)
        assertEquals(3, s?.from?.bad)
        assertEquals(3, s?.to?.good)
    }

    @Test
    fun `failed calls count as going wrong too`() {
        val s = SimAdvice.suggest(many(2) { dropped("sim1") } + failed("sim1") + many(3) { good("sim2", incoming = true) }, sims, current = null)
        assertEquals("sim2", s?.simId)
    }

    @Test
    fun `below the thresholds says nothing`() {
        assertNull(SimAdvice.suggest(many(2) { dropped("sim1") } + many(3) { good("sim2") }, sims, null))
        assertNull(SimAdvice.suggest(many(3) { dropped("sim1") } + many(2) { good("sim2") }, sims, null))
    }

    @Test
    fun `a SIM that mostly works isn't left for a few drops`() {
        // 3 drops in 10 calls on SIM 1: under half.
        assertNull(SimAdvice.suggest(many(3) { dropped("sim1") } + many(7) { good("sim1") } + many(5) { good("sim2") }, sims, null))
    }

    @Test
    fun `the other SIM has to be clearly better`() {
        val calls = many(4) { dropped("sim1") } + many(1) { good("sim1") } + many(3) { good("sim2") } + many(3) { dropped("sim2") }
        assertNull(SimAdvice.suggest(calls, sims, null))
    }

    @Test
    fun `single-SIM phones never see it`() {
        val calls = many(3) { dropped("sim1") } + many(3) { good("sim2") }
        assertNull(SimAdvice.suggest(calls, setOf("sim1"), null))
        assertNull(SimAdvice.suggest(calls, emptySet(), null))
    }

    @Test
    fun `a SIM that's gone isn't suggested`() {
        val calls = many(3) { dropped("sim1") } + many(3) { good("sim3") }
        assertNull(SimAdvice.suggest(calls, sims, null))
    }

    @Test
    fun `not again once it's set or answered`() {
        val calls = many(3) { dropped("sim1") } + many(3) { good("sim2") }
        assertNull(SimAdvice.suggest(calls, sims, current = "sim2"))
        assertNull(SimAdvice.suggest(calls, sims, current = null, answered = setOf("sim2")))
        assertEquals("sim2", SimAdvice.suggest(calls, sims, current = "sim1", answered = setOf("sim1"))?.simId)
    }
}
