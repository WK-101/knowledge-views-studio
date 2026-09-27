package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "Message or call on…" sheet lists the same rows, in the same order, for a person and for a number. */
class ReachPlanTest {
    private val item = MessengerMimes.ITEM
    private val wa = MessengerApp.of(MessengerCatalog.WHATSAPP)
    private val signal = MessengerApp.of(MessengerCatalog.SIGNAL)
    private val telegramWeb = MessengerApp.forPackage("org.telegram.messenger.web")!!
    private val telegram = MessengerApp.of(MessengerCatalog.TELEGRAM)

    private fun row(id: Long, mime: String, type: String, number: String = "+15551234567") =
        MessengerMimes.classify(id, item + mime, type, number, null, null, number)!!

    @Test fun one_chat_app_per_entry_last_used_first() {
        assertEquals(listOf(telegram, wa, signal), ReachPlan.chatApps(listOf(signal, telegramWeb, wa, telegram), "org.telegram.messenger"))
        assertEquals(listOf(wa, signal), ReachPlan.chatApps(listOf(signal, wa), null))
    }

    @Test fun chat_apps_use_their_own_call_rows_else_the_chat_then_other_apps() {
        val rows = listOf(
            row(1, "vnd.org.thoughtcrime.securesms.call", "org.thoughtcrime.securesms"),
            row(2, "vnd.org.thoughtcrime.securesms.videocall", "org.thoughtcrime.securesms"),
            row(3, "com.google.android.apps.tachyon.phone", "com.google.android.apps.tachyon"),
            row(4, "vnd.com.whatsapp.profile", "com.whatsapp"),
        )
        val groups = ReachGroups.group(rows)
        val plan = ReachPlan.callOn(listOf(wa, signal), groups)
        assertEquals(3, plan.size)
        // WhatsApp only added a chat row: its chat opens, never a pretend call.
        assertEquals(CallOnEntry.ViaChat(wa), plan[0])
        val s = plan[1] as CallOnEntry.Direct
        assertEquals(MessengerCatalog.SIGNAL, s.group.app)
        assertEquals(1L, s.group.voice!!.dataId)
        assertEquals(2L, s.group.video!!.dataId)
        assertEquals(MessengerCatalog.MEET, (plan[2] as CallOnEntry.Direct).group.app)
    }

    @Test fun nothing_installed_and_no_rows_means_no_rows() {
        assertEquals(emptyList<CallOnEntry>(), ReachPlan.callOn(emptyList(), emptyList()))
    }

    @Test fun linked_key_is_the_account_type_of_a_chat_row() {
        assertEquals("com.whatsapp", ReachPlan.linkedKey(wa, setOf("com.whatsapp")))
        // A flavour of Telegram opens the main app's linked row.
        assertEquals("org.telegram.messenger", ReachPlan.linkedKey(telegramWeb, setOf("org.telegram.messenger")))
        assertNull(ReachPlan.linkedKey(signal, setOf("com.whatsapp")))
    }
}
