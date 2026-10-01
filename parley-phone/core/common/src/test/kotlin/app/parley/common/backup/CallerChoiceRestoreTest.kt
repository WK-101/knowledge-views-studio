package app.parley.common.backup

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.extras.CallerChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerChoiceRestoreTest {
    private fun contact(id: Long, key: String, name: String, vararg numbers: String) =
        ContactSummary(id, key, name, null, false, numbers.map { PhoneEntry(it, 2, null) })

    private val buzz = CallerChoice(vibration = "sos", autoAnswer = true)

    private val here = listOf(
        contact(1, "0r1-ANA", "Ana Silva", "+33 6 12 34 56 78"),
        // Sam's old phone had a local contact whose key is the same as Bo's here.
        contact(2, "0r7-SAM", "Bo", "0700000000"),
        contact(3, "k-cy", "Cy"),
    )

    @Test fun a_new_key_finds_the_contact_by_number() {
        val r = CallerChoiceRestore.restore(listOf(CallerChoiceRestore.Entry(PersonRef("old", "Ana", setOf("612345678")), buzz)), here)
        assertEquals(mapOf("0r1-ANA" to buzz), r.choices)
        assertEquals(0, r.unmatched)
    }

    @Test fun the_same_key_for_someone_else_brings_nothing_back() {
        // Same key, different name and numbers: auto-answer must never land on Bo.
        val r = CallerChoiceRestore.restore(listOf(CallerChoiceRestore.Entry(PersonRef("0r7-SAM", "Sam", setOf("612000000")), buzz)), here)
        assertNull(r.choices["0r7-SAM"])
        assertEquals(1, r.unmatched)
    }

    @Test fun a_name_alone_brings_the_vibration_but_not_auto_answer() {
        val r = CallerChoiceRestore.restore(listOf(CallerChoiceRestore.Entry(PersonRef("old", "Cy"), buzz)), here)
        assertEquals(mapOf("k-cy" to CallerChoice(vibration = "sos")), r.choices)
    }

    @Test fun an_older_backup_by_key_only_keeps_the_vibration() {
        val r = CallerChoiceRestore.restore(listOf(CallerChoiceRestore.Entry(PersonRef("0r1-ANA"), buzz)), here)
        assertEquals(mapOf("0r1-ANA" to CallerChoice(vibration = "sos")), r.choices)
        // Auto-answer alone, matched by key only: nothing left to restore.
        val r2 = CallerChoiceRestore.restore(listOf(CallerChoiceRestore.Entry(PersonRef("0r1-ANA"), CallerChoice(autoAnswer = true))), here)
        assertEquals(emptyMap<String, CallerChoice>(), r2.choices)
        assertEquals(1, r2.unmatched)
    }

    @Test fun nobody_here_is_counted() {
        val r = CallerChoiceRestore.restore(listOf(CallerChoiceRestore.Entry(PersonRef("gone", "Dee", setOf("999999999")), buzz)), here)
        assertEquals(emptyMap<String, CallerChoice>(), r.choices)
        assertEquals(1, r.unmatched)
    }
}
