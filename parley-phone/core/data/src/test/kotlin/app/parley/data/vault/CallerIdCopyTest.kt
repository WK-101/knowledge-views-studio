package app.parley.data.vault

import app.parley.common.people.PrivateLabels
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The caller-ID copy's fields, read without the detail key. */
@RunWith(RobolectricTestRunner::class)
class CallerIdCopyTest {
    private fun copy() = JSONObject()
        .put("name", "Ana Silva").put("numbers", JSONArray(listOf("+351912345678", "+351213456789"))).put("labels", JSONArray(listOf(2, 3)))
        .put("u", 42L).put(CallerIdCopy.C_STAR, true).put(CallerIdCopy.C_TONE, "content://tone").put(CallerIdCopy.C_SEEDED, true)
        .put(CallerIdCopy.C_TITLE, "Designer").put(CallerIdCopy.C_COMPANY, "Acme").put("ctx", "From the climbing gym").put("note", "Owes me lunch")
        .put(CallerIdCopy.C_PRONOUNS, "she/her")

    @Test fun summaryReadsTheListRow() {
        val s = CallerIdCopy.summary(7, copy(), expiresAt = 99L, createdAt = 1L)
        assertEquals(7, s.id)
        assertEquals("Ana Silva", s.name)
        assertEquals(listOf("+351912345678", "+351213456789"), s.numbers)
        assertEquals(99L, s.expiresAt)
        assertEquals(42L, s.updatedAt)
        assertTrue(s.starred)
        assertEquals("content://tone", s.ringtone)
        assertTrue(s.choicesKnown)
        assertFalse(s.sendToVoicemail)
        assertNull(s.vibration)
        // No stored "Family, Given" form: guessed from the name.
        assertEquals("Silva, Ana", s.nameAlt)
    }

    @Test fun anOldCopyFallsBackToItsCreationTimeAndUnknownChoices() {
        val s = CallerIdCopy.summary(3, JSONObject().put("name", "Bo"), expiresAt = null, createdAt = 5L)
        assertEquals(5L, s.updatedAt)
        assertFalse(s.choicesKnown)
        assertEquals(emptyList<String>(), s.numbers)
    }

    @Test fun aCompanyKeepsItsNameAsTheAlternative() {
        assertEquals("Acme", CallerIdCopy.alternativeOf(JSONObject().put("name", "Acme").put(CallerIdCopy.C_COMPANY, "Acme")))
        assertEquals("Lovelace, Ada", CallerIdCopy.alternativeOf(JSONObject().put("name", "Ada").put(CallerIdCopy.C_NAME_ALT, "Lovelace, Ada")))
    }

    @Test fun labelsRoundTripAndBlankTitlesAreDropped() {
        val o = JSONObject()
        CallerIdCopy.putLabels(o, listOf(PrivateLabels.Membership(1, "Family"), PrivateLabels.Membership(2, " ")))
        assertEquals(listOf(PrivateLabels.Membership(1, "Family")), CallerIdCopy.labelsOf(o))
        CallerIdCopy.putLabels(o, emptyList())
        assertFalse(o.has(CallerIdCopy.C_LABELS))
    }

    @Test fun rebuiltKeepsWhatSurvivesALostKey() {
        val d = CallerIdCopy.rebuilt(7, copy())
        assertEquals(-7, d.id)
        assertEquals("Ana Silva", d.displayName)
        assertEquals(listOf("+351912345678", "+351213456789"), d.phones.map { it.value })
        assertEquals(listOf(2, 3), d.phones.map { it.type })
        assertEquals("Designer", d.title)
        assertEquals("Acme", d.company)
        assertEquals("From the climbing gym", d.context)
        assertEquals("Owes me lunch", d.pinnedNote)
        assertEquals("she/her", d.pronouns)
        // Before title and company were kept apart, the combined line becomes the title.
        assertEquals("CTO · Acme", CallerIdCopy.rebuilt(1, JSONObject().put("name", "Cy").put("sub", "CTO · Acme")).title)
    }
}
