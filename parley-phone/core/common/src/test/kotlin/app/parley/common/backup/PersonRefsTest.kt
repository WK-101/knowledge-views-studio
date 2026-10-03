package app.parley.common.backup

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.calltime.CallingConfig
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope
import app.parley.common.calltime.ReminderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonRefsTest {
    private fun contact(id: Long, key: String, name: String, vararg numbers: String) =
        ContactSummary(id, key, name, null, false, numbers.map { PhoneEntry(it, 2, null) })

    private val here = listOf(
        contact(1, "k-ana", "Ana Silva", "+33 6 12 34 56 78"),
        contact(2, "k-bo", "Bo", "0700000000"),
        contact(3, "k-cy1", "Cy"),
        contact(4, "k-cy2", "Cy"),
    )

    @Test fun same_key_then_number_then_unique_name() {
        val refs = PersonRefs(here)
        assertEquals(1L, refs.resolve(PersonRef("k-ana"))?.id)
        // Another phone: other key, the number written nationally.
        assertEquals(1L, refs.resolve(PersonRef("old", "Ana", setOf("612345678")))?.id)
        assertEquals(2L, refs.resolve(PersonRef("old", "Bo"))?.id)
        // Two contacts named Cy: nobody is guessed.
        assertNull(refs.resolve(PersonRef("old", "Cy")))
        assertNull(refs.resolve(PersonRef("old")))
    }

    @Test fun refs_carry_name_and_portable_numbers() {
        val r = PersonRefs(here).ref("k-ana")
        assertEquals(PersonRef("k-ana", "Ana Silva", setOf("612345678")), r)
        assertEquals(PersonRef("gone"), PersonRefs(here).ref("gone"))
    }

    @Test fun call_time_keys_move_to_this_phone() {
        val cfg = CallingConfig(
            rules = listOf(
                LimitRule(LimitScope.CONTACT, "a1", dailyMinutes = 10),
                LimitRule(LimitScope.CONTACT, "zz", dailyMinutes = 5),
                LimitRule(LimitScope.SIM, "sim1", dailyMinutes = 60),
            ),
            neverLimit = setOf("a1", "zz"),
            reminders = ReminderSettings(perContact = mapOf("a1" to 5)),
            supervised = true,
        )
        assertEquals(setOf("a1", "zz"), CallTimeRestore.keys(cfg))
        val out = CallTimeRestore.remap(cfg) { k -> if (k == "a1") "k-ana" else null }
        assertEquals(listOf("k-ana", "sim1"), out.config.rules.map { it.key })
        assertEquals(setOf("k-ana"), out.config.neverLimit)
        assertEquals(mapOf("k-ana" to 5), out.config.reminders.perContact)
        assertEquals(true, out.config.supervised)
        assertEquals(2, out.unmatched)
    }
}
