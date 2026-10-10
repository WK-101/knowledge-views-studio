package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One reader per call, whatever Telecom does with its RttCall objects. */
class RttReaderTest {
    private class Stream(val name: String) {
        override fun toString() = name
    }

    @Test fun one_reader_for_the_first_stream_only() {
        val r = RttReader<Stream>()
        val a = Stream("a")
        assertTrue(r.follow(a))
        // The same stream again, and a new RttCall over the same pipe: no second thread.
        assertFalse(r.follow(a))
        val b = Stream("b")
        assertFalse(r.follow(b))
        assertTrue(r.accepts())
    }

    @Test fun the_reader_switches_to_the_newer_stream_after_its_read() {
        val r = RttReader<Stream>()
        val a = Stream("a")
        val b = Stream("b")
        r.follow(a)
        assertSame(a, r.next(a, closed = false))
        r.follow(b)
        // The text that read returned (from the old object, same pipe) is kept; the next read is from the new one.
        assertTrue(r.accepts())
        assertSame(b, r.next(a, closed = false))
        // The old object closing after the switch doesn't end the reader either.
        assertSame(b, r.next(a, closed = true))
        assertSame(b, r.current())
    }

    @Test fun rtt_off_ends_the_reader_and_a_new_stream_starts_one() {
        val r = RttReader<Stream>()
        val a = Stream("a")
        r.follow(a)
        r.follow(null)
        assertFalse(r.accepts())
        assertNull(r.next(a, closed = false))
        // Turned on again: one new reader.
        val b = Stream("b")
        assertTrue(r.follow(b))
        assertFalse(r.follow(b))
    }

    @Test fun a_closed_stream_with_nothing_newer_ends_the_reader() {
        val r = RttReader<Stream>()
        val a = Stream("a")
        r.follow(a)
        assertNull(r.next(a, closed = true))
        // Turned on again while the old thread was blocked: the old thread picks up the new stream, no second thread.
        val r2 = RttReader<Stream>()
        r2.follow(a)
        r2.follow(null)
        val b = Stream("b")
        assertFalse("the old thread is still running", r2.follow(b))
        assertSame(b, r2.next(a, closed = true))
        assertEquals(b, r2.current())
    }
}
