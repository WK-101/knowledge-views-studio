package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilySafetyTest {
    private val unknown = SafeWords.Facts(
        hasSafeWord = true, unknownCaller = true, emergency = false, connected = true, connectedSeconds = 21, claimedFamily = false, dismissed = false,
    )

    @Test fun the_card_shows_for_an_unknown_caller_after_20_seconds() {
        assertTrue(SafeWords.cardShows(unknown))
        assertFalse(SafeWords.cardShows(unknown.copy(connectedSeconds = 20)))
        // Not while it rings or after it ends.
        assertFalse(SafeWords.cardShows(unknown.copy(connected = false)))
    }

    @Test fun says_theyre_family_shows_it_at_once_for_anyone() {
        val saved = unknown.copy(unknownCaller = false, connectedSeconds = 2)
        assertFalse(SafeWords.cardShows(saved))
        assertTrue(SafeWords.claimOffered(saved))
        assertTrue(SafeWords.cardShows(saved.copy(claimedFamily = true)))
        assertFalse(SafeWords.claimOffered(saved.copy(claimedFamily = true)))
    }

    @Test fun never_for_emergency_calls_once_closed_or_without_a_safe_word() {
        assertFalse(SafeWords.cardShows(unknown.copy(emergency = true)))
        assertFalse(SafeWords.claimOffered(unknown.copy(emergency = true, connectedSeconds = 1)))
        assertFalse(SafeWords.cardShows(unknown.copy(dismissed = true)))
        assertFalse(SafeWords.cardShows(unknown.copy(hasSafeWord = false)))
        assertFalse(SafeWords.claimOffered(unknown.copy(hasSafeWord = false)))
    }

    @Test fun never_offered_for_saved_members_of_that_label() {
        val words = mapOf("Family" to SafeWord("What's our word?", "heron"), "Neighbours" to SafeWord("Q", "A"))
        assertEquals(listOf("Neighbours"), SafeWords.offeredFor(words, setOf(" Family ")))
        assertEquals(listOf("Family", "Neighbours"), SafeWords.offeredFor(words, emptySet()))
        assertEquals(emptyList<String>(), SafeWords.offeredFor(words, setOf("Family", "Neighbours")))
    }

    @Test fun safe_words_are_cleaned_and_follow_renames_and_deletes() {
        assertEquals(SafeWord("What's our word?", "blue heron"), SafeWords.clean("  What's our word?\n", "blue\nheron "))
        assertNull(SafeWords.clean("Question", "  "))
        assertEquals(SafeWords.MAX_ANSWER, SafeWords.clean("Q", "x".repeat(500))?.answer?.length)
        val words = mapOf("Family" to SafeWord("Q1", "A1"), "Kin" to SafeWord("Q2", "A2"))
        // A merge into an existing label keeps that label's own word.
        assertEquals(mapOf("Kin" to SafeWord("Q2", "A2")), SafeWords.renamed(words, mapOf("Family" to "Kin")))
        assertEquals(setOf("Relatives", "Kin"), SafeWords.renamed(words, mapOf("Family" to "Relatives")).keys)
        assertEquals(setOf("Kin"), SafeWords.deleted(words, setOf("Family")).keys)
    }

    @Test fun at_most_three_helpers_each_line_once() {
        var list = emptyList<Helper>()
        list = Helpers.add(list, Helper("Sam", "+44 7700 900001"), "GB")
        list = Helpers.add(list, Helper("Sam again", "07700 900001"), "GB")
        list = Helpers.add(list, Helper("Ana", "07700 900002", private = true), "GB")
        list = Helpers.add(list, Helper("Bo", "07700 900003"), "GB")
        list = Helpers.add(list, Helper("Cy", "07700 900004"), "GB")
        assertEquals(listOf("Sam", "Ana", "Bo"), list.map { it.name })
        assertEquals(listOf("Ana", "Bo"), Helpers.forCall(list, "+447700900001", "GB").map { it.name })
    }

    @Test fun add_my_helper_is_offered_only_on_one_connected_ordinary_call() {
        assertTrue(Helpers.offered(helpers = 1, connected = true, emergency = false, otherCalls = 0, joining = false))
        assertFalse(Helpers.offered(helpers = 0, connected = true, emergency = false, otherCalls = 0, joining = false))
        assertFalse(Helpers.offered(helpers = 1, connected = false, emergency = false, otherCalls = 0, joining = false))
        assertFalse(Helpers.offered(helpers = 1, connected = true, emergency = true, otherCalls = 0, joining = false))
        assertFalse(Helpers.offered(helpers = 1, connected = true, emergency = false, otherCalls = 1, joining = false))
        assertFalse(Helpers.offered(helpers = 1, connected = true, emergency = false, otherCalls = 0, joining = true))
    }

    @Test fun helper_stages() {
        assertEquals(HelperStage.CALLING, HelperJoin.stage(null, merged = false, seen = false))
        assertEquals(HelperStage.CALLING, HelperJoin.stage(LiveCallState.DIALING, merged = false, seen = true))
        assertEquals(HelperStage.ANSWERED, HelperJoin.stage(LiveCallState.ACTIVE, merged = false, seen = true))
        assertEquals(HelperStage.JOINED, HelperJoin.stage(null, merged = true, seen = true))
        assertEquals(HelperStage.GONE, HelperJoin.stage(null, merged = false, seen = true))
    }

    @Test fun the_state_round_trips_and_sources_start_undecided() {
        val s = FamilySafetyState(
            safeWords = mapOf("Family" to SafeWord("Q", "A")),
            helpers = listOf(Helper("Sam", "+447700900001")),
            consents = mapOf(ExpectedSource.NOTE to true, ExpectedSource.DELIVERY_QR to false),
            windows = listOf(ExpectedWindow(1, 2, ExpectedSource.NOTE, "k", "Dentist")),
        )
        assertEquals(s, FamilySafetyState.decode(FamilySafetyState.encode(s)))
        assertEquals(FamilySafetyState(), FamilySafetyState.decode(null))
        assertTrue(s.accepted(ExpectedSource.NOTE))
        assertFalse(s.accepted(ExpectedSource.DELIVERY_QR))
        assertFalse(s.undecided(ExpectedSource.DELIVERY_QR))
        assertTrue(s.undecided(ExpectedSource.TO_CALL))
    }
}
