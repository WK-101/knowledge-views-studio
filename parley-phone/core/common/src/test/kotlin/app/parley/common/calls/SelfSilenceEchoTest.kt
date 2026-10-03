package app.parley.common.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfSilenceEchoTest {
    @Test fun parleys_own_silence_comes_back_once_and_is_ignored() {
        val echo = SelfSilenceEcho()
        echo.noted(10_000)
        assertTrue("the echo a few ms later", echo.consumed(10_004))
        // The user pressing the volume key right after is theirs: one request echoes once.
        assertFalse(echo.consumed(10_300))
    }

    @Test fun a_user_silence_with_no_request_of_parleys_is_honoured() {
        assertFalse(SelfSilenceEcho().consumed(5_000))
    }

    @Test fun after_the_window_a_silence_is_the_users() {
        val echo = SelfSilenceEcho()
        echo.noted(10_000)
        assertFalse(echo.consumed(10_000 + SelfSilenceEcho.WINDOW_MS + 1))
        // And the stale request is gone for good.
        assertFalse(echo.consumed(10_000 + SelfSilenceEcho.WINDOW_MS + 2))
    }

    @Test fun two_requests_echo_twice() {
        val echo = SelfSilenceEcho()
        echo.noted(1_000)
        echo.noted(1_050)
        assertTrue(echo.consumed(1_010))
        assertTrue(echo.consumed(1_060))
        assertFalse(echo.consumed(1_070))
    }

    @Test fun a_clock_that_went_backwards_never_swallows_a_silence() {
        val echo = SelfSilenceEcho()
        echo.noted(50_000)
        assertFalse(echo.consumed(40_000))
    }

    @Test fun forgotten_requests_never_swallow_a_silence() {
        val echo = SelfSilenceEcho()
        echo.noted(1_000)
        echo.noted(1_001)
        echo.forget()
        assertFalse(echo.consumed(1_002))
    }
}
