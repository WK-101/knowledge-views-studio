package app.parley.common.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A tally counted again from the archive's index after calls left it by date. */
class CallTallyRecountTest {
    private val tally = CallTally(
        mark = "5:5", region = "GB",
        lines = mapOf(
            "a" to CallTally.Line("+441", count = 3, since = 10, at = 30, name = "Ana", namedAt = 30),
            "b" to CallTally.Line("+442", count = 1, since = 5, at = 5, name = "Old", namedAt = 5),
            "c" to CallTally.Line("+443", count = 1, since = 40, at = 40, name = "Early", namedAt = 8),
        ),
    )
    private val keys = mapOf("+441" to listOf("k1", "k1b"), "+442" to listOf("k2"), "+443" to listOf("k3"))

    @Test fun lines_follow_the_index_and_lines_with_no_call_left_go() {
        val counts = mapOf("k1" to CallTally.Count(1, 20, 30), "k1b" to CallTally.Count(1, 25, 26), "k3" to CallTally.Count(1, 40, 40))
        val out = tally.recounted({ keys.getValue(it.number) }, counts)!!
        assertEquals(setOf("a", "c"), out.lines.keys)
        assertEquals(CallTally.Line("+441", 2, 20, 30, "Ana", 30), out.lines.getValue("a"))
        // A name from a call older than every call left went with that call.
        assertNull(out.lines.getValue("c").name)
        assertEquals("5:5", out.mark)
    }

    @Test fun a_fingerprint_counted_for_two_lines_reads_everything_again() {
        val shared = mapOf("+441" to listOf("k1"), "+442" to listOf("k1"), "+443" to listOf("k3"))
        assertNull(tally.recounted({ shared.getValue(it.number) }, mapOf("k1" to CallTally.Count(1, 20, 20))))
    }
}
