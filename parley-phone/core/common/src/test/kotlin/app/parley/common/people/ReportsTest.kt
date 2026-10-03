package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    @Test fun crash_reports_and_raw_dumps_are_masked() {
        val c = Reports.Crash(0, "main", "java.lang.IllegalStateException: bad number +44 7700 900123 for anna@x.org", "3.1", "15")
        val text = Reports.crashText(c)
        assertFalse(text.contains("7700 900123"))
        assertFalse(text.contains("anna@"))
        assertTrue(text.contains("IllegalStateException"))
        // The message itself is gone either way; masking covers whatever else is in the trace.
        assertFalse(Reports.crashText(c, mask = false).contains("+44 7700 900123"))
        assertEquals("Aaaa +99 9900", Reports.shape("Anna +44 7700"))
        assertEquals("null", Reports.shape(null))
        assertTrue(Reports.shape("x".repeat(100), 10).startsWith("aaaaaaaaaa…"))
        assertEquals(3, Reports.trimStack((1..10).joinToString("\n"), 2).lines().size)
    }

    @Test fun contacts_copy_as_plain_text() {
        val t = Reports.contactsAsText(
            listOf(
                Reports.TextContact("Anna", listOf("+44 7700" to "Mobile", "123" to null), listOf("a@x.org")),
                Reports.TextContact("Ben", emptyList(), emptyList()),
            ),
        )
        assertEquals("Anna\nMobile: +44 7700\n123\na@x.org\n\nBen", t)
    }

    @Test fun stored_crashes_keep_class_names_and_frames_but_no_messages() {
        val cause = IllegalArgumentException("no contact for Anna Smith +44 7700 900123")
        val error = IllegalStateException("bad number +44 7700 900123", cause)
        error.addSuppressed(java.io.IOException("could not write /data/anna.vcf"))
        val stack = Reports.scrubbedStack(error)
        assertFalse(stack, stack.contains("Anna"))
        assertFalse(stack, stack.contains("7700"))
        assertFalse(stack, stack.contains("anna.vcf"))
        assertTrue(stack, stack.startsWith("java.lang.IllegalStateException\n\tat "))
        assertTrue(stack, stack.contains("Caused by: java.lang.IllegalArgumentException\n"))
        assertTrue(stack, stack.contains("\tSuppressed: java.io.IOException\n"))
        assertTrue(stack, stack.contains("ReportsTest.stored_crashes_keep_class_names_and_frames_but_no_messages(ReportsTest.kt:"))
        assertTrue(stack, Regex("""\t\.\.\. \d+ more""").containsMatchIn(stack))
    }

    @Test fun a_cause_loop_ends() {
        val a = RuntimeException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)
        assertTrue(Reports.scrubbedStack(b).contains("[CIRCULAR REFERENCE: java.lang.IllegalStateException]"))
    }

    @Test fun reports_stored_before_lose_their_messages_when_shared() {
        val old = listOf(
            "java.lang.IllegalStateException: bad number +44 7700 900123",
            "and a second line of it for Anna",
            "\tat app.parley.data.Foo.bar(Foo.kt:12)",
            "\tSuppressed: java.io.IOException: /data/anna.vcf",
            "\t\tat app.parley.data.Foo.baz(Foo.kt:20)",
            "Caused by: app.parley.data.Store${'$'}Broken: Anna Smith",
            "\t... 3 more",
            "… 40 more lines",
        ).joinToString("\n")
        assertEquals(
            listOf(
                "java.lang.IllegalStateException",
                "\tat app.parley.data.Foo.bar(Foo.kt:12)",
                "\tSuppressed: java.io.IOException",
                "\t\tat app.parley.data.Foo.baz(Foo.kt:20)",
                "Caused by: app.parley.data.Store${'$'}Broken",
                "\t... 3 more",
                "… 40 more lines",
            ).joinToString("\n"),
            Reports.withoutMessages(old),
        )
        val shared = Reports.crashText(Reports.Crash(0, "main", old, "5.0.1", "16"))
        assertFalse(shared, shared.contains("Anna"))
    }
}
