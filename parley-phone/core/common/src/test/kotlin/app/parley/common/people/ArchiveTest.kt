package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Archived contacts: where Unarchive puts them back, how an archived number is named on a call, their keys. */
class ArchiveTest {
    private val google = ArchivedAccount("com.google", "ana@example.com")
    private val phone = ArchivedAccount(null, null)

    @Test fun unarchive_goes_back_where_it_came_from_or_asks() {
        assertEquals(Archive.Target.Original, Archive.target(listOf(phone), emptySet()))
        assertEquals(Archive.Target.Original, Archive.target(listOf(google, phone), setOf(google)))
        // Signed out of that account: the user picks one.
        assertEquals(Archive.Target.Ask(listOf(google)), Archive.target(listOf(google, phone), emptySet()))
        // A phone account of the maker counts as the phone.
        val samsung = ArchivedAccount("vnd.sec.contact.phone", "vnd.sec.contact.phone")
        assertEquals(Archive.Target.Original, Archive.target(listOf(samsung), emptySet()) { it == null || it == samsung.type })
    }

    @Test fun an_archived_number_is_named_whichever_way_it_is_written() {
        val ana = ArchivedCard(1, "Ana Lima", listOf("+34 612 345 678"), archivedAt = 5)
        val ben = ArchivedCard(2, "Ben Ruiz", listOf("020 7946 0000"))
        val index = Archive.index(listOf(ana, ben), "ES")
        assertEquals("Ana Lima", index["612345678"]?.name)
        assertEquals("Ana Lima", index["0034612345678"]?.name)
        assertNull(index["+34 612 345 679"])
        assertEquals("Ben Ruiz", Archive.index(listOf(ana, ben), "GB")["+44 20 7946 0000"]?.name)
    }

    @Test fun archived_keys_are_parleys_own() {
        val key = ContactRef.archivedKey(7)
        assertEquals(key, ArchivedCard(7, "Ana").parleyKey)
        assertTrue(ContactRef.isArchivedKey(key))
        assertTrue(ContactRef.isParleyOnlyKey(key))
        assertTrue(ContactRef.isParleyOnlyKey(ContactRef.privateKey(7)))
        assertFalse(ContactRef.isPrivateKey(key))
        assertNull(ContactRef.vaultIdOf(key))
        assertFalse(ContactRef.isParleyOnlyKey("0r12-ABCD"))
    }

    @Test fun a_card_survives_storage() {
        val card = ArchivedCard(3, "Ana", listOf("+34612345678"), 99, listOf(google), "lk-1", "Acme")
        assertEquals(card, Archive.decode(Archive.encode(card)))
        assertNull(Archive.decode("{"))
        assertNull(Archive.decode(null))
    }
}
