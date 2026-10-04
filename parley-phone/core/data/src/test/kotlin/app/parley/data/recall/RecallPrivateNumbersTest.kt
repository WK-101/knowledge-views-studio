package app.parley.data.recall

import app.parley.common.PhoneIdentity
import app.parley.common.recall.RecallCorpus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * While private contacts may not be shown, Recall's deleted contacts, snapshots and chats leave out anything with a
 * private contact's number, as number memory does: a copy made before the number became private, or kept under
 * another key, never names them.
 */
class RecallPrivateNumbersTest {
    private val privateLines = PhoneIdentity.LineSet(listOf("+44 20 7946 0001"), "GB")
    private val hidden: (String) -> Boolean = { it in privateLines }

    private val stored = RecallSources.Stored(
        deleted = listOf(
            RecallCorpus.Gone("Dana (deleted, later made private)", listOf("020 7946 0001"), 1, "1"),
            RecallCorpus.Gone("Old plumber", listOf("020 7946 0002"), 2, "2"),
            // A deleted private contact: shown only when private contacts may be, which the access decides.
            RecallCorpus.Gone("Private, deleted", listOf("020 7946 0001"), 3, "f", private = true),
        ),
        snapshots = listOf(
            RecallCorpus.Gone("Dana", listOf("07700 900123", "+442079460001"), 4, "4"),
            RecallCorpus.Gone("Ana", listOf("07700 900124"), 5, "5"),
        ),
        messaged = listOf(RecallCorpus.Messaged("+44 20 7946 0001", "WhatsApp", 6), RecallCorpus.Messaged("07700 900125", "Signal", 7)),
    )

    @Test fun privateNumbersStayOut() {
        val out = RecallSources.withoutPrivate(stored, hidden)
        assertEquals(listOf("Old plumber", "Private, deleted"), out.deleted.map { it.name })
        assertEquals(listOf("Ana"), out.snapshots.map { it.name })
        assertEquals(listOf("07700 900125"), out.messaged.map { it.number })
    }

    @Test fun shownWhenPrivateContactsMayBe() {
        assertEquals(stored, RecallSources.withoutPrivate(stored, { false }))
    }
}
