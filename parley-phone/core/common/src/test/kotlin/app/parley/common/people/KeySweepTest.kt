package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeySweepTest {
    @Test fun keysTheAddressBookStillHasResolveWithoutALookup() {
        val split = KeySweep.split(
            stored = linkedMapOf("a" to 1L, "b" to null, "gone" to 7L),
            current = mapOf("a" to 1L, "b" to 2L, "other" to 3L),
        )
        assertEquals(mapOf("a" to (1L to "a"), "b" to (2L to "b")), split.direct)
        assertEquals(listOf("gone"), split.needLookup)
    }

    @Test fun aKeyWhoseContactIdChangedTakesTheCurrentId() {
        val split = KeySweep.split(mapOf("a" to 1L), mapOf("a" to 9L))
        assertEquals(9L to "a", split.direct["a"])
    }

    @Test fun noStoredKeysNeedNothing() {
        val split = KeySweep.split(emptyMap(), mapOf("a" to 1L))
        assertEquals(emptyMap<String, Pair<Long, String>>(), split.direct)
        assertEquals(emptyList<String>(), split.needLookup)
    }

    @Test fun snapshotsDifferWhenEitherSideChanges() {
        val base = KeySweep.Snapshot(mapOf("a" to 1L), mapOf("a" to 1L))
        assertEquals(base, KeySweep.Snapshot(mapOf("a" to 1L), mapOf("a" to 1L)))
        assertNotEquals(base, KeySweep.Snapshot(mapOf("a.b" to 1L), mapOf("a" to 1L)))
        assertNotEquals(base, KeySweep.Snapshot(mapOf("a" to 1L), mapOf("a" to 1L, "b" to null)))
        assertNotEquals(base, KeySweep.Snapshot(mapOf("a" to 2L), mapOf("a" to 1L)))
    }
}
