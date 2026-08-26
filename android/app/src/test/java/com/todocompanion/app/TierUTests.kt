package com.todocompanion.app

import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.AutomationRule
import com.todocompanion.app.domain.AutomationRules
import com.todocompanion.app.domain.TimeInsights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier U — pure-logic coverage for the new insight/automation maths. */
class TierUTests {

    private val H = 3_600_000L
    private val dayStart = 1_700_000_000_000L
    private fun entry(startMin: Int, durMin: Int, tags: String = "", taskId: String? = null) =
        TimeEntryEntity(
            id = "e$startMin", activityId = "a", startMillis = dayStart + startMin * 60_000L,
            endMillis = dayStart + (startMin + durMin) * 60_000L, tags = tags, taskId = taskId,
        )

    @Test fun minutesByHourSplitsAcrossHours() {
        // 90-minute block starting at 00:30 → 30 min in hour 0, 60 min in hour 1.
        val e = entry(30, 90)
        val byHour = TimeInsights.minutesByHour(listOf(e), dayStart, dayStart + 24 * H, dayStart + 24 * H)
        assertEquals(30, byHour[0])
        assertEquals(60, byHour[1])
        assertEquals(0, byHour[2])
    }

    @Test fun durationDistributionBuckets() {
        val d = TimeInsights.durationDistribution(listOf(entry(0, 10), entry(20, 45), entry(120, 200)), dayStart, dayStart + 24 * H)
        assertEquals(1, d[0].count)   // < 15m
        assertEquals(1, d[2].count)   // 30–60m
        assertEquals(1, d[4].count)   // 2h+
    }

    @Test fun totalsByTagCountsEachTag() {
        val res = TimeInsights.totalsByTag(listOf(entry(0, 30, "work,client"), entry(60, 20, "work")), dayStart, dayStart + 24 * H, dayStart + 24 * H)
        assertEquals(50, res.first { it.tag == "work" }.minutes)
        assertEquals(30, res.first { it.tag == "client" }.minutes)
    }

    @Test fun planVsActualCalibrationIsMedianRatio() {
        val items = listOf(
            TimeInsights.PlanActualItem("1", "a", 60, 120),   // 2.0
            TimeInsights.PlanActualItem("2", "b", 60, 90),    // 1.5
            TimeInsights.PlanActualItem("3", "c", 60, 60),    // 1.0
        )
        val pa = TimeInsights.planVsActual(items)
        assertEquals(180, pa.plannedMin)
        assertEquals(270, pa.actualMin)
        assertEquals(1.5, pa.calibration!!, 1e-9)   // median of {1.0, 1.5, 2.0}
    }

    @Test fun conditionalRateComputesLift() {
        val universe = (1L..10L).toList()
        val success = setOf(1L, 2L, 3L, 4L, 6L)     // done on these days
        val condition = setOf(1L, 2L, 3L, 4L, 5L)   // "tracked X" on days 1–5
        val c = TimeInsights.conditionalRate(universe, success, condition)
        assertEquals(0.8, c.rateWith, 1e-9)         // 4 of 5 with condition were successes
        assertEquals(0.2, c.rateWithout, 1e-9)      // 1 of 5 without
        assertTrue(c.lift > 0.5)
    }

    @Test fun untrackedBlocksFindsUncoveredPlan() {
        // A planned block 09:00–10:00 for task T with no tracked time against it.
        val block = TimeInsights.PlannedBlock("T", "Write", 540, 60)
        val now = dayStart + 20 * H
        val none = TimeInsights.untrackedBlocks(listOf(block), emptyList(), dayStart, now)
        assertEquals(1, none.size)
        // Now with a tracked entry linked to T covering it → not untracked.
        val covered = TimeInsights.untrackedBlocks(listOf(block), listOf(entry(540, 60, taskId = "T")), dayStart, now)
        assertTrue(covered.isEmpty())
    }

    @Test fun automationRulesRoundTripAndMatch() {
        val rules = listOf(
            AutomationRule(id = "1", whenActivityId = "deep", actionType = AutomationRule.ACTION_NOTIFY, notifyText = "silent?"),
            AutomationRule(id = "2", whenActivityId = "gym", actionType = AutomationRule.ACTION_START, startActivityId = "music", enabled = false),
        )
        val json = AutomationRules.encode(rules)
        val back = AutomationRules.parse(json)
        assertEquals(2, back.size)
        assertEquals(1, AutomationRules.onStart(back, "deep").size)      // enabled match
        assertEquals(0, AutomationRules.onStart(back, "gym").size)       // disabled → no match
    }
}
