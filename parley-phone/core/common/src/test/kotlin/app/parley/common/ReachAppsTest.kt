package app.parley.common

import app.parley.common.record.Messengers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Messenger mimetype → app and action, grouping per number, and honest call routes. */
class ReachAppsTest {
    private val item = MessengerMimes.ITEM

    private fun row(mime: String, type: String?, d1: String? = null, d2: String? = null, d3: String? = null, phone: String? = null, id: Long = 1) =
        MessengerMimes.classify(id, mime, type, d1, d2, d3, phone)

    // ---- Mimetype → app and action ----

    @Test fun signal_rows_are_message_voice_and_video() {
        val t = "org.thoughtcrime.securesms"
        val msg = row("${item}vnd.org.thoughtcrime.securesms.contact", t, "+15551234567", "Signal", "Message +15551234567")!!
        val voice = row("${item}vnd.org.thoughtcrime.securesms.call", t, "+15551234567", "Signal", "Signal Voice Call +15551234567")!!
        val video = row("${item}vnd.org.thoughtcrime.securesms.videocall", t, "+15551234567", "Signal", "Signal Video Call +15551234567")!!
        assertEquals(ReachKind.MESSAGE, msg.kind)
        assertEquals(ReachKind.VOICE, voice.kind)
        assertEquals(ReachKind.VIDEO, video.kind)
        listOf(msg, voice, video).forEach {
            assertEquals(ReachApp.SIGNAL, it.app)
            assertEquals(t, it.appKey)
            assertEquals("+15551234567", it.number)
        }
    }

    @Test fun kind_does_not_depend_on_the_localised_label() {
        // A French "Appel vocal Signal" label has no "call" in it; the mimetype decides.
        val voice = row("${item}vnd.org.thoughtcrime.securesms.call", "org.thoughtcrime.securesms", d3 = "Appel vocal Signal +33 6 12 34 56 78")!!
        assertEquals(ReachKind.VOICE, voice.kind)
        val msg = row("${item}vnd.org.thoughtcrime.securesms.contact", "org.thoughtcrime.securesms", d3 = "Anruf? Nachricht an +49 151 2345678")!!
        assertEquals(ReachKind.MESSAGE, msg.kind)
    }

    @Test fun molly_shares_signals_mimetypes_but_is_its_own_app() {
        val r = row("${item}vnd.org.thoughtcrime.securesms.call", "im.molly.app")!!
        assertEquals(ReachApp.MOLLY, r.app)
        assertEquals("im.molly.app", r.appKey)
        assertEquals(ReachKind.VOICE, r.kind)
    }

    @Test fun whatsapp_and_business_are_told_apart() {
        assertEquals(ReachKind.MESSAGE, MessengerMimes.kind("${item}vnd.com.whatsapp.profile"))
        assertEquals(ReachKind.VOICE, MessengerMimes.kind("${item}vnd.com.whatsapp.voip.call"))
        assertEquals(ReachKind.VIDEO, MessengerMimes.kind("${item}vnd.com.whatsapp.video.call"))
        assertEquals(ReachKind.VIDEO, MessengerMimes.kind("${item}vnd.com.whatsapp.w4b.video.call"))
        assertEquals(ReachApp.WHATSAPP_BUSINESS, ReachApp.forMime("${item}vnd.com.whatsapp.w4b.voip.call"))
        assertEquals(ReachApp.WHATSAPP, ReachApp.forMime("${item}vnd.com.whatsapp.voip.call"))
        // WhatsApp's DATA1 is its "digits@s.whatsapp.net" id.
        val r = row("${item}vnd.com.whatsapp.voip.call", "com.whatsapp", d1 = "923001234567@s.whatsapp.net", d3 = "Voice call +92 300 1234567")!!
        assertEquals("+923001234567", r.number)
    }

    @Test fun telegram_uses_the_prompt_not_the_user_id() {
        val r = row("${item}vnd.org.telegram.messenger.android.call.video", "org.telegram.messenger", d1 = "123456789", d3 = "Video call +44 7700 900123")!!
        assertEquals(ReachApp.TELEGRAM, r.app)
        assertEquals(ReachKind.VIDEO, r.kind)
        assertEquals("+447700900123", r.number)
        val noPhone = row("${item}vnd.org.telegram.messenger.android.profile", "org.telegram.messenger", d1 = "123456789", d3 = "Message")!!
        assertNull(noPhone.number)
    }

