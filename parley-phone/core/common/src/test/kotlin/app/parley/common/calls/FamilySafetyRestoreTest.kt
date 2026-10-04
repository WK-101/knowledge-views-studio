package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Test

/** A restore puts a backup's family safety beside this phone's, which wins wherever both have something. */
class FamilySafetyRestoreTest {
    private val now = 1_000_000L
    private fun window(source: ExpectedSource, key: String, end: Long = now + 1000) = ExpectedWindow(now - 10, end, source, key)

    @Test fun this_phones_choices_win_and_the_rest_comes_back() {
        val here = FamilySafetyState(
            safeWords = mapOf("Family" to SafeWord("Pet?", "Rex")),
            helpers = listOf(Helper("Sam", "+44 7700 900001")),
            consents = mapOf(ExpectedSource.NOTE to false),
            windows = listOf(window(ExpectedSource.TO_CALL, "a")),
        )
        val backup = FamilySafetyState(
            safeWords = mapOf(" Family" to SafeWord("Street?", "Elm"), "Work" to SafeWord("Floor?", "3")),
            helpers = listOf(Helper("Sam again", "07700 900001"), Helper("Jo", "+44 7700 900002", private = true)),
            consents = mapOf(ExpectedSource.NOTE to true, ExpectedSource.DELIVERY_QR to true),
            windows = listOf(
                window(ExpectedSource.TO_CALL, "a", now + 5000), window(ExpectedSource.DELIVERY_QR, "q"), window(ExpectedSource.TO_CALL, "old", now - 1),
            ),
        )
        val r = here.restoredFrom(backup, now, "GB")
        assertEquals(mapOf("Family" to SafeWord("Pet?", "Rex"), "Work" to SafeWord("Floor?", "3")), r.safeWords)
        assertEquals(listOf("Sam", "Jo"), r.helpers.map { it.name })
        assertEquals(true, r.helpers.last().private)
        assertEquals(mapOf(ExpectedSource.NOTE to false, ExpectedSource.DELIVERY_QR to true), r.consents)
        assertEquals(listOf("a" to now + 1000, "q" to now + 1000), r.windows.map { it.key to it.end }.sortedBy { it.first })
    }

    @Test fun helpers_stay_within_three() {
        val here = FamilySafetyState(helpers = (1..2).map { Helper("H$it", "+44 7700 90000$it") })
        val backup = FamilySafetyState(helpers = (3..6).map { Helper("H$it", "+44 7700 90000$it") })
        assertEquals(Helpers.MAX, here.restoredFrom(backup, now, "GB").helpers.size)
    }

    @Test fun nothing_restored_into_nothing_is_nothing() {
        assertEquals(FamilySafetyState(), FamilySafetyState().restoredFrom(FamilySafetyState(), now, null))
    }
}
