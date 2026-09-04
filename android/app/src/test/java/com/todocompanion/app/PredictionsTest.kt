package com.todocompanion.app

import com.todocompanion.app.domain.Prediction
import com.todocompanion.app.domain.Predictions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 3 (feature C) — the Drucker prediction loop: due-to-resurface selection, a JSON round-trip, and
 * recording an outcome.
 */
class PredictionsTest {

    private val today = 1_000L

    private fun p(id: String, created: Long, resurface: Long, resolved: Boolean = false) =
        Prediction(id = id, createdEpochDay = created, resurfaceEpochDay = resurface, expectation = "expect $id", resolved = resolved)

    // ── Due-to-resurface selection ──
    @Test fun dueSelectionPicksUnresolvedPastDueOldestFirst() {
        val list = listOf(
            p("a", created = 900, resurface = 990),   // due (990 <= 1000)
            p("b", created = 950, resurface = 1000),  // due exactly today
            p("c", created = 960, resurface = 1005),  // not yet due
            p("d", created = 800, resurface = 980, resolved = true), // due but already resolved → excluded
        )
        val due = Predictions.dueToResurface(list, today)
        assertEquals("only the two unresolved, past-due predictions", listOf("a", "b"), due.map { it.id })
        // Oldest-resurfacing first.
        assertEquals(990L, due.first().resurfaceEpochDay)
    }

    @Test fun pendingIsUnresolvedNotYetDue() {
        val list = listOf(p("a", 900, 990), p("c", 960, 1005), p("e", 970, 1500))
        val pending = Predictions.pending(list, today)
        assertEquals(listOf("c", "e"), pending.map { it.id })
    }

    // ── Round-trip ──
    @Test fun jsonRoundTrips() {
        val list = listOf(
            p("a", 900, 990),
            Prediction("b", 950, 1100, "I expect the move will make me feel calmer", resolved = true, outcomeNote = "It did", matched = Predictions.MATCH_YES, resolvedEpochDay = 1105),
        )
        val json = Predictions.encodeAll(list)
        assertTrue(json.isNotBlank())
        val back = Predictions.parseAll(json)
        assertEquals(2, back.size)
        val b = back.first { it.id == "b" }
        assertTrue(b.resolved)
        assertEquals("It did", b.outcomeNote)
        assertEquals(Predictions.MATCH_YES, b.matched)
        assertEquals(1105L, b.resolvedEpochDay)
    }

    @Test fun emptyStoreEncodesToBlankAndParsesEmpty() {
        assertEquals("", Predictions.encodeAll(emptyList()))
        assertTrue(Predictions.parseAll("").isEmpty())
        assertTrue(Predictions.parseAll("not json").isEmpty())
    }

    // ── Resolve ──
    @Test fun resolveFlipsAndRecordsOutcome() {
        val store = Predictions.upsert("", p("a", 900, 990))
        val resolved = Predictions.resolve(store, "a", "Turned out better than expected", Predictions.MATCH_NO, resolvedEpochDay = today)
        val back = Predictions.parseAll(resolved).single()
        assertTrue(back.resolved)
        assertEquals("Turned out better than expected", back.outcomeNote)
        assertEquals(Predictions.MATCH_NO, back.matched)
        // A resolved prediction no longer surfaces.
        assertTrue(Predictions.dueToResurface(listOf(back), today).isEmpty())
    }

    @Test fun resolveUnknownIdIsNoOp() {
        val store = Predictions.upsert("", p("a", 900, 990))
        assertEquals(store, Predictions.resolve(store, "missing", "x", Predictions.MATCH_YES, today))
    }

    @Test fun upsertReplacesById() {
        var store = Predictions.upsert("", p("a", 900, 990))
        store = Predictions.upsert(store, p("a", 900, 990).copy(expectation = "changed"))
        val list = Predictions.parseAll(store)
        assertEquals(1, list.size)
        assertEquals("changed", list.single().expectation)
    }

    @Test fun removeDropsById() {
        var store = Predictions.upsert("", p("a", 900, 990))
        store = Predictions.upsert(store, p("b", 910, 995))
        store = Predictions.remove(store, "a")
        assertEquals(listOf("b"), Predictions.parseAll(store).map { it.id })
    }

    // ── Since-label ──
    @Test fun sinceLabelReadsAsWeeksAndMonths() {
        assertEquals("Yesterday", Predictions.sinceLabel(today - 1, today))
        assertEquals("A week ago", Predictions.sinceLabel(today - 7, today))
        assertEquals("2 weeks ago", Predictions.sinceLabel(today - 14, today))
        assertEquals("3 months ago", Predictions.sinceLabel(today - 90, today))
    }

    @Test fun resurfaceForIsInTheFuture() {
        assertEquals(today + 30, Predictions.resurfaceFor(today, 30))
        // A non-positive horizon is coerced to at least one day out.
        assertTrue(Predictions.resurfaceFor(today, 0) > today)
    }

    @Test fun matchMarkersAreCoerced() {
        val store = Predictions.upsert("", p("a", 900, 990))
        val back = Predictions.parseAll(Predictions.resolve(store, "a", "", 99, today)).single()
        assertFalse(back.matched > Predictions.MATCH_NO)
    }
}
