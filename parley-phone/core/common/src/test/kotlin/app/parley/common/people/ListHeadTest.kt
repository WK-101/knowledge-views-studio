package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.ux.ListSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Contacts list's first screen kept for a cold start: what it keeps, when its private rows may show (never under
 * discreet mode, a duress unlock or "Lock private contacts", and never when what may be shown can't be read), and that
 * the real list takes over row for row.
 */
class ListHeadTest {
    private fun c(id: Long, name: String, vararg numbers: String) =
        ContactSummary(id, "lk$id", name, "content://photo/$id", id % 2 == 0L, numbers.mapIndexed { i, n -> PhoneEntry(n, 2, null, isPrimary = i == 1) })

    private fun rows(list: List<ContactSummary>) = ListSections.interleave(list) { ListSections.letterOf(it.sortName) }

    private fun items(rows: List<ListSections.Row<String, ContactSummary>>?) =
        rows.orEmpty().filterIsInstance<ListSections.Row.Item<ContactSummary>>().map { it.item }

    /** Ana and Zoe private among 100 address-book contacts, in name order as the list shows them. */
    private val merged = (listOf(c(-3, "Ana Private"), c(-9, "Bea Private")) + (1L..100L).map { c(it, "Person %03d".format(it), "+1 555 0100", "+1 555 0101") })
        .sortedBy { it.displayName }

    private val name = ContactSort.NAME.name
    private val open = ListHead.Access(privateListed = true, privateMayShow = true, sort = name)

    @Test fun keeps_the_first_screenful_as_shown() {
        val kept = ListHead.decode(ListHead.encode(rows(merged), withPrivate = true, sort = name))!!
        val shown = items(kept.rows)
        assertEquals(ListHead.ROWS, shown.size)
        assertEquals(listOf(-3L, -9L), shown.take(2).map { it.id })
        val first = shown.first { it.id == 1L }
        assertEquals("Person 001", first.displayName)
        assertEquals("content://photo/1", first.photoUri)
        // The number a row offers: the primary one.
        assertEquals(listOf("+1 555 0101"), first.phones.map { it.number })
    }

    @Test fun nothing_kept_means_wait() {
        assertNull(ListHead.decode(ListHead.encode(emptyList())))
        assertNull(ListHead.decode("not json"))
        assertNull(ListHead.shown(null, open))
    }

    @Test fun private_rows_are_kept_only_when_they_were_listed() {
        val kept = ListHead.decode(ListHead.encode(rows(merged), withPrivate = false, sort = name))!!
        assertTrue(items(kept.rows).none { it.id < 0 })
        assertFalse(kept.withPrivate)
    }

    @Test fun private_rows_show_only_when_they_may() {
        val kept = ListHead.decode(ListHead.encode(rows(merged), listOf(c(-3, "Ana Private"), c(2, "Person 002")), withPrivate = true, sort = name))
        // Unlocked, not hidden: the whole head, private rows where they were.
        assertEquals(listOf(-3L, -9L), items(ListHead.shown(kept, open)).take(2).map { it.id })
        assertEquals(listOf(-3L, 2L), ListHead.shownFavourites(kept, open).map { it.id })

        // Hidden (discreet mode) or a duress unlock: private contacts aren't listed, so the rest shows without them.
        val hidden = ListHead.Access(privateListed = false, privateMayShow = false, sort = name)
        val withoutPrivate = items(ListHead.shown(kept, hidden))
        assertTrue(withoutPrivate.isNotEmpty())
        assertTrue(withoutPrivate.none { it.id < 0 })
        assertTrue(ListHead.shownFavourites(kept, hidden).none { it.id < 0 })
        // Still in list order with its headers, each header above its first row.
        val shownRows = ListHead.shown(kept, hidden)!!
        assertTrue(shownRows.first() is ListSections.Row.Header)
        shownRows.zipWithNext().forEach { (a, b) -> if (a is ListSections.Row.Header) assertEquals(a.first, (b as ListSections.Row.Item).item) }

        // Locked with "Lock private contacts": they are still listed, but not drawn from here, so nothing is (no pop-in).
        val locked = ListHead.Access(privateListed = true, privateMayShow = false, sort = name)
        assertNull(ListHead.shown(kept, locked))
        assertTrue(ListHead.shownFavourites(kept, locked).isEmpty())

        // The facts couldn't be read: fail closed.
        assertNull(ListHead.shown(kept, ListHead.Access.CLOSED))
    }

