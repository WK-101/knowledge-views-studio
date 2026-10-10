package app.parley.common.calls

import app.parley.common.calls.PostCallActions.Action
import app.parley.common.calls.PostCallActions.Facts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostCallActionsTest {
    @Test fun save_remind_and_block_come_first_and_the_rest_waits_under_more() {
        val l = PostCallActions.layout(Facts(nameReply = true))
        assertEquals(listOf(Action.SAVE, Action.REMIND_ME, Action.BLOCK), l.primary)
        assertEquals(
            listOf(Action.MESSAGE_OR_CALL_ON, Action.ASK_NAME, Action.REPORT, Action.SCAM_CHECK, Action.CALL_SAVED_NUMBER),
            l.more,
        )
    }

    @Test fun a_suspicious_call_brings_the_scam_check_and_a_saved_number_forward() {
        val l = PostCallActions.layout(Facts(suspicious = true))
        assertEquals(listOf(Action.SAVE, Action.REMIND_ME, Action.BLOCK, Action.SCAM_CHECK, Action.CALL_SAVED_NUMBER), l.primary)
        assertEquals(listOf(Action.MESSAGE_OR_CALL_ON, Action.REPORT), l.more)
    }

    @Test fun a_blocked_number_offers_unblock_and_an_emergency_service_neither_nor_report() {
        assertTrue(Action.UNBLOCK in PostCallActions.layout(Facts(blocked = true)).primary)
        val sos = PostCallActions.layout(Facts(blocked = true, emergency = true))
        val all = sos.primary + sos.more
        assertTrue(all.none { it == Action.BLOCK || it == Action.UNBLOCK || it == Action.REPORT })
    }

    @Test fun every_action_shows_once_and_the_card_keeps_to_five_big_buttons() {
        for (bits in 0 until 16) {
            val f = Facts(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0)
            val l = PostCallActions.layout(f)
            val all = l.primary + l.more
            assertEquals("$f", all.size, all.toSet().size)
            assertTrue("$f", l.primary.size <= 5)
            assertEquals(Action.SAVE, l.primary.first())
            assertEquals(f.nameReply, Action.ASK_NAME in all)
        }
    }
}
