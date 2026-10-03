package app.parley.common

import app.parley.common.calls.MenuMemory
import app.parley.common.calls.MenuPath
import app.parley.common.calls.MenuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stores keyed by a line's key before 5.4 may hold the older E.164 form of numbers libphonenumber now reads
 * differently (an Argentine "15" mobile, Slovak and Ivorian numbers): reads try both forms.
 */
class StoredKeyFormsTest {
    private val ar = "011 15 2345 6789"
    private val arOld = "+54111523456789"
    private val sk = "0912 123 456"
    private val skOld = "+4210912123456"

    @Test fun the_older_form_follows_the_current_one() {
        assertEquals(listOf("+5491123456789", arOld), PhoneIdentity.keyForms(ar, "AR"))
        assertEquals(listOf("+421912123456", skOld), PhoneIdentity.exactKeyForms(sk, "SK"))
        // Numbers whose key didn't change have one form.
        assertEquals(listOf("+33612345678"), PhoneIdentity.keyForms("06 12 34 56 78", "FR"))
        assertTrue(PhoneIdentity.keyForms("", "FR").isEmpty())
    }

    @Test fun a_messaged_entry_from_before_is_found_replaced_and_forgotten() {
        val old = listOf(MessagedEntry(arOld, ar, "org.example.chat", "Chat", 1_000))
        assertEquals(old.single(), MessagedRecord.find(old, ar, "AR"))
        val again = MessagedRecord.record(old, ar, "org.example.chat", "Chat", 2_000, "AR")
        assertEquals(listOf("+5491123456789"), again.map { it.key })
        // "Forget this number" leaves nothing behind, the old entry with its plaintext number included.
        assertTrue(MessagedRecord.forget(old, ar, "AR").isEmpty())
    }

    @Test fun a_menu_remembered_before_is_still_offered_unless_stopped() {
        val path = MenuPath(emptyList(), 1_000, sk)
        val state = MenuState(paths = mapOf(skOld to path))
        assertEquals(path, MenuMemory.pathFor(state, sk, "SK"))
        val stopped = MenuMemory.setOptOut(state, MenuMemory.key(sk, "SK"), true)
        assertNull(MenuMemory.pathFor(stopped, sk, "SK"))
    }
}
