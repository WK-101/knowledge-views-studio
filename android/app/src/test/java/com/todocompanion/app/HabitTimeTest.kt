package com.todocompanion.app

import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.habit.HabitStats
import com.todocompanion.app.domain.habit.HabitTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class HabitTimeTest {

    private val utc = ZoneId.of("UTC")
    private val today = 20000L

    private fun habit(
        id: String = "h",
        unit: String? = null,
        target: Int = 1,
        minutesPerUnit: Int = 0,
        freq: String = "weekly",
        param: Int = 0,
        cueTime: Int? = null,
        reminders: String = "",
        type: String = "build",
    ) = HabitEntity(
        id = id, name = "H", createdAt = 0L, unit = unit, targetPerDay = target,
        minutesPerUnit = minutesPerUnit, freqType = freq, freqParam = param,
        cueTime = cueTime, reminderTimes = reminders, habitType = type,
    )

    // ── cost derivation ladder ──────────────────────────────────────────────────────────────────────
    @Test fun unitMinutesIsTheCost() {
        assertEquals(20, HabitTime.derivedCostMin(habit(unit = "min", target = 20)))
        assertEquals(30, HabitTime.derivedCostMin(habit(unit = "minutes", target = 30)))
    }

    @Test fun minutesPerUnitTimesTarget() {
        // walk 1000 "steps" is not min-unit; if the user set a per-unit time it multiplies.
        assertEquals(15, HabitTime.derivedCostMin(habit(unit = "steps", target = 15, minutesPerUnit = 1)))
    }

    @Test fun learnedMedianUsedWhenNoExplicitUnit() {
        assertEquals(12, HabitTime.derivedCostMin(habit(unit = "steps", target = 1000), learnedMin = 12))
    }

    @Test fun unknownCostIsZeroNotInvented() {
        assertEquals(0, HabitTime.derivedCostMin(habit(unit = "steps", target = 1000)))
    }

    @Test fun manualOverridesDerived() {
        val h = habit(unit = "min", target = 20)
        val cfg = HabitTime.Cfg(costMode = HabitTime.CostMode.MANUAL, manualMin = 45)
        assertEquals(45, HabitTime.costMin(h, cfg))
        // manual = 0 means "not set" → fall back to the derived ladder.
        assertEquals(20, HabitTime.costMin(h, HabitTime.Cfg(costMode = HabitTime.CostMode.MANUAL, manualMin = 0)))
    }

    // ── classification ──────────────────────────────────────────────────────────────────────────────
    @Test fun autoClassMinuteUnitIsDedicatedElseAmbient() {
        assertEquals(HabitTime.TimeClass.DEDICATED, HabitTime.autoClass(habit(unit = "min", target = 20)))
        assertEquals(HabitTime.TimeClass.AMBIENT, HabitTime.autoClass(habit(unit = "steps", target = 1000)))
    }

    @Test fun rollingQuotaIsAlwaysAmbient() {
        val h = habit(unit = "min", target = 60, freq = HabitStats.FREQ_TIMES_WEEK, param = 3)
        assertEquals(HabitTime.TimeClass.AMBIENT, HabitTime.effectiveClass(h, HabitTime.Cfg()))
    }

    @Test fun offClassSuppressesEntirely() {
        val h = habit(unit = "min", target = 20)
        assertEquals(HabitTime.TimeClass.OFF, HabitTime.effectiveClass(h, HabitTime.Cfg(timeClass = HabitTime.TimeClass.OFF)))
    }

    // ── config round-trip ───────────────────────────────────────────────────────────────────────────
    @Test fun cfgEncodeParseRoundTrips() {
        val c = HabitTime.Cfg(HabitTime.TimeClass.DEDICATED, HabitTime.CostMode.MANUAL, 45, true)
        assertEquals(c, HabitTime.parseCfg(HabitTime.encodeCfg(c)))
        // blank/auto default
        assertEquals(HabitTime.Cfg(), HabitTime.parseCfg(""))
        assertTrue(HabitTime.isDefault(HabitTime.Cfg()))
        assertFalse(HabitTime.isDefault(c))
    }

    // ── per-day model ───────────────────────────────────────────────────────────────────────────────
    private fun settingsWith(vararg entries: Pair<String, HabitTime.Cfg>) =
        AppSettings(habitTimeCfg = entries.associate { it.first to HabitTime.encodeCfg(it.second) })

    @Test fun ambientStepsReserveTimeAndAreReleasedWhenDone() {
        val steps = habit(id = "s", unit = "steps", target = 1000)
        val settings = settingsWith("s" to HabitTime.Cfg(costMode = HabitTime.CostMode.MANUAL, manualMin = 15))
        // pending today → 15 min reserved as an ambient band, no placed interval.
        val pendingDay = HabitTime.forDay(listOf(steps), emptyList(), settings, today, today)
        assertEquals(15, HabitTime.ambientReserveMin(pendingDay))
        assertTrue(HabitTime.busyIntervals(pendingDay, today, utc).isEmpty())
        // done today → released.
        val done = listOf(HabitCheckinEntity(habitId = "s", epochDay = today, count = 1000, status = "done"))
        val doneDay = HabitTime.forDay(listOf(steps), done, settings, today, today)
        assertEquals(0, HabitTime.ambientReserveMin(doneDay))
    }

    @Test fun dedicatedTimedHabitPlacesAnInterval() {
        val run = habit(id = "r", unit = "min", target = 30, cueTime = 6 * 60) // 06:00, 30 min
        val settings = settingsWith("r" to HabitTime.Cfg(timeClass = HabitTime.TimeClass.DEDICATED, showAsBlock = true))
        val day = HabitTime.forDay(listOf(run), emptyList(), settings, today, today)
        val iv = HabitTime.busyIntervals(day, today, utc)
        assertEquals(1, iv.size)
        val dayStart = today * 86_400_000L
        assertEquals(dayStart + 6 * 60 * 60_000L, iv[0].first)
        assertEquals(dayStart + (6 * 60 + 30) * 60_000L, iv[0].second)
        // a placed interval is not also counted in the ambient band (no double-count).
        assertEquals(0, HabitTime.ambientReserveMin(day))
        assertTrue(day.first().showAsBlock)
    }

    @Test fun dedicatedUntimedFallsBackToAmbientReserve() {
        val h = habit(id = "d", unit = "min", target = 25) // dedicated but no cue time
        val settings = settingsWith("d" to HabitTime.Cfg(timeClass = HabitTime.TimeClass.DEDICATED))
        val day = HabitTime.forDay(listOf(h), emptyList(), settings, today, today)
        assertTrue(HabitTime.busyIntervals(day, today, utc).isEmpty())
        assertEquals(25, HabitTime.ambientReserveMin(day))
        assertNull(day.first().cueMin)
    }

    @Test fun rollingQuotaIsAmortizedPerDay() {
        // gym 60 min, 3×/week → ~26 min/day amortized (round(60*3/7)).
        val gym = habit(id = "g", unit = "min", target = 60, freq = HabitStats.FREQ_TIMES_WEEK, param = 3)
        val day = HabitTime.forDay(listOf(gym), emptyList(), AppSettings(), today, today)
        assertEquals(26, HabitTime.totalReserveMin(day))
    }

    @Test fun futureDayIsAlwaysPending() {
        val steps = habit(id = "s", unit = "steps", target = 1000)
        val settings = settingsWith("s" to HabitTime.Cfg(costMode = HabitTime.CostMode.MANUAL, manualMin = 15))
        val done = listOf(HabitCheckinEntity(habitId = "s", epochDay = today, count = 1000, status = "done"))
        // done today doesn't release tomorrow's reserve.
        val tomorrow = HabitTime.forDay(listOf(steps), done, settings, today + 1, today)
        assertEquals(15, HabitTime.ambientReserveMin(tomorrow))
    }

    @Test fun optedBlockWithoutTimeIsPlacedAtWorkStart() {
        // Dedicated + "show as block" but no reminder/cue → placed at the start of the working day, not lost.
        val h = habit(id = "r", unit = "min", target = 30)
        val settings = AppSettings(
            workStartHour = 9,
            habitTimeCfg = mapOf("r" to HabitTime.encodeCfg(HabitTime.Cfg(timeClass = HabitTime.TimeClass.DEDICATED, showAsBlock = true))),
        )
        val day = HabitTime.forDay(listOf(h), emptyList(), settings, today, today)
        assertTrue(day.first().showAsBlock)
        val iv = HabitTime.busyIntervals(day, today, utc)
        assertEquals(1, iv.size)
        val dayStart = today * 86_400_000L
        assertEquals(dayStart + 9 * 60 * 60_000L, iv[0].first)
    }

    @Test fun optedBlockWithUnknownCostGetsDefaultLength() {
        // No derivable cost, but the user opted it into a block → assume a short default so it appears + counts.
        val h = habit(id = "x", unit = "reps", target = 1)
        val settings = AppSettings(
            habitTimeCfg = mapOf("x" to HabitTime.encodeCfg(HabitTime.Cfg(timeClass = HabitTime.TimeClass.DEDICATED, showAsBlock = true))),
        )
        val day = HabitTime.forDay(listOf(h), emptyList(), settings, today, today)
        assertEquals(1, day.size)
        assertEquals(30, day.first().costMin)
    }

    @Test fun breakAndArchivedHabitsAreIgnored() {
        val brk = habit(id = "b", unit = "min", target = 20, type = "break")
        val arch = habit(id = "a", unit = "min", target = 20).copy(archived = true)
        val day = HabitTime.forDay(listOf(brk, arch), emptyList(), AppSettings(), today, today)
        assertTrue(day.isEmpty())
    }
}