    @Test fun viber_out_is_a_paid_call_not_a_free_one() {
        assertEquals(ReachKind.VOICE, MessengerMimes.kind("${item}vnd.com.viber.voip.viber_number_call"))
        assertEquals(ReachKind.MESSAGE, MessengerMimes.kind("${item}vnd.com.viber.voip.viber_number_message"))
        assertEquals(ReachKind.PAID_CALL, MessengerMimes.kind("${item}vnd.com.viber.voip.viber_out_call_viber"))
        assertEquals(ReachKind.PAID_CALL, MessengerMimes.kind("${item}vnd.com.viber.voip.viber_out_call_none"))
    }

    @Test fun threema_flavours_and_meet() {
        val work = row("${item}vnd.ch.threema.app.work.call", "ch.threema.app.work")!!
        assertEquals(ReachApp.THREEMA, work.app)
        assertEquals(ReachKind.VOICE, work.kind)
        assertEquals(ReachKind.MESSAGE, row("${item}vnd.ch.threema.app.profile", "ch.threema.app")!!.kind)
        assertEquals(ReachKind.VIDEO, row("${item}com.google.android.apps.tachyon.phone", "com.google.android.apps.tachyon")!!.kind)
        assertEquals(ReachKind.VOICE, row("${item}com.google.android.apps.tachyon.phone.audio", "com.google.android.apps.tachyon")!!.kind)
    }

    @Test fun unknown_apps_count_only_when_the_mimetype_is_their_own() {
        val r = row("${item}vnd.com.example.chat.videocall", "com.example.chat", d2 = "Example")!!
        assertNull(r.app)
        assertEquals("com.example.chat", r.appKey)
        assertEquals("Example", r.appLabel)
        assertEquals(ReachKind.VIDEO, r.kind)
        // Another account's row, a Google row and the platform's own kinds are not messenger rows.
        assertNull(row("${item}vnd.com.other.app.call", "com.example.chat"))
        assertNull(row("${item}vnd.com.google.something.call", "com.google"))
        assertNull(row("${item}phone_v2", "org.thoughtcrime.securesms"))
        assertNull(row("${item}name", "com.whatsapp"))
        assertNull(row("vnd.com.google.cursor.item/contact_misc", "com.google"))
        assertFalse(MessengerMimes.isMessengerRow("${item}vnd.com.foo.call", null))
    }

    @Test fun known_apps_are_read_only_messenger_accounts() {
        for (t in listOf("im.molly.app", "org.thunderdog.challegram", "ch.threema.app.work", "com.google.android.apps.tachyon", "im.thebot.messenger", "com.imo.android.imoim")) {
            assertTrue(t, Messengers.isMessengerAccount(t))
        }
        assertTrue(Messengers.isMessengerAccount("org.thoughtcrime.securesms"))
        assertFalse(Messengers.isMessengerAccount("com.google"))
    }

    @Test fun plus_number_in_a_prompt() {
        assertEquals("+15551234567", MessengerMimes.plusNumber("Message +1 (555) 123-4567"))
        assertNull(MessengerMimes.plusNumber("Message 5551234567"))
        assertNull(MessengerMimes.plusNumber("+12"))
    }

    // ---- Grouping per app and number ----

    @Test fun groups_join_an_apps_rows_per_number() {
        val t = "org.thoughtcrime.securesms"
        val rows = listOfNotNull(
            row("${item}vnd.com.whatsapp.profile", "com.whatsapp", d1 = "15551234567@s.whatsapp.net", id = 1),
            row("${item}vnd.org.thoughtcrime.securesms.contact", t, "+1 555 123 4567", id = 2),
            row("${item}vnd.org.thoughtcrime.securesms.call", t, "+15551234567", id = 3),
            row("${item}vnd.org.thoughtcrime.securesms.videocall", t, "+15551234567", id = 4),
            row("${item}vnd.org.thoughtcrime.securesms.contact", t, "+15559876543", id = 5),
            row("${item}vnd.com.viber.voip.viber_out_call_viber", "com.viber.voip", "+15551234567", id = 6),
        )
        val groups = ReachGroups.group(rows)
        assertEquals(listOf(ReachApp.WHATSAPP, ReachApp.SIGNAL, ReachApp.SIGNAL), groups.map { it.app })
        val first = groups[1]
        assertEquals(2L, first.message?.dataId)
        assertEquals(3L, first.voice?.dataId)
        assertEquals(4L, first.video?.dataId)
        assertTrue(first.canCall)
        val second = groups[2]
        assertEquals(5L, second.message?.dataId)
        assertFalse(second.canCall)
        // Viber Out never shows as a way to talk for free.
        assertTrue(groups.none { it.app == ReachApp.VIBER })
        val same = { a: String, b: String -> PhoneNumbers.matchKey(a) == PhoneNumbers.matchKey(b) }
        assertEquals(2, ReachGroups.forNumber(groups, "+15551234567", same).size)
        assertEquals(1, ReachGroups.forNumber(groups, "+15559876543", same).size)
    }

