package app.parley.common

import app.parley.common.messaging.BulkAdd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkAddTest {
    private val text = """
        Leads from the fair:
        Ana 0300 1234567, Bilal +92 301 7654321
        again Ana: +92 300 1234567
        broken: 0300 12
        Saved one: 0333 5550000
    """.trimIndent()

    // ---- M11: review ----

    @Test fun finds_every_number_including_repeats() {
        val found = NumberText.find(text, "PK", distinct = false)
        // "0300 12" is found too (a possible number), and later marked invalid.
        assertEquals(listOf("+923001234567", "+923017654321", "+923001234567", "+92030012", "+923335550000"), found.mapNotNull { it.e164 })
        // The default still removes repeats (the pick list).
        assertEquals(4, NumberText.find(text, "PK").size)
    }

    @Test fun review_marks_contacts_private_duplicates_and_invalid() {
        val found = NumberText.find(text, "PK", distinct = false) + NumberText.Found("+99912", "+99912", 0..0)
        val list = BulkAdd.review(
            found, "PK",
            contactName = { if (it == "+923335550000") "Sam" else null },
            privateName = { if (it == "+923017654321") "Bilal" else null },
        )
        assertEquals(
            listOf(BulkAdd.Status.NEW, BulkAdd.Status.PRIVATE, BulkAdd.Status.DUPLICATE, BulkAdd.Status.INVALID, BulkAdd.Status.CONTACT, BulkAdd.Status.INVALID),
            list.map { it.status },
        )
        assertEquals("Sam", list[4].existingName)
        assertEquals(listOf(true, false, false, false, false, false), list.map { it.checked })
        assertFalse(list[2].selectable)
        assertTrue(list[1].selectable)
        assertEquals("1 new · 1 already a contact · 1 already a private contact · 1 repeated in this list · 2 not a valid number", BulkAdd.summary(list))
    }

    @Test fun review_caps_the_list() {
        val many = (1..600).map { NumberText.Found("x$it", "+9230012${it.toString().padStart(5, '0')}", 0..0) }
        assertEquals(BulkAdd.MAX_NUMBERS, BulkAdd.review(many, "PK", { null }, { null }).size)
    }

    // ---- M11: naming ----

    @Test fun naming_patterns() {
        assertEquals("Lead 03", BulkAdd.name(BulkAdd.Pattern.NUMBERED.template, "Lead", 3, 12, "+92 300 1234567"))
        assertEquals("Lead 3", BulkAdd.name(BulkAdd.Pattern.NUMBERED.template, "Lead", 3, 9, "+92 300 1234567"))
        assertEquals("Fair · +92 300 1234567", BulkAdd.name(BulkAdd.Pattern.WITH_NUMBER.template, "Fair", 1, 2, "+92 300 1234567"))
        // No prefix: no leading separator.
        assertEquals("+92 300 1234567", BulkAdd.name(BulkAdd.Pattern.WITH_NUMBER.template, "  ", 1, 2, "+92 300 1234567"))
        assertEquals("Parent #2 (+1 555)", BulkAdd.name("Parent #{n} ({number})", "", 2, 5, "+1 555"))
        // A blank template result falls back to the number.
        assertEquals("+1 555", BulkAdd.name("{prefix}", "", 1, 1, "+1 555"))
    }

    @Test fun custom_template_without_placeholders_gets_a_number() {
        assertEquals("Team {n}", BulkAdd.template(BulkAdd.Pattern.CUSTOM, "Team"))
        assertEquals("{prefix} {n}", BulkAdd.template(BulkAdd.Pattern.CUSTOM, "  "))
        assertEquals("{number} x", BulkAdd.template(BulkAdd.Pattern.CUSTOM, "{number} x"))
        assertEquals("{prefix} · {number}", BulkAdd.template(BulkAdd.Pattern.WITH_NUMBER, "ignored"))
    }
}
