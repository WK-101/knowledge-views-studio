package com.todocompanion.app

import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.Modules
import com.todocompanion.app.domain.WeeklyDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** T7 — coverage for the modular fusion invariants that don't need a live database. */

class ModulesTest {
    private fun s(primary: String, disabled: Set<String>) =
        AppSettings().copy(primaryModule = primary, disabledModules = disabled)

    @Test fun primaryIsAlwaysEnabledEvenIfInDisabledSet() {
        // Defensive: the primary can never be off (I3).
        val st = s(Modules.TIME, setOf(Modules.TIME, Modules.TASKS))
        assertTrue(Modules.isEnabled(st, Modules.TIME))
    }

    @Test fun disabledModuleIsOffOthersOn() {
        val st = s(Modules.TASKS, setOf(Modules.HABITS))
        assertTrue(Modules.isEnabled(st, Modules.TASKS))
        assertFalse(Modules.isEnabled(st, Modules.HABITS))
        assertTrue(Modules.isEnabled(st, Modules.TIME))
    }

    @Test fun enabledListsPrimaryFirst() {
        val st = s(Modules.HABITS, setOf(Modules.TIME))
        assertEquals(listOf(Modules.HABITS, Modules.TASKS), Modules.enabled(st))
    }

    @Test fun tabsMapToModules() {
        assertEquals(Modules.TASKS, Modules.moduleOfTab("CALENDAR"))
        assertEquals(Modules.HABITS, Modules.moduleOfTab("HABITS"))
        assertEquals(Modules.TIME, Modules.moduleOfTab("TIME"))
        assertNull(Modules.moduleOfTab("SETTINGS"))   // cross-cutting, always available
    }
}

class WeeklyDigestTimeTest {
    private val today = LocalDate.of(2026, 8, 20).toEpochDay()
    private val UTC = ZoneId.of("UTC")

    @Test fun timeMetricFoldsInWhenPresent() {
        val d = WeeklyDigest.compute(emptyList(), emptyList(), emptyList(), emptyList(), 0, today, UTC, 120, 90)
        val time = d.metrics.firstOrNull { it.label == "Time" }
        assertTrue(time != null)
        assertEquals("120m", time!!.value)
        assertEquals(30, time.delta)
    }

    @Test fun noTimeMetricWhenAbsent() {
        val d = WeeklyDigest.compute(emptyList(), emptyList(), emptyList(), emptyList(), 0, today, UTC)
        assertNull(d.metrics.firstOrNull { it.label == "Time" })
    }
}
