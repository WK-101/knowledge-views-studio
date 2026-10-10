package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The number parse cache: least recently used out one at a time, sized to the address book. */
class RecentCacheTest {
    @Test fun the_least_recently_used_goes_never_everything() {
        val cache = RecentCache<String, Int>(3)
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3
        cache["a"] // used again
        cache["d"] = 4
        assertNull("the least recently used went", cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
        assertEquals(4, cache["d"])
        assertEquals(3, cache.size)
    }

    @Test fun a_working_set_that_fits_keeps_hitting_pass_after_pass() {
        val cache = RecentCache<Int, Int>(16_000)
        var misses = 0
        repeat(3) { repeat(15_000) { n -> if (cache[n] == null) { misses++; cache[n] = n } } }
        assertEquals("only the first pass parses", 15_000, misses)
    }

    @Test fun resize_grows_up_to_a_ceiling_and_never_shrinks() {
        val cache = RecentCache<Int, Int>(100)
        cache.resize(1_000, ceiling = 500)
        assertEquals(500, cache.capacity)
        cache.resize(10)
        assertEquals(500, cache.capacity)
    }

    @Test fun number_text_makes_room_for_the_address_book() {
        // 10,000 contacts with 15,000 numbers: room for them and the call history.
        NumberText.fitCache(15_000)
        assertTrue(NumberText.cacheCapacity >= 30_000)
        // Old behaviour for comparison: 4,096 entries cleared wholesale. A pass over 15,000 numbers twice then parses
        // all 30,000 times; now the second pass parses none.
        val numbers = (0 until 15_000).map { "+1 202 55${"%05d".format(it)}" }
        val cold = System.nanoTime()
        numbers.forEach { NumberText.toE164(it, "US") }
        val coldMs = (System.nanoTime() - cold) / 1_000_000
        val start = System.nanoTime()
        numbers.forEach { NumberText.toE164(it, "US") }
        val warmMs = (System.nanoTime() - start) / 1_000_000
        // Before, every pass cost what the first does: the 4,096-entry cache was cleared several times per pass.
        println("B5 a pass over 15,000 numbers: $coldMs ms parsing each (every pass before), $warmMs ms now that they stay cached")
        assertEquals("+12025500042", NumberText.toE164("+1 202 5500042", "US"))
    }
}
