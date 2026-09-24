package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M1: messenger links and the number parsing behind them (with WhatsOpen's regressions). */
class MessengerLinksTest {
    private val pk = "+923001234567"

    // ---- Number → E.164 with the SIM country ----

    @Test fun national_number_uses_the_sim_country_not_its_digits() {
        // WhatsOpen #5: a Pakistani 03… number became +32 (Belgium).
        assertEquals(pk, NumberText.toE164("0300 1234567", "PK"))
        assertEquals(pk, NumberText.toE164("03001234567", "pk"))
        assertEquals("PK", NumberText.regionOf(pk))
    }

    @Test fun trunk_zero_is_stripped() {
        assertEquals("+33612345678", NumberText.toE164("06 12 34 56 78", "FR"))
        assertEquals("+447700900123", NumberText.toE164("07700 900123", "GB"))
        assertEquals("+4915112345678", NumberText.toE164("0151 12345678", "DE"))
    }

    @Test fun international_numbers_keep_their_country() {
        assertEquals("+33612345678", NumberText.toE164("+33 6 12 34 56 78", "PK"))
        assertEquals("+33612345678", NumberText.toE164("0033 6 12 34 56 78", "DE"))
        assertEquals("+15551234567", NumberText.toE164("+1 555-123-4567", "PK"))
    }

    @Test fun short_and_service_codes_have_no_international_form() {
        assertNull(NumberText.toE164("*100#", "PK"))
        assertNull(NumberText.toE164("", "PK"))
    }

    // ---- Numbers in free text (M3) ----

    @Test fun plus_one_number_in_text_is_one_number() {
        // WhatsOpen split "+1 555-123-4567" at the space and opened +55 (Brazil).
        val found = NumberText.find("Call me at +1 555-123-4567 tomorrow", "PK")
        assertEquals(1, found.size)
        assertEquals("+15551234567", found[0].e164)
        assertEquals("+1 555-123-4567", found[0].raw)
    }

    @Test fun several_numbers_in_text() {
        val text = "Office: 01 42 68 53 00, mobile 06 12 34 56 78 or +44 20 7946 0958. Office again: +33 1 42 68 53 00"
        val found = NumberText.find(text, "FR")
        assertEquals(listOf("+33142685300", "+33612345678", "+442079460958"), found.map { it.e164 })
        assertEquals(text.substring(found[1].range), found[1].raw)
    }

    @Test fun national_number_in_text_uses_region() {
        assertEquals(pk, NumberText.find("mera number 0300-1234567 hai", "PK").single().e164)
    }

    @Test fun short_code_text() {
        val found = NumberText.find(" 112 ", "FR").single()
        assertEquals("112", found.raw)
        assertTrue(NumberText.find("no numbers here", "FR").isEmpty())
    }

    // ---- Links: one per app, always an explicit package ----

    @Test fun whatsapp() {
        val l = MessengerLinks.build(MessengerApp.WHATSAPP, pk, "Hi there & bye")!!
        assertEquals("https://wa.me/923001234567?text=Hi%20there%20%26%20bye", l.uri)
        assertEquals("com.whatsapp", l.packageName)
        assertEquals(MessengerLink.ACTION_VIEW, l.action)
        assertEquals("com.whatsapp.w4b", MessengerLinks.build(MessengerApp.WHATSAPP_BUSINESS, pk)!!.packageName)
        assertEquals("https://wa.me/923001234567", MessengerLinks.build(MessengerApp.WHATSAPP, pk, "  ")!!.uri)
    }

    @Test fun signal_and_molly() {
        val l = MessengerLinks.build(MessengerApp.SIGNAL, pk, "ignored")!!
        assertEquals("sgnl://signal.me/#p/+923001234567", l.uri)
        assertEquals("org.thoughtcrime.securesms", l.packageName)
        assertEquals("im.molly.app", MessengerLinks.build(MessengerApp.MOLLY, pk)!!.packageName)
        assertFalse(MessengerApp.SIGNAL.takesText)
    }

    @Test fun telegram_all_three_apps() {
        val l = MessengerLinks.build(MessengerApp.TELEGRAM, pk, "Привет")!!
        assertEquals("tg://resolve?phone=923001234567&text=%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82", l.uri)
        assertEquals("org.telegram.messenger", l.packageName)
        assertEquals("org.thunderdog.challegram", MessengerLinks.build(MessengerApp.TELEGRAM_X, pk)!!.packageName)
        assertEquals("org.telegram.messenger.web", MessengerLinks.build(MessengerApp.TELEGRAM_WEB, pk)!!.packageName)
    }

    @Test fun viber() {
        val l = MessengerLinks.build(MessengerApp.VIBER, pk)!!
        assertEquals("viber://chat?number=%2B923001234567", l.uri)
        assertEquals("com.viber.voip", l.packageName)
    }

    @Test fun sms() {
        val l = MessengerLinks.sms("0300 1234567", pk, "Hello", "org.fossify.messages")
        assertEquals(MessengerLink.ACTION_SENDTO, l.action)
        assertEquals("smsto:+923001234567", l.uri)
        assertEquals("Hello", l.extras[MessengerLink.EXTRA_SMS_BODY])
        assertEquals("org.fossify.messages", l.packageName)
        // Short code: sent as typed.
        assertEquals("smsto:%2A100%23", MessengerLinks.sms("*100#", null, null, null).uri)
    }

    @Test fun every_messenger_link_has_a_package_and_no_web_fallback() {
        for (app in MessengerApp.entries) {
            val l = MessengerLinks.build(app, pk, "x")!!
            assertEquals(app.packageName, l.packageName)
            // wa.me is WhatsApp's own verified link; nothing else is a web address.
            if (app.messenger != Messenger.WHATSAPP) assertFalse(l.uri.startsWith("http"))
        }
        assertEquals("Install or enable WhatsApp Business", MessengerLinks.unavailableMessage(MessengerApp.WHATSAPP_BUSINESS))
    }

    @Test fun national_number_is_refused() {
        for (app in MessengerApp.entries) assertNull(MessengerLinks.build(app, "03001234567"))
        assertNull(MessengerLinks.build(MessengerApp.WHATSAPP, "+0123456789"))
        assertNull(MessengerLinks.build(MessengerApp.WHATSAPP, "+12"))
    }

    @Test fun packages_are_unique() {
        assertEquals(MessengerApp.entries.size, MessengerApp.entries.map { it.packageName }.toSet().size)
        assertNotNull(MessengerApp.forPackage("im.molly.app"))
    }

    @Test fun my_details_draft() {
        assertEquals("Hi, this is Ana. My number is +33 6 12 34 56 78.", MessageDrafts.myDetails("Ana", "+33 6 12 34 56 78"))
        assertEquals("Hi, this is Ana.", MessageDrafts.myDetails(" Ana ", " "))
        assertNull(MessageDrafts.myDetails(null, ""))
    }
}
