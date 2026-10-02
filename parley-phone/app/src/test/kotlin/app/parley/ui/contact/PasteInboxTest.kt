package app.parley.ui.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L8: shared text reaches only the editor it was handed to, once, and not after a few minutes. */
class PasteInboxTest {
    @Test fun the_text_is_bound_to_its_editor_and_read_once() {
        val id = PasteInbox.put("Ana Pérez +44 7700 900123", now = 1_000)
        assertNull(PasteInbox.take("another-id", now = 1_001))
        assertNull(PasteInbox.take(null, now = 1_001))
        assertEquals("Ana Pérez +44 7700 900123", PasteInbox.take(id, now = 1_002))
        assertNull(PasteInbox.take(id, now = 1_003))
    }

    @Test fun forgotten_text_expires() {
        val id = PasteInbox.put("Bo", now = 0)
        assertNull(PasteInbox.take(id, now = 11 * 60_000L))
    }
}
