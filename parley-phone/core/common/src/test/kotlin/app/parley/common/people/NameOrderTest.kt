package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.ux.ListSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NameOrderTest {
    private fun person(id: Long, name: String, alt: String) =
        ContactSummary(id, "k$id", name, null, false, emptyList(), displayNameAlt = alt)

    // In the address book's first-name order.
    private val book = listOf(
        person(1, "Ann Young", "Young, Ann"),
        person(2, "Bob Adams", "Adams, Bob"),
        person(3, "Cy Marsh", "Marsh, Cy"),
    )
    private val cmp = Comparator<String> { a, b -> a.compareTo(b, ignoreCase = true) }

    @Test fun old_single_setting_moves_to_both() {
        // Never saved: names show the way they were sorted.
        assertFalse(NameOrder.showLastFirst(null, sortByFirstName = true))
        assertTrue(NameOrder.showLastFirst(null, sortByFirstName = false))
        // Saved since: its own value, whatever the sort order.
        assertFalse(NameOrder.showLastFirst(false, sortByFirstName = false))
        assertTrue(NameOrder.showLastFirst(true, sortByFirstName = true))
    }

    @Test fun first_name_both_ways_keeps_the_list() {
        assertSame(book, NameOrder.apply(book, sortByFirstName = true, lastFirst = false, compare = cmp))
    }

    @Test fun sort_by_last_name_show_first_name_first() {
        val r = NameOrder.apply(book, sortByFirstName = false, lastFirst = false, compare = cmp)
        assertEquals(listOf("Bob Adams", "Cy Marsh", "Ann Young"), r.map { it.displayName })
        assertEquals(listOf("Adams, Bob", "Marsh, Cy", "Young, Ann"), r.map { it.sortName })
        // Headers and the A–Z rail follow the sort order, not the shown name.
        assertEquals(listOf("A", "M", "Y"), ListSections.interleave(r) { ListSections.letterOf(it.sortName) }
            .filterIsInstance<ListSections.Row.Header<String, ContactSummary>>().map { it.section })
    }

    @Test fun sort_by_first_name_show_last_name_first() {
        val r = NameOrder.apply(book, sortByFirstName = true, lastFirst = true, compare = cmp)
        assertEquals(listOf("Young, Ann", "Adams, Bob", "Marsh, Cy"), r.map { it.displayName })
        assertEquals(listOf("Ann Young", "Bob Adams", "Cy Marsh"), r.map { it.sortName })
    }

    @Test fun last_name_both_ways() {
        val r = NameOrder.apply(book, sortByFirstName = false, lastFirst = true, compare = cmp)
        assertEquals(listOf("Adams, Bob", "Marsh, Cy", "Young, Ann"), r.map { it.displayName })
        assertEquals(r.map { it.displayName }, r.map { it.sortName })
    }

    @Test fun shown_name_falls_back_to_the_primary_one() {
        assertEquals("Jones, Robert", NameOrder.shown("Robert Jones", "Jones, Robert", lastFirst = true))
        assertEquals("Robert Jones", NameOrder.shown("Robert Jones", "Jones, Robert", lastFirst = false))
        assertEquals("Robert Jones", NameOrder.shown("Robert Jones", null, lastFirst = true))
        assertEquals("Robert Jones", NameOrder.shown("Robert Jones", " ", lastFirst = true))
    }

    @Test fun a_nickname_sorts_by_itself() {
        val bob = NameOrder.apply(book, sortByFirstName = false, lastFirst = false, compare = cmp)[0]
        val nick = NameOrder.renamed(bob, "Bobby")
        assertEquals("Bobby", nick.displayName)
        assertEquals("Bobby", nick.sortName)
        assertSame(bob, NameOrder.renamed(bob, bob.displayName))
    }

    @Test fun private_contacts_merge_by_the_sort_name() {
        val sorted = NameOrder.apply(book, sortByFirstName = false, lastFirst = false, compare = cmp)
        val private = PrivateListing.row(7, "Lee", emptyList(), false, null)
        val merged = PrivateListing.merge(sorted, listOf(private), cmp)
        assertEquals(listOf("Bob Adams", "Lee", "Cy Marsh", "Ann Young"), merged.map { it.displayName })
    }

    @Test fun favourites_by_name_follow_the_sort_order() {
        val favs = NameOrder.apply(book, sortByFirstName = false, lastFirst = false, compare = cmp).reversed()
        val r = FavoriteOrder.sort(favs, FavoriteSort.NAME, emptyList(), collator = cmp)
        assertEquals(listOf("Bob Adams", "Cy Marsh", "Ann Young"), r.map { it.displayName })
    }
}
