package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Parley stopped unexpectedly" report: only crashes and ANRs since the last run, and no personal data. */
class ExitReportTest {
    @Test fun only_a_crash_or_anr_after_the_last_run_counts() {
        val exits = listOf(100L to 4, 200L to 10, 300L to 6, 50L to 5)
        assertEquals(ExitReport.Exit(300L, ExitReport.Kind.ANR), ExitReport.newest(exits, since = 0))
        assertNull(ExitReport.newest(exits, since = 300))
        // A user's own stop (10) or one already offered isn't a crash.
        assertNull(ExitReport.newest(listOf(400L to 10, 500L to 1), since = 0))
        assertEquals(ExitReport.Kind.NATIVE_CRASH, ExitReport.newest(listOf(60L to 5), since = 0)!!.kind)
    }

    private val trace = """
        ----- pid 4242 at 2026-10-01 10:00:00 -----
        Cmd line: app.parley
        "Signal Catcher" daemon prio=10 tid=2 Runnable
          at dalvik.system.VMStack.getThreadStackTrace(Native method)

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72c0 self=0xb400
          | sysTid=4242 nice=-10 cgrp=top-app
          at app.parley.data.ContactsRepository.load(ContactsRepository.kt:120)
          - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23
          native: #00 pc 000000000004e2a8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
          at app.parley.ui.HomeScreen.show(HomeScreen.kt:44)

        "binder:4242_1" prio=5 tid=3 Native
          at app.parley.Other.run(Other.kt:1)
    """.trimIndent()

    @Test fun an_anr_keeps_the_main_threads_frames_only() {
        val s = ExitReport.anrStack(trace.lineSequence())
        val lines = s.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("ContactsRepository.load"))
        assertTrue(lines[1].trim().startsWith("native: "))
        assertFalse(s.contains("waiting to lock"))
        assertFalse(s.contains("Other.run"))
        assertFalse(s.contains("sysTid"))
    }

    @Test fun the_report_holds_stack_versions_and_device_only() {
        val stack = "java.lang.IllegalStateException: Ana +44 7700 900123 not found\n\tat app.parley.X.y(X.kt:3)"
        val text = ExitReport.text(ExitReport.Exit(1L, ExitReport.Kind.CRASH), stack, "6.3.0", "17 (API 37)", "Google Pixel 9", "1 Oct 2026")
        assertTrue(text.contains("App: 6.3.0") && text.contains("Android: 17 (API 37)") && text.contains("Device: Google Pixel 9"))
        assertTrue(text.contains("at app.parley.X.y(X.kt:3)"))
        // The exception's message (a name and a number here) never gets in.
        assertFalse(text.contains("Ana"))
        assertFalse(text.contains("900123"))
        val none = ExitReport.text(ExitReport.Exit(1L, ExitReport.Kind.NATIVE_CRASH), null, "6.3.0", "17", "Pixel", "1 Oct 2026")
        assertTrue(none.contains("No stack"))
    }
}
