package com.cairn.reader.data.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter

/**
 * The streaming backup writer ([jsonArrayField]) reuses each entity's own `toJson()`, so the only
 * genuinely new logic is the array framing — the commas and brackets. These verify that framing is
 * exact (no leading/trailing/doubled commas, empty arrays render as `[]`) and that a document
 * assembled the way `BackupManager.exportTo` assembles it re-parses to the expected shape, which is
 * what `BackupManager.import` reads back. Runs under Robolectric for a real `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupJsonTest {

    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }

    @Test fun emptyArrayRendersAsBrackets() {
        val w = StringWriter()
        w.jsonArrayField("items", emptyList<JSONObject>()) { it }
        assertEquals("\"items\":[]", w.toString())
    }

    @Test fun singleElementHasNoStrayComma() {
        val w = StringWriter()
        w.jsonArrayField("tags", listOf(obj("id" to "a"))) { it }
        assertEquals("\"tags\":[{\"id\":\"a\"}]", w.toString())
    }

    @Test fun multipleElementsAreCommaSeparated() {
        val w = StringWriter()
        w.jsonArrayField("sources", listOf(obj("id" to "a"), obj("id" to "b"), obj("id" to "c"))) { it }
        assertEquals("\"sources\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"}]", w.toString())
    }

    @Test fun mapperIsAppliedPerRow() {
        val w = StringWriter()
        w.jsonArrayField("itemCollections", listOf("x" to "c1", "y" to "c2")) { (item, coll) ->
            obj("itemId" to item, "collectionId" to coll)
        }
        val parsed = JSONObject("{${w}}").getJSONArray("itemCollections")
        assertEquals(2, parsed.length())
        assertEquals("x", parsed.getJSONObject(0).getString("itemId"))
        assertEquals("c2", parsed.getJSONObject(1).getString("collectionId"))
    }

    /** A document assembled field-by-field the way exportTo does must be valid, complete JSON. */
    @Test fun assembledDocumentReparsesWithAllKeys() {
        val w = StringWriter()
        w.write("{")
        w.write("\"version\":3,")
        w.write("\"exportedAt\":123,")
        w.write("\"filesRoot\":${JSONObject.quote("/data/app")},")
        w.jsonArrayField("sources", listOf(obj("id" to "s1"))) { it }; w.write(",")
        w.jsonArrayField("items", listOf(obj("id" to "i1"), obj("id" to "i2"))) { it }; w.write(",")
        w.jsonArrayField("states", emptyList<JSONObject>()) { it }; w.write(",")
        w.write("\"settings\":${obj("themeMode" to "AUTO")}")
        w.write("}")

        val root = JSONObject(w.toString())
        assertEquals(3, root.getInt("version"))
        assertEquals(123L, root.getLong("exportedAt"))
        assertEquals("/data/app", root.getString("filesRoot"))
        assertEquals(1, root.getJSONArray("sources").length())
        assertEquals(2, root.getJSONArray("items").length())
        assertEquals(0, root.getJSONArray("states").length())
        assertEquals("AUTO", root.getJSONObject("settings").getString("themeMode"))
        assertTrue(root.has("settings"))
    }
}
