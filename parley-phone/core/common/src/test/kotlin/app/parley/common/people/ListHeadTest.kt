package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListHeadTest {
    private fun c(id: Long, name: String, vararg numbers: String) =
        ContactSummary(id, "lk$id", name, "content://photo/$id", id % 2 == 0L, numbers.mapIndexed { i, n -> PhoneEntry(n, 2, null, isPrimary = i == 1) })

    @Test fun keepsTheFirstScreenfulWithoutPrivateContacts() {
        val list = listOf(c(-3, "Private")) + (1L..100L).map { c(it, "Person $it", "+1 555 0100", "+1 555 0101") }
        val head = ListHead.decode(ListHead.encode(list))!!
        assertEquals(ListHead.ROWS, head.size)
        assertTrue(head.none { it.id < 0 })
        val first = head.first()
        assertEquals(1L, first.id)
        assertEquals("Person 1", first.displayName)
        assertEquals("content://photo/1", first.photoUri)
        // The number a row offers: the primary one.
        assertEquals(listOf("+1 555 0101"), first.phones.map { it.number })
    }

    @Test fun nothingKeptMeansWait() {
        assertNull(ListHead.decode(ListHead.encode(emptyList())))
        assertNull(ListHead.decode("not json"))
    }

    @Test fun writtenOnlyWhenTheHeadChanged() {
        val list = (1L..80L).map { c(it, "P$it") }
        val kept = ListHead.encode(list)
        // A change below the head doesn't count.
        assertFalse(ListHead.changed(list.take(70) + c(999, "Zed"), kept))
        assertTrue(ListHead.changed(listOf(c(500, "Aaron")) + list, kept))
    }
}
