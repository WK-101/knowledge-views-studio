package com.todocompanion.app

import com.todocompanion.app.util.NoteMirror
import com.todocompanion.app.util.NoteMirror.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave N — the living mirror: reconciliation decisions + canonical hashing + baseline JSON. */
class NoteMirrorTest {

    @Test fun identicalSidesAreInSync() {
        assertEquals(Action.NONE, NoteMirror.reconcile(baseline = null, disk = "h", db = "h"))
        assertEquals(Action.NONE, NoteMirror.reconcile(baseline = "old", disk = "h", db = "h"))
    }

    @Test fun firstTimeDecisions() {
        assertEquals(Action.NEW_LOCAL, NoteMirror.reconcile(null, disk = "h", db = null))
        assertEquals(Action.NEW_DB, NoteMirror.reconcile(null, disk = null, db = "h"))
        assertEquals(Action.CONFLICT, NoteMirror.reconcile(null, disk = "a", db = "b"))
    }

    @Test fun oneSidedChangesPushOrPull() {
        // baseline "b": only the db moved on → push out; only disk moved on → pull in.
        assertEquals(Action.PUSH, NoteMirror.reconcile("b", disk = "b", db = "b2"))
        assertEquals(Action.PULL, NoteMirror.reconcile("b", disk = "b2", db = "b"))
    }

    @Test fun bothChangedIsConflict() {
        assertEquals(Action.CONFLICT, NoteMirror.reconcile("b", disk = "d2", db = "n2"))
    }

    @Test fun deletionsAreConservative() {
        // File vanished but note changed/exists → re-export, never drop the note.
        assertEquals(Action.PUSH, NoteMirror.reconcile("b", disk = null, db = "b"))
        // Note gone, file unchanged since baseline → leave the file alone (no resurrect/delete).
        assertEquals(Action.NONE, NoteMirror.reconcile("b", disk = "b", db = null))
        // Note gone, file edited externally → import the edited file.
        assertEquals(Action.PULL, NoteMirror.reconcile("b", disk = "b2", db = null))
    }

    @Test fun canonicalIgnoresNothingButIsStable() {
        val a = NoteMirror.canonical("T", "body", listOf("b", "a"), "note", false, false, null, null)
        val b = NoteMirror.canonical("T", "body", listOf("a", "b"), "note", false, false, null, null) // tag order
        assertEquals(a, b)                                    // tag order doesn't matter
        assertEquals(NoteMirror.hash(a), NoteMirror.hash(b))
        val c = NoteMirror.canonical("T", "body!", listOf("a", "b"), "note", false, false, null, null)
        assertNotEquals(NoteMirror.hash(a), NoteMirror.hash(c))  // body change registers
    }

    @Test fun baselineRoundTrips() {
        val b = NoteMirror.Baseline(mutableMapOf("n1" to NoteMirror.Base("One.md", "abc")))
        val decoded = NoteMirror.decodeBaseline(NoteMirror.encodeBaseline(b))
        assertEquals("abc", decoded.notes["n1"]?.hash)
        assertEquals("One.md", decoded.notes["n1"]?.fileName)
        assertTrue(NoteMirror.decodeBaseline(null).notes.isEmpty())   // tolerant of missing file
    }
}
