package app.parley.telecom.ui

import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import app.parley.common.Verification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The call screen keeps out of recents' thumbnails while a private contact is on the line, and only then. */
class CallWindowShieldTest {
    private fun call(id: String, number: String?, account: String? = "sim1", children: List<CallUi> = emptyList()) = CallUi(
        id = id, state = CallState.ACTIVE, number = number, hidden = number == null, name = null, label = null,
        photoUri = null, contactId = null, incoming = true, connectTimeMillis = 0, isConference = children.isNotEmpty(), children = children,
        canHold = false, canMerge = false, canSwap = false, canMute = true, canSeparate = false, canDisconnectChild = false,
        canRespondViaText = false, accountLabel = null, verification = Verification.NOT_VERIFIED, disconnectReason = null,
        postDialWait = null, silenced = false, isEmergency = false, accountId = account,
    )

    @Test fun every_line_on_screen_is_asked_about_once() {
        val conference = call("conf", null, children = listOf(call("a", "+447700900001"), call("b", "+447700900002")))
        val lines = callLines(listOf(conference, call("w", "+447700900001"), call("h", null)), ended = call("e", "+447700900003", account = null))
        assertEquals(
            listOf("+447700900001" to "sim1", "+447700900002" to "sim1", "+447700900003" to null),
            lines,
        )
    }

    @Test fun a_private_line_blanks_the_thumbnail_and_normal_calls_do_not() = runTest {
        val private = setOf("+447700900002")
        val lines = listOf("+447700900001" to "sim1", "+447700900002" to "sim1")
        assertTrue(anyPrivateLine(lines) { n, _ -> n in private })
        assertFalse(anyPrivateLine(lines.take(1)) { n, _ -> n in private })
        assertFalse(anyPrivateLine(emptyList()) { _, _ -> true })
        // A check that fails counts as private (fails closed).
        assertTrue(anyPrivateLine(lines.take(1)) { _, _ -> error("vault unavailable") })
    }
}
