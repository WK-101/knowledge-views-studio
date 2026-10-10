package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class CallQualityDiaryTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 30, 12, 0).toInstant(zone).toEpochMilli()

    /** A connected call [daysAgo] days ago at [hour]. */
    private fun call(
        who: String?,
        daysAgo: Int,
        hour: Int = 10,
        sim: String? = null,
        wifi: Boolean = false,
        drop: Boolean = false,
        connected: Boolean = true,
    ): DiaryCall {
        val at = LocalDateTime.of(2026, 9, 30, hour, 0).minusDays(daysAgo.toLong()).toInstant(zone).toEpochMilli()
        return DiaryCall(
            who,
            CallQualityFacts(
                startedAt = at, incoming = false, durationSec = if (connected) 60 else 0, connected = connected, sim = sim, wifi = wifi,
                drop = if (drop) DropKind.LOST_SIGNAL else null, end = if (drop) EndCode.ERROR else EndCode.LOCAL,
            ),
        )
    }

    @Test fun a_large_history_is_searched_quickly() {
        // 60 days of heavy use: 20,000 calls with 2,000 people over two SIMs and Wi-Fi calling, and one real pattern.
        val calls = (0 until 20_000).map { i ->
            val who = "p${i % 2_000}"
            call(who, daysAgo = i % 59, hour = i % 24, sim = if (i % 3 == 0) "Work" else "Home", wifi = i % 5 == 0, drop = who == "p7" || i % 97 == 0)
        }
        val started = System.nanoTime()
        val report = CallQualityDiary.report(calls, zone, now)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertNotNull(report)
        assertTrue("took $ms ms", ms < 5_000)
        assertEquals("p7", report!!.patterns.first().who)
    }

    @Test fun too_few_calls_say_nothing() {
        assertNull(CallQualityDiary.report(listOf(call("mum", 1), call("mum", 2)), zone, now))
        // Calls that never connected don't count.
        assertNull(CallQualityDiary.report(listOf(call("mum", 1), call("mum", 2), call("mum", 3, connected = false)), zone, now))
        // Nor calls older than the window.
        assertNull(CallQualityDiary.report(listOf(call("mum", 1), call("mum", 2), call("mum", 70)), zone, now))
    }

    @Test fun rates_per_sim_and_network() {
        val calls = listOf(
            call("a", 1, sim = "Work", drop = true),
            call("b", 2, sim = "Work"),
            call("c", 3, sim = "Personal", wifi = true),
            call("d", 4, sim = "Personal"),
        )
        val r = CallQualityDiary.report(calls, zone, now)!!
        assertEquals(DropRate(4, 1), r.overall)
        assertEquals(25, r.overall.percent)
        assertEquals(mapOf("Work" to DropRate(2, 1), "Personal" to DropRate(2, 0)), r.bySim)
        assertEquals(mapOf(CallNetwork.WIFI to DropRate(1, 0), CallNetwork.MOBILE to DropRate(3, 1)), r.byNetwork)
    }

    @Test fun one_sim_and_no_wifi_calling_show_no_breakdown() {
        val r = CallQualityDiary.report(listOf(call("a", 1), call("b", 2), call("c", 3, drop = true)), zone, now)!!
        assertTrue(r.bySim.isEmpty())
        assertTrue(r.byNetwork.isEmpty())
    }

    @Test fun calls_with_mum_drop_on_sim_2_in_the_evening() {
        val calls = buildList {
            // Mum in the evening on SIM 2: 3 of 4 drop.
            add(call("mum", 1, hour = 19, sim = "SIM 2", drop = true))
            add(call("mum", 3, hour = 20, sim = "SIM 2", drop = true))
            add(call("mum", 6, hour = 19, sim = "SIM 2", drop = true))
            add(call("mum", 9, hour = 18, sim = "SIM 2"))
            // Mum in the evening on SIM 1, Mum during the day, others in the evening on SIM 2, others by day: fine.
            add(call("mum", 2, hour = 19, sim = "SIM 1"))
            add(call("mum", 4, hour = 20, sim = "SIM 1"))
            add(call("mum", 5, hour = 10, sim = "SIM 2"))
            add(call("mum", 7, hour = 11, sim = "SIM 2"))
            repeat(3) { add(call("evening$it", it + 1, hour = 19, sim = "SIM 2")) }
            repeat(6) { add(call("day$it", it + 1, hour = 14, sim = if (it % 2 == 0) "SIM 1" else "SIM 2")) }
        }
        val r = CallQualityDiary.report(calls, zone, now)!!
        val p = r.patterns.first()
        assertEquals("mum", p.who)
        assertEquals("SIM 2", p.sim)
        assertEquals(DayPart.EVENING, p.dayPart)
        assertNull(p.network)
        assertEquals(DropRate(4, 3), p.rate)
        // SIM 1 held on 5 calls: try it.
        assertEquals(QualityAdvice.TRY_OTHER_SIM, p.advice)
        assertEquals("SIM 1", p.otherSim)
        // The same three drops aren't repeated as "Calls with Mum on SIM 2".
        assertEquals(1, r.patterns.size)
        // Recent drops with Call again, newest first.
        assertEquals(calls.take(3).map { it.facts.startedAt }, r.recentDrops.map { it.facts.startedAt })
    }

    @Test fun a_part_that_narrows_nothing_is_left_out() {
        // All of Mum's calls are in the evening: the pattern is about Mum, not the evening.
        val calls = buildList {
            add(call("mum", 1, hour = 19, drop = true))
            add(call("mum", 2, hour = 19, drop = true))
            add(call("mum", 3, hour = 19))
            repeat(4) { add(call("day$it", it + 1, hour = 10)) }
        }
        val p = CallQualityDiary.report(calls, zone, now)!!.patterns.single()
        assertEquals("mum", p.who)
        assertNull(p.dayPart)
    }

    @Test fun mobile_drops_suggest_wifi_calling() {
        val calls = buildList {
            add(call("mum", 1, drop = true))
            add(call("mum", 2, drop = true))
            add(call("mum", 3))
            repeat(6) { add(call("x$it", it + 1)) }
        }
        val p = CallQualityDiary.report(calls, zone, now)!!.patterns.single()
        assertEquals("mum", p.who)
        assertNull(p.sim)
        assertEquals(QualityAdvice.TRY_WIFI_CALLING, p.advice)
    }

    @Test fun wifi_calling_drops_suggest_the_mobile_network() {
        val calls = buildList {
            repeat(3) { add(call("w$it", it + 1, wifi = true, drop = it < 2)) }
            repeat(5) { add(call("m$it", it + 1)) }
        }
        val p = CallQualityDiary.report(calls, zone, now)!!.patterns.single()
        assertEquals(CallNetwork.WIFI, p.network)
        assertNull(p.who)
        assertEquals(QualityAdvice.TRY_MOBILE_NETWORK, p.advice)
    }

    @Test fun drops_spread_evenly_are_no_pattern() {
        val calls = (1..12).map { call("p${it % 3}", it, drop = it % 4 == 0) }
        assertTrue(CallQualityDiary.report(calls, zone, now)!!.patterns.isEmpty())
    }

    @Test fun hidden_callers_are_counted_but_never_named_or_called_again() {
        val calls = listOf(call(null, 1, drop = true), call(null, 2, drop = true), call(null, 3), call("a", 4), call("b", 5), call("c", 6))
        val r = CallQualityDiary.report(calls, zone, now)!!
        assertEquals(DropRate(6, 2), r.overall)
        assertTrue(r.patterns.none { it.who != null })
        assertTrue(r.recentDrops.isEmpty())
    }

    @Test fun parts_of_the_day() {
        assertEquals(DayPart.NIGHT, DayPart.of(3))
        assertEquals(DayPart.MORNING, DayPart.of(5))
        assertEquals(DayPart.AFTERNOON, DayPart.of(12))
        assertEquals(DayPart.EVENING, DayPart.of(21))
        assertEquals(DayPart.NIGHT, DayPart.of(22))
    }

    @Test fun number_line() {
        assertNull(CallQualityDiary.numberLine(listOf(call("a", 1).facts)))
        val facts = listOf(
            call("a", 1, sim = "Work", drop = true).facts,
            call("a", 2, sim = "Work", drop = true, wifi = true).facts,
            call("a", 3, sim = "Home").facts.copy(hd = true),
            call("a", 4, connected = false).facts,
        )
        val q = CallQualityDiary.numberLine(facts)
        assertNotNull(q)
        assertEquals(DropRate(3, 2), q!!.rate)
        assertEquals("Work", q.dropSim)
        assertEquals(1, q.wifiCalls)
        assertEquals(1, q.hdCalls)
        // One SIM: no "on Work".
        assertNull(CallQualityDiary.numberLine(listOf(call("a", 1, sim = "Work", drop = true).facts, call("a", 2, sim = "Work", drop = true).facts))!!.dropSim)
    }
}
