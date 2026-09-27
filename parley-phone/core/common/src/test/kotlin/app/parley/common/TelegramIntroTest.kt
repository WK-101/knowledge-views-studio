package app.parley.common

import app.parley.common.messaging.IntroQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramIntroTest {
    // ---- Telegram profile ----

    @Test fun telegram_profile_link() {
        val l = MessengerLinks.telegramProfile(MessengerApp.of(MessengerCatalog.TELEGRAM), "+923001234567")
        assertNotNull(l)
        assertEquals("tg://resolve?phone=923001234567&profile", l!!.uri)
        assertEquals("org.telegram.messenger", l.packageName)
        assertEquals("org.thunderdog.challegram", MessengerLinks.telegramProfile(MessengerApp.of(MessengerCatalog.TELEGRAM_X), "+923001234567")!!.packageName)
        assertNull(MessengerLinks.telegramProfile(MessengerApp.of(MessengerCatalog.WHATSAPP), "+923001234567"))
        assertNull(MessengerLinks.telegramProfile(MessengerApp.of(MessengerCatalog.TELEGRAM), "03001234567"))
    }

    // ---- Introduction queue ----

    @Test fun intro_queue_advances_only_after_a_chat_was_opened() {
        val q0 = IntroQueue((1..3).map { IntroQueue.Target("P$it", "+1555000000$it") })
        assertEquals("1 of 3", q0.progress)
        // Coming back without having opened anything doesn't move on.
        assertEquals(0, q0.returned().index)
        val q1 = q0.markOpened().returned()
        assertEquals("2 of 3", q1.progress)
        val q2 = q1.skip()
        assertEquals("P3", q2.current!!.name)
        val q3 = q2.markOpened().returned()
        assertTrue(q3.finished)
        assertEquals("Done", q3.progress)
        assertEquals("Opened 2 chats · skipped 1", q3.summary())
        assertEquals(q3, q3.next())
    }

    @Test fun intro_queue_stop_counts_what_was_not_reached() {
        val q = IntroQueue((1..5).map { IntroQueue.Target("", "+1555000000$it") }).markOpened().returned().stop()
        assertTrue(q.finished)
        assertNull(q.current)
        assertEquals("Opened 1 chat · 4 not reached", q.summary())
    }
}
