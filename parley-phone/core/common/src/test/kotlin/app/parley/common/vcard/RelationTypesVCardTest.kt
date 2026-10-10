package app.parley.common.vcard

import app.parley.common.people.RelationTypes
import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every relation type through a vCard (RELATED;TYPE=, or its label) and back, as Android's type and label. */
class RelationTypesVCardTest {
    private val name = row(Mime.NAME, Col.D1 to "Ana Lima", Col.D2 to "Ana", Col.D3 to "Lima")

    @Test fun every_relation_type_round_trips_without_loss() {
        val rows = RelationTypes.all.mapIndexed { i, t ->
            val (type, label) = RelationTypes.toAndroid(t)
            row(Mime.RELATION, Col.D1 to "Person $i", Col.D2 to type.toString(), Col.D3 to label)
        }
        val back = assertLossless(record("Ana Lima", name, *rows.toTypedArray()))
        val types = back.rows(Mime.RELATION).associate { r -> r[Col.D1] to RelationTypes.fromAndroid(r[Col.D2]!!.toInt(), r[Col.D3])?.key }
        RelationTypes.all.forEachIndexed { i, t -> assertEquals(t.label, t.key, types["Person $i"]) }
    }

    @Test fun androids_built_in_types_travel_as_related_types() {
        val text = unfolded(record("Ana Lima", name, row(Mime.RELATION, Col.D1 to "Sam", Col.D2 to "14")))
        assertTrue(text, text.contains("RELATED;TYPE=spouse", ignoreCase = true) || text.contains("TYPE=spouse"))
        val wife = RelationTypes.toAndroid(RelationTypes.byKey("wife")!!)
        val back = assertLossless(record("Ana Lima", name, row(Mime.RELATION, Col.D1 to "Sam", Col.D2 to wife.first.toString(), Col.D3 to wife.second)))
        assertEquals("wife", back.rows(Mime.RELATION).single().let { RelationTypes.fromAndroid(it[Col.D2]!!.toInt(), it[Col.D3])?.key })
    }
}
