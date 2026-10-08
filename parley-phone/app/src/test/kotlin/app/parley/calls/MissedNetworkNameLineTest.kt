package app.parley.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** A grouped missed-call summary says when a caller's name is the network's, as the caller's own notification does. */
class MissedNetworkNameLineTest {
    private val sep = " · "
    private val tag = "From the network"

    @Test fun the_inbox_line_carries_the_tag() {
        assertEquals("Ravi Kumar · From the network · 10:42", MissedCallNotifier.inboxLine("Ravi Kumar", tag, "10:42", null, sep))
        assertEquals("Ravi Kumar (2) · From the network · 10:42 · Work", MissedCallNotifier.inboxLine("Ravi Kumar (2)", tag, "10:42", "Work", sep))
        // A saved name or a number: as before.
        assertEquals("Ana · 10:42", MissedCallNotifier.inboxLine("Ana", null, "10:42", null, sep))
    }

    @Test fun the_collapsed_summary_carries_it_too() {
        assertEquals("Ravi Kumar · From the network", MissedCallNotifier.taggedTitle("Ravi Kumar", tag, sep))
        assertEquals("Ana", MissedCallNotifier.taggedTitle("Ana", null, sep))
    }
}
