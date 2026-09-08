package com.cairn.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-select delegate the six list ViewModels share. These pin the exact behaviour the old
 * per-ViewModel copies had, so the de-duplication can't silently change selection semantics.
 */
class MultiSelectStoreTest {

    @Test fun `toggle adds an id, then removes it`() {
        val s = MultiSelectStore<String>()
        s.toggle("a")
        assertEquals(setOf("a"), s.selected.value)
        s.toggle("b")
        assertEquals(setOf("a", "b"), s.selected.value)
        s.toggle("a")
        assertEquals(setOf("b"), s.selected.value)
    }

    @Test fun `selectAll replaces the whole selection`() {
        val s = MultiSelectStore<String>()
        s.toggle("x")
        s.selectAll(listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), s.selected.value)
    }

    @Test fun `clear empties the selection`() {
        val s = MultiSelectStore<String>()
        s.selectAll(listOf("a", "b"))
        s.clear()
        assertTrue(s.selected.value.isEmpty())
    }

    @Test fun `consume returns the snapshot and clears in one step`() {
        val s = MultiSelectStore<String>()
        s.selectAll(listOf("a", "b"))
        val snapshot = s.consume()
        assertEquals(setOf("a", "b"), snapshot)
        assertTrue("consume must leave the selection empty", s.selected.value.isEmpty())
    }

    @Test fun `consume on an empty selection is an empty set, not an error`() {
        val s = MultiSelectStore<String>()
        assertTrue(s.consume().isEmpty())
    }
}
