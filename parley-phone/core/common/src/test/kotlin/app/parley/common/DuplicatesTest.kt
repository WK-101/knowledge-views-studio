package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicatesTest {
    private fun c(id: Long, name: String, vararg nums: String) =
        ContactSummary(id, "k$id", name, null, false, nums.map { PhoneEntry(it, 2, null) })

    @Test fun groups_by_number_and_name() {
        val groups = Duplicates.find(
            listOf(
                c(1, "Ann Lee", "+33 6 12 34 56 78"),
                c(2, "Annie", "0612345678"),
                c(3, "Lee Ann"),
                c(4, "Bob", "555"),
                c(5, "Bob"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf(1L, 2L, 3L), groups[0].map { it.id }.toSet())
    }
}
