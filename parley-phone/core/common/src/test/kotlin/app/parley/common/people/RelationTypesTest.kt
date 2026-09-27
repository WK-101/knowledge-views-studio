package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationTypesTest {
    @Test fun relation_types_map_to_android_and_back() {
        assertTrue(RelationTypes.all.size >= 60)
        assertEquals(RelationTypes.all.size, RelationTypes.all.map { it.key }.distinct().size)
        assertEquals("spouse", RelationTypes.fromAndroid(14, null)!!.key)
        assertEquals("co-worker", RelationTypes.fromAndroid(0, "Co-worker")!!.key)
        assertEquals("neighbor", RelationTypes.fromAndroid(0, "neighbour")!!.key)
        assertNull(RelationTypes.fromAndroid(0, "Fishing buddy"))
        assertEquals(14 to null, RelationTypes.toAndroid(RelationTypes.byKey("spouse")!!))
        assertEquals(0 to "Grandmother", RelationTypes.toAndroid(RelationTypes.byKey("grandmother")!!))
        assertEquals("grandmother", RelationTypes.search("grandm").first().key)
    }

    @Test fun a_picked_relation_contact_wins_over_name_matching() {
        val contacts = listOf(Triple(1L, "Anna", "k1"), Triple(2L, "Anna", "k2"))
        val picked = mapOf("anna" to RelationLinks.Link("k2", 2))
        assertEquals(picked, RelationLinks.update(listOf("Anna"), emptyMap(), contacts, self = 9, picked = picked))
        assertEquals(emptyMap<String, RelationLinks.Link>(), RelationLinks.update(listOf("Anna"), emptyMap(), contacts, self = 9))
    }
}
