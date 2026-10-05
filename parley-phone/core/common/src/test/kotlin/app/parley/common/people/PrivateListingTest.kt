package app.parley.common.people

import app.parley.common.ContactSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateListingTest {
    private fun device(id: Long, name: String) = ContactSummary(id, "lk$id", name, null, false, emptyList())
    private val order = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }

    @Test fun private_rows_are_merged_in_name_order_without_reordering_the_device_list() {
        val list = listOf(device(1, "Ana"), device(2, "Carl"), device(3, "Eve"))
        val merged = PrivateListing.merge(
            list,
            listOf(
                PrivateListing.row(9, "zed", listOf("1"), false, null), PrivateListing.row(8, "Bea", emptyList(), false, null),
                PrivateListing.row(7, "Carl", emptyList(), true, null),
            ),
            order,
        )
        assertEquals(listOf("Ana", "Bea", "Carl", "Carl", "Eve", "zed"), merged.map { it.displayName })
        // A tie keeps the device contact first.
        assertEquals(2L, merged[2].id)
        assertEquals(-7L, merged[3].id)
        assertEquals(list, merged.filterNot(PrivateListing::isPrivate))
    }

    @Test fun a_private_row_carries_its_nav_id_key_star_and_numbers() {
        val row = PrivateListing.row(5, "Sam", listOf("+441", "+442"), true, "content://x")
        assertEquals(-5L, row.id)
        assertEquals(ContactRef.privateKey(5), row.lookupKey)
        assertTrue(row.starred)
        assertEquals(listOf("+441", "+442"), row.phones.map { it.number })
        assertTrue(PrivateListing.isPrivate(row))
        assertFalse(PrivateListing.isPrivate(device(1, "A")))
    }

    @Test fun no_private_rows_returns_the_same_list() {
        val list = listOf(device(1, "Ana"))
        assertTrue(list === PrivateListing.merge(list, emptyList(), order))
    }

    @Test fun the_whole_list_waits_for_the_private_rows_so_its_first_showing_already_has_them() {
        val list = listOf(device(1, "Ana"), device(2, "Carl"))
        val bea = PrivateListing.row(8, "Bea", listOf("+44 7700 900001"), false, null)
        // The address book first, the private listing still opening: nothing yet, not a list without Bea.
        assertNull(PrivateListing.whole(list, null, hidden = false, compare = order))
        assertNull(PrivateListing.whole(null, listOf(bea), hidden = false, compare = order))
        // Both: Bea in her place from the first showing on, nothing moves later.
        assertEquals(listOf("Ana", "Bea", "Carl"), PrivateListing.whole(list, listOf(bea), hidden = false, compare = order)!!.map { it.displayName })
        // No private contacts at all: the address book's list as it is.
        assertEquals(list, PrivateListing.whole(list, emptyList(), hidden = false, compare = order))
        // Hidden (Hide private contacts, a duress unlock): never waited for, never listed.
        assertEquals(list, PrivateListing.whole(list, null, hidden = true, compare = order))
        assertEquals(list, PrivateListing.whole(list, listOf(bea), hidden = true, compare = order))
    }

    @Test fun ten_thousand_contacts_merge_with_their_private_ones_in_one_pass() {
        val list = (0 until 10_000).map { device(it + 1L, "Person %05d".format(it)) }
        val private = (0 until 300).map { PrivateListing.row(it + 1L, "Person %05d b".format(it * 33), emptyList(), false, null) }
        val started = System.nanoTime()
        val whole = PrivateListing.whole(list, private, hidden = false, compare = order)!!
        val ms = (System.nanoTime() - started) / 1_000_000
        println("PrivateListing.whole: 10000 + 300 rows in $ms ms")
        assertEquals(10_300, whole.size)
        assertEquals(list, whole.filterNot(PrivateListing::isPrivate))
        assertEquals(whole.map { it.sortName }.sortedWith(order), whole.map { it.sortName })
    }
}