    @Test fun a_head_without_private_rows_waits_when_private_contacts_are_listed_now() {
        // Kept while they were hidden: they may fall inside the first screen, so the list waits and appears whole.
        val kept = ListHead.decode(ListHead.encode(rows(merged), withPrivate = false, sort = name))
        assertNull(ListHead.shown(kept, open))
        assertNotNull(ListHead.shown(kept, open.copy(privateListed = false)))
    }

    @Test fun another_order_waits() {
        val kept = ListHead.decode(ListHead.encode(rows(merged), withPrivate = true, sort = name))
        assertNull(ListHead.shown(kept, open.copy(sort = ContactSort.COMPANY.name)))
    }

    @Test fun locking_takes_private_rows_out() {
        val text = ListHead.encode(rows(merged), listOf(c(-3, "Ana Private")), withPrivate = true, sort = name)
        val dropped = ListHead.decode(ListHead.dropPrivate(text)!!)!!
        assertFalse(dropped.withPrivate)
        assertTrue(items(dropped.rows).none { it.id < 0 })
        assertTrue(dropped.favourites.none { it.id < 0 })
        // Nothing private is left in the text either.
        assertFalse(ListHead.dropPrivate(text)!!.contains("Private"))
    }

    @Test fun the_real_list_takes_over_row_for_row() {
        val live = rows(merged)
        val kept = ListHead.shown(ListHead.decode(ListHead.encode(live, withPrivate = true, sort = name)), open)!!

        // Same rows, same order, same headers: the list's keys (ids, "s<letter>") don't move when it arrives.
        fun keys(r: List<ListSections.Row<String, ContactSummary>>) =
            r.map { if (it is ListSections.Row.Header) "s${it.section}" else "${(it as ListSections.Row.Item).item.id}" }
        assertEquals(keys(live).take(kept.size), keys(kept))

        // One contact renamed below the fold, one added further down: the head is still the real list's top.
        val renamed = merged.map { if (it.id == 90L) it.copy(displayName = "Person 090b", sortName = "Person 090b") else it }
        val changed = rows((renamed + c(500, "Zed")).sortedBy { it.displayName })
        assertEquals(keys(changed).take(kept.size), keys(kept))
        // Rewritten only when the head itself changed.
        assertFalse(ListHead.changed(ListHead.encode(changed, withPrivate = true, sort = name), ListHead.encode(live, withPrivate = true, sort = name)))
        val renamedOnTop = rows(merged.map { if (it.id == 1L) it.copy(displayName = "Person 001 Jr", sortName = "Person 001 Jr") else it })
        assertTrue(ListHead.changed(ListHead.encode(renamedOnTop, withPrivate = true, sort = name), ListHead.encode(live, withPrivate = true, sort = name)))
    }

    @Test fun a_head_kept_by_an_older_version_still_shows() {
        val rowsText = (1..3).joinToString(",") { """{"id":$it,"key":"lk$it","name":"P$it","sortName":"P$it"}""" }
        val old = "[" + rowsText + """,{"id":-4,"key":"p","name":"X","sortName":"X"}]"""
        val kept = ListHead.decode(old)!!
        assertEquals(listOf(1L, 2L, 3L), items(kept.rows).map { it.id })
        assertNotNull(ListHead.shown(kept, ListHead.Access(privateListed = false, privateMayShow = true, sort = name)))
        assertNull("it never held private rows", ListHead.shown(kept, open))
    }
}
