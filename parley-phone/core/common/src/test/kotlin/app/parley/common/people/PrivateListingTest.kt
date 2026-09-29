package app.parley.common.people

import app.parley.common.ContactSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
