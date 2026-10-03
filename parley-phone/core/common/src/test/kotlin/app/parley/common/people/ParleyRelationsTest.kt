package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParleyRelationsTest {
    @Test fun round_trips_names_types_and_labels() {
        val list = listOf(
            ParleyRelations.Entry("Ana Lima", 13),
            ParleyRelations.Entry("Rui\twith a tab", 0, "Climbing buddy\nline"),
        )
        assertEquals(list, ParleyRelations.decode(ParleyRelations.encode(list)))
    }

    @Test fun blank_names_are_dropped_and_none_is_null() {
        assertNull(ParleyRelations.encode(listOf(ParleyRelations.Entry("  ", 13))))
        assertEquals(emptyList<ParleyRelations.Entry>(), ParleyRelations.decode(null))
        assertEquals(emptyList<ParleyRelations.Entry>(), ParleyRelations.decode("not a relation"))
    }

    @Test fun merging_keeps_both_and_one_of_each_name_and_type() {
        val a = ParleyRelations.encode(listOf(ParleyRelations.Entry("Ana", 13)))
        val b = ParleyRelations.encode(listOf(ParleyRelations.Entry("ana", 13), ParleyRelations.Entry("Bo", 14)))
        assertEquals(listOf(ParleyRelations.Entry("Ana", 13), ParleyRelations.Entry("Bo", 14)), ParleyRelations.decode(ParleyRelations.merge(a, b)))
        assertNull(ParleyRelations.merge(null, null))
    }

    @Test fun a_rekey_merge_keeps_them() {
        val into = MetaRekey.Values(parleyRelations = ParleyRelations.encode(listOf(ParleyRelations.Entry("Ana", 13))))
        val from = MetaRekey.Values(parleyRelations = ParleyRelations.encode(listOf(ParleyRelations.Entry("Bo", 14))))
        assertEquals(2, ParleyRelations.decode(MetaRekey.merge(into, from).parleyRelations).size)
    }
}
