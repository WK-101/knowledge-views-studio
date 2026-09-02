package com.todocompanion.app

import com.todocompanion.app.data.entity.CountdownEntity
import com.todocompanion.app.domain.LifeEvent
import com.todocompanion.app.domain.MicroPlan
import com.todocompanion.app.domain.MicroPlans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * R68 — new coverage for two previously-untested domain areas: the R67 MicroPlans codec (temptation
 * bundling & if-then plans) and the LifeEvent occasion date engine (birthdays / next-occurrence / age).
 * Pure JVM, no Android — matches the existing domain test suite.
 */
class R68Tests {

    // ---- MicroPlans (settings-JSON codec for bundling / if-then) ----

    @Test fun microPlans_roundTrip_preservesEveryField() {
        val plans = listOf(
            MicroPlan(id = "1", kind = MicroPlans.BUNDLE, a = "audiobook", b = "at the gym", createdAt = 111L),
            MicroPlan(id = "2", kind = MicroPlans.IF_THEN, a = "I sit at my desk", b = "I write one line", createdAt = 222L),
        )
        val back = MicroPlans.parse(MicroPlans.encode(plans))
        assertEquals(plans, back)
    }

    @Test fun microPlans_blankString_isEmptyList() {
        assertTrue(MicroPlans.parse("").isEmpty())
        assertTrue(MicroPlans.parse("   ").isEmpty())
    }

    @Test fun microPlans_malformedJson_degradesToEmpty_neverThrows() {
        assertTrue(MicroPlans.parse("{not valid").isEmpty())
        assertTrue(MicroPlans.parse("[{\"id\":").isEmpty())
    }

    @Test fun microPlans_encodeEmpty_parsesBackToEmpty() {
        assertTrue(MicroPlans.parse(MicroPlans.encode(emptyList())).isEmpty())
    }

    @Test fun microPlans_kindsFilterIndependently() {
        val mixed = listOf(
            MicroPlan("a", MicroPlans.BUNDLE, "x", "y"),
            MicroPlan("b", MicroPlans.IF_THEN, "p", "q"),
            MicroPlan("c", MicroPlans.BUNDLE, "m", "n"),
        )
        val back = MicroPlans.parse(MicroPlans.encode(mixed))
        assertEquals(2, back.count { it.kind == MicroPlans.BUNDLE })
        assertEquals(1, back.count { it.kind == MicroPlans.IF_THEN })
    }

    // ---- LifeEvent (occasion date engine) ----

    private fun millisOf(d: LocalDate): Long =
        d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun birthday(origin: LocalDate) = CountdownEntity(
        id = "sara", title = "Sara", targetMillis = millisOf(origin), createdAt = 0L,
        eventType = "BIRTHDAY", yearly = true, yearKnown = true, personName = "Sara",
    )

    @Test fun lifeEvent_yearlyBirthday_rollsToThisYearWhenStillAhead() {
        val c = birthday(LocalDate.of(1990, 6, 15))
        val today = LocalDate.of(2026, 1, 1)
        assertEquals(LocalDate.of(2026, 6, 15), LifeEvent.nextOccurrence(c, today))
        assertEquals(165L, LifeEvent.daysUntil(c, today))
    }

    @Test fun lifeEvent_yearlyBirthday_rollsToNextYearWhenPast() {
        val c = birthday(LocalDate.of(1990, 6, 15))
        val today = LocalDate.of(2026, 8, 1)
        assertEquals(LocalDate.of(2027, 6, 15), LifeEvent.nextOccurrence(c, today))
    }

    @Test fun lifeEvent_onTheDay_countsAsToday_notAYearOut() {
        val c = birthday(LocalDate.of(1990, 6, 15))
        val today = LocalDate.of(2026, 6, 15)
        assertEquals(today, LifeEvent.nextOccurrence(c, today))
        assertEquals(0L, LifeEvent.daysUntil(c, today))
    }

    @Test fun lifeEvent_ageAndAgeAtNext() {
        val c = birthday(LocalDate.of(1990, 6, 15))
        val today = LocalDate.of(2026, 1, 1) // before this year's birthday
        assertEquals(35, LifeEvent.currentAge(c, today))   // completed years since 1990-06-15
        assertEquals(36, LifeEvent.ageAtNext(c, today))    // what they turn on 2026-06-15
    }

    @Test fun lifeEvent_feb29Origin_fallsBackToFeb28InCommonYear() {
        val c = CountdownEntity(
            id = "leap", title = "Leapling", targetMillis = millisOf(LocalDate.of(2000, 2, 29)), createdAt = 0L,
            eventType = "BIRTHDAY", yearly = true, yearKnown = true,
        )
        val today = LocalDate.of(2025, 1, 1) // 2025 is not a leap year
        assertEquals(LocalDate.of(2025, 2, 28), LifeEvent.nextOccurrence(c, today))
    }

    @Test fun lifeEvent_nonYearlyCountdown_returnsOriginAndHidesAge() {
        val c = CountdownEntity(
            id = "trip", title = "Trip", targetMillis = millisOf(LocalDate.of(2027, 3, 10)), createdAt = 0L,
            eventType = "COUNTDOWN", yearly = false,
        )
        assertEquals(LocalDate.of(2027, 3, 10), LifeEvent.nextOccurrence(c, LocalDate.of(2026, 1, 1)))
        assertNull(LifeEvent.currentAge(c, LocalDate.of(2026, 1, 1))) // COUNTDOWN doesn't count age
    }

    @Test fun lifeEvent_eventTypeFrom_unknownDefaultsToCountdown() {
        assertEquals(LifeEvent.EventType.COUNTDOWN, LifeEvent.EventType.from("NOPE"))
        assertEquals(LifeEvent.EventType.BIRTHDAY, LifeEvent.EventType.from("BIRTHDAY"))
        // memorials are deliberately not celebratory; birthdays are.
        assertTrue(LifeEvent.EventType.BIRTHDAY.celebratory)
        assertTrue(!LifeEvent.EventType.MEMORIAL.celebratory)
    }
}
