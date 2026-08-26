package com.todocompanion.app

import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.AppSettings
import com.todocompanion.app.domain.AssistantAction
import com.todocompanion.app.domain.AssistantLog
import com.todocompanion.app.domain.InsightPrefs
import com.todocompanion.app.domain.InsightPrefsCodec
import com.todocompanion.app.domain.MetricSnapshot
import com.todocompanion.app.domain.MetricSnapshots
import com.todocompanion.app.domain.habit.HabitStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier Z — pure-logic coverage for the trust layer (prefs, log, snapshots, graded strength). */
class TierZTests {

    // ── Z2 · insight prefs ────────────────────────────────────────────────────────────────────────
    @Test fun dismissSuppressesForever() {
        val p = InsightPrefs().dismiss("keystone")
        assertTrue(p.suppressed("keystone", 100L))
        assertTrue(p.suppressed("keystone", 999L))
        assertFalse(p.suppressed("peak", 100L))
    }
    @Test fun snoozeSuppressesUntilDay() {
        val p = InsightPrefs().snooze("burnout", untilEpochDay = 110L)
        assertTrue(p.suppressed("burnout", 105L))
        assertTrue(p.suppressed("burnout", 110L))
        assertFalse(p.suppressed("burnout", 111L))
    }
    @Test fun restoreClearsBoth() {
        val p = InsightPrefs().dismiss("x").snooze("x", 200L).restore("x")
        assertFalse(p.suppressed("x", 50L))
    }
    @Test fun insightPrefsRoundTrip() {
        val p = InsightPrefs(dismissed = setOf("a"), snoozeUntil = mapOf("b" to 42L))
        val back = InsightPrefsCodec.parse(InsightPrefsCodec.encode(p))
        assertTrue(back.suppressed("a", 999L))
        assertTrue(back.suppressed("b", 40L))
    }

    // ── Z6 · assistant log ────────────────────────────────────────────────────────────────────────
    @Test fun logPushKeepsNewestAndCaps() {
        var list = emptyList<AssistantAction>()
        for (i in 1..60) list = AssistantLog.push(list, AssistantAction("id$i", i.toLong(), "backfill", "action $i", "{}"))
        assertEquals(50, list.size)
        assertEquals("id60", list.first().id) // newest first
    }
    @Test fun logMarkUndoneAndReversible() {
        val a = AssistantAction("1", 0L, "rhythm", "did", "{\"habit\":\"h\"}")
        assertTrue(a.reversible)
        val list = AssistantLog.markUndone(listOf(a), "1")
        assertTrue(list[0].undone)
        assertFalse(list[0].reversible)
    }
    @Test fun logNonReversibleWhenNoUndo() {
        assertFalse(AssistantAction("1", 0L, "plan", "did", "").reversible)
    }

    // ── Z5 · metric snapshots ─────────────────────────────────────────────────────────────────────
    @Test fun snapshotUpsertReplacesSameMonth() {
        val a = MetricSnapshot("2026-07", calibrationPct = 10)
        val b = MetricSnapshot("2026-08", calibrationPct = 20)
        val b2 = MetricSnapshot("2026-08", calibrationPct = 5)
        val list = MetricSnapshots.upsert(MetricSnapshots.upsert(listOf(a), b), b2)
        assertEquals(2, list.size)
        assertEquals(5, list.last().calibrationPct)          // replaced, not duplicated
        assertEquals("2026-07", list.first().yearMonth)      // sorted chronologically
    }

    // ── Z8 · graded strength ──────────────────────────────────────────────────────────────────────
    @Test fun gradedCreditLiftsStrengthAbovePartialZero() {
        val h = HabitEntity(id = "h", name = "read", targetPerDay = 4, createdAt = 90L * 86_400_000L)
        val today = 100L
        val done = setOf(100L, 98L, 96L)                 // fully met
        val partial = mapOf(99L to 0.5, 97L to 0.5)      // attempted but short
        val binary = HabitStats.strength(h, done, emptySet(), emptySet(), today)
        val graded = HabitStats.strength(h, done, emptySet(), emptySet(), today, gradedCredit = partial)
        assertTrue("graded should reward partial days", graded > binary)
    }
    @Test fun emptyGradedCreditMatchesBinary() {
        val h = HabitEntity(id = "h", name = "read", targetPerDay = 4, createdAt = 90L * 86_400_000L)
        val today = 100L
        val done = setOf(100L, 98L)
        val a = HabitStats.strength(h, done, emptySet(), emptySet(), today)
        val b = HabitStats.strength(h, done, emptySet(), emptySet(), today, gradedCredit = emptyMap())
        assertEquals(a, b)
    }

    // ── settings round-trip ───────────────────────────────────────────────────────────────────────
    @Test fun tierZSettingsRoundTrip() {
        val s = AppSettings(
            insightPrefsJson = InsightPrefsCodec.encode(InsightPrefs(dismissed = setOf("k"))),
            morningBriefEnabled = true, morningBriefHour = 7, gradedStrength = true,
        )
        val back = AppSettings.fromMap(s.toMap())
        assertTrue(back.morningBriefEnabled)
        assertEquals(7, back.morningBriefHour)
        assertTrue(back.gradedStrength)
        assertTrue(InsightPrefsCodec.parse(back.insightPrefsJson).suppressed("k", 1L))
    }
}
