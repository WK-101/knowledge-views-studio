package app.parley.common.people

import app.parley.common.MessengerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRoutesTest {
    @Test fun preferred_messenger_reads_the_old_value_and_writes_it_back_unchanged() {
        val legacy = MessengerPrefs.decode("com.whatsapp")
        assertEquals(MessengerPrefs(call = "com.whatsapp"), legacy)
        assertEquals("com.whatsapp", legacy.encode())
        val full = MessengerPrefs(call = "com.whatsapp", message = "org.thoughtcrime.securesms", video = "com.whatsapp", number = "+44 7700;900")
        assertEquals(full, MessengerPrefs.decode(full.encode()))
        assertNull(MessengerPrefs().encode())
        assertEquals(MessengerPrefs(), MessengerPrefs.decode(null))
    }

    @Test fun message_route_follows_links_before_data_rows_are_needed() {
        val numbers = listOf("+447700900123", "+441234567890")
        val wa = MessengerPrefs(message = "com.whatsapp")
        // WhatsApp hasn't linked the person (no data row) but is installed: open it by number.
        assertEquals(
            MessageRoute.MessengerLink(MessengerApp.of(app.parley.common.MessengerCatalog.WHATSAPP), "+447700900123"),
            MessageRoutes.plan(wa, linked = emptySet(), installed = setOf("com.whatsapp"), numbers = numbers, defaultNumber = numbers[0]),
        )
        assertEquals(MessageRoute.MessengerRow("com.whatsapp"), MessageRoutes.plan(wa, setOf("com.whatsapp"), setOf("com.whatsapp"), numbers, numbers[0]))
        assertEquals(MessageRoute.Ask, MessageRoutes.plan(wa, emptySet(), emptySet(), numbers, numbers[0]))
        assertEquals(MessageRoute.Ask, MessageRoutes.plan(MessengerPrefs(), emptySet(), setOf("com.whatsapp"), numbers, numbers[0]))
        val sms = MessengerPrefs(message = MessengerPrefs.SMS, number = "+441234567890")
        assertEquals(MessageRoute.Sms("+441234567890"), MessageRoutes.plan(sms, emptySet(), emptySet(), numbers, numbers[0]))
        // A remembered number the contact no longer has falls back to the default one.
        assertEquals(MessageRoute.Sms("+447700900123"), MessageRoutes.plan(sms.copy(number = "+15550000000"), emptySet(), emptySet(), numbers, numbers[0]))
        assertTrue(MessageRoutes.showUnlinkedHint("com.whatsapp", emptySet(), setOf("com.whatsapp")))
        assertFalse(MessageRoutes.showUnlinkedHint("com.whatsapp", setOf("com.whatsapp"), setOf("com.whatsapp")))
    }
}
