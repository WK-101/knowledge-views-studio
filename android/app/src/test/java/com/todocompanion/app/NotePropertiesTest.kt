package com.todocompanion.app

import com.todocompanion.app.domain.NoteProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave S — notes as a local database: frontmatter properties. */
class NotePropertiesTest {

    private val sample = "---\nstatus: reading\nrating: 4\nauthor: Ursula K. Le Guin\n---\n# Title\n\nbody line"

    @Test fun parseReadsOrderedMapAndStripsBody() {
        val m = NoteProperties.parse(sample)
        assertEquals(listOf("status", "rating", "author"), m.keys.toList())
        assertEquals("reading", m["status"])
        assertEquals("Ursula K. Le Guin", m["author"])
        assertTrue(NoteProperties.has(sample))
        assertEquals("# Title\n\nbody line", NoteProperties.strip(sample))
    }

    @Test fun noFrontmatterIsInert() {
        val b = "# Just a note\nno props"
        assertFalse(NoteProperties.has(b))
        assertTrue(NoteProperties.parse(b).isEmpty())
        assertEquals(b, NoteProperties.strip(b))
        // a horizontal rule mid-body is NOT frontmatter
        assertFalse(NoteProperties.has("text\n---\nmore"))
    }

    @Test fun setRemoveRoundTrip() {
        val withStatus = NoteProperties.set("# Hi\n\nbody", "status", "todo")
        assertTrue(withStatus.startsWith("---\nstatus: todo\n---"))
        assertEquals("todo", NoteProperties.parse(withStatus)["status"])
        assertEquals("# Hi\n\nbody", NoteProperties.strip(withStatus))
        // updating a key keeps position; adding a key appends
        val two = NoteProperties.set(withStatus, "rating", "5")
        assertEquals(listOf("status", "rating"), NoteProperties.parse(two).keys.toList())
        // removing the last key removes the whole block
        val none = NoteProperties.remove(NoteProperties.remove(two, "status"), "rating")
        assertFalse(NoteProperties.has(none))
        assertEquals("# Hi\n\nbody", none)
    }

    @Test fun withPropertiesEmptyRemovesBlock() {
        assertEquals("# Title\n\nbody line", NoteProperties.withProperties(sample, emptyMap()))
    }

    @Test fun mirrorRoundTripStable() {
        // parse ∘ withProperties(parse) is stable
        val rebuilt = NoteProperties.withProperties(sample, NoteProperties.parse(sample))
        assertEquals(NoteProperties.parse(sample), NoteProperties.parse(rebuilt))
        assertEquals(NoteProperties.strip(sample), NoteProperties.strip(rebuilt))
    }
}
