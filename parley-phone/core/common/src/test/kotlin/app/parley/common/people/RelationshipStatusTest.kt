package app.parley.common.people

import app.parley.common.people.RelationMirror.Row
import app.parley.common.people.RelationshipStatus.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelationshipStatusTest {
    private fun header(vararg rows: Row) = RelationshipStatus.header(rows.toList()) { it }.map { (k, r) -> k to r.name }

    @Test fun spouses_and_partners_make_the_header_line() {
        assertEquals(listOf(Kind.MARRIED to "Sam"), header(Row("Sam", "spouse", null)))
        assertEquals(listOf(Kind.MARRIED to "Sam"), header(Row("Sam", "husband", null)))
        assertEquals(listOf(Kind.PARTNER to "Alex"), header(Row("Alex", "domestic-partner", null)))
        assertEquals(listOf(Kind.PARTNER to "Alex"), header(Row("Alex", "partner", null)))
    }

    @Test fun married_comes_first_and_each_person_once() {
        val rows = arrayOf(Row("Alex", "partner", null), Row("Sam", "wife", null), Row("sam ", "spouse", null))
        assertEquals(listOf(Kind.MARRIED to "Sam", Kind.PARTNER to "Alex"), header(*rows))
    }

    @Test fun former_spouses_and_other_relations_stay_out_of_the_header() {
        assertEquals(emptyList<Pair<Kind, String>>(), header(Row("Kim", "ex-spouse", null), Row("Lee", "friend", null), Row("", "spouse", null)))
        assertEquals(Kind.FORMERLY_MARRIED, RelationshipStatus.kindOf("ex-spouse"))
        assertNull(RelationshipStatus.kindOf("girlfriend"))
        assertNull(RelationshipStatus.kindOf(null))
    }

    @Test fun ex_spouse_words_written_by_other_apps_are_recognised() {
        assertEquals("ex-wife", RelationTypes.fromAndroid(0, "Ex-wife")?.key)
        assertEquals(Kind.FORMERLY_MARRIED, RelationshipStatus.kindOf("ex-wife"))
        assertEquals(Kind.FORMERLY_MARRIED, RelationshipStatus.kindOf("ex-husband"))
        assertEquals("ex-spouse", RelationTypes.fromAndroid(0, "Former spouse")?.key)
        assertEquals("ex-spouse", RelationMirror.inverse(RelationTypes.byKey("ex-spouse")!!)?.key)
    }
}