    @Test fun rows_without_a_number_join_the_apps_numbered_rows() {
        val rows = listOfNotNull(
            row("${item}vnd.org.telegram.messenger.android.profile", "org.telegram.messenger", d3 = "Message +44 7700 900123", id = 1),
            row("${item}vnd.org.telegram.messenger.android.call", "org.telegram.messenger", d3 = "Voice call", id = 2),
        )
        val g = ReachGroups.group(rows).single()
        assertEquals("+447700900123", g.number)
        assertEquals(2L, g.voice?.dataId)
    }

    // ---- Call routes ----

    @Test fun calls_use_the_apps_row_or_say_they_go_through_the_chat() {
        val rows = listOfNotNull(
            row("${item}vnd.org.thoughtcrime.securesms.call", "org.thoughtcrime.securesms", id = 3),
            row("${item}vnd.com.whatsapp.w4b.video.call", "com.whatsapp.w4b", id = 7),
        )
        val signal = CallRoutes.forApp(MessengerApp.of(app.parley.common.MessengerCatalog.SIGNAL), video = false, rows)
        assertTrue(signal is CallRoute.Row)
        assertEquals(3L, (signal as CallRoute.Row).row.dataId)
        // No video row from Signal: the chat, never a pretend call link.
        assertEquals(CallRoute.ViaChat(MessengerApp.of(app.parley.common.MessengerCatalog.SIGNAL)), CallRoutes.forApp(MessengerApp.of(app.parley.common.MessengerCatalog.SIGNAL), video = true, rows))
        // Business's row doesn't start a call in WhatsApp.
        assertEquals(CallRoute.ViaChat(MessengerApp.of(app.parley.common.MessengerCatalog.WHATSAPP)), CallRoutes.forApp(MessengerApp.of(app.parley.common.MessengerCatalog.WHATSAPP), video = true, rows))
        assertTrue(CallRoutes.forApp(MessengerApp.of(app.parley.common.MessengerCatalog.WHATSAPP_BUSINESS), video = true, rows) is CallRoute.Row)
        // Molly doesn't take Signal's row.
        assertEquals(CallRoute.ViaChat(MessengerApp.of(app.parley.common.MessengerCatalog.MOLLY)), CallRoutes.forApp(MessengerApp.of(app.parley.common.MessengerCatalog.MOLLY), video = false, rows))
        assertNotNull(ReachApp.forMessengerApp(MessengerApp.forPackage("org.telegram.messenger.web")!!))
    }

    // ---- messenger-only contacts match exact numbers only ----

    @Test fun messenger_only_rows_need_an_exact_number() {
        // No SIM country: national numbers don't parse, and the last nine digits are the same.
        val mine = listOf("0300 1234567")
        assertTrue(PhoneNumbers.same("0300 1234567", "0400 1234567", ""))
        assertFalse(MessengerRowMatch.extraRow("0400 1234567", mine, ""))
        assertTrue(MessengerRowMatch.extraRow("03001234567", mine, ""))
        assertFalse(MessengerRowMatch.extraRow(null, mine, ""))
        assertTrue(MessengerRowMatch.extraRow("+923001234567", listOf("0300 1234567"), "PK"))
    }

    @Test fun rows_for_a_number_without_a_number_need_the_contact_to_have_it() {
        assertTrue(MessengerRowMatch.forNumber(null, listOf("0300 1234567"), "03001234567", ""))
        assertFalse(MessengerRowMatch.forNumber(null, listOf("0400 1234567"), "03001234567", ""))
        assertFalse(MessengerRowMatch.forNumber("0400 1234567", listOf("0300 1234567"), "03001234567", ""))
        assertTrue(MessengerRowMatch.forNumber("+923001234567", emptyList(), "0300 1234567", "PK"))
    }
}
