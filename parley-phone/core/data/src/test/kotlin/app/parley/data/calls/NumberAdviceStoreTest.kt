package app.parley.data.calls

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Answers to number advice are kept per line key and per SIM, and never mix two people's. */
@RunWith(RobolectricTestRunner::class)
class NumberAdviceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: NumberAdviceStore

    @Before fun setUp() {
        store = NumberAdviceStore(context)
        store.clear()
    }

    @Test fun dead_dismissal_is_kept_and_undone() {
        assertNull(store.deadDismissedAt("mac:a"))
        store.dismissDead("mac:a", at = 42L)
        assertEquals(42L, store.deadDismissedAt("mac:a"))
        assertNull(store.deadDismissedAt("mac:b"))
        store.undismissDead("mac:a")
        assertNull(store.deadDismissedAt("mac:a"))
    }

    @Test fun sim_answers_are_per_person_and_per_sim() {
        store.answerSim(listOf("mac:ana1", "mac:ana2"), "sim2")
        assertEquals(setOf("sim2"), store.simAnswered(listOf("mac:ana2")))
        assertTrue(store.simAnswered(listOf("mac:sam")).isEmpty())
        // A SIM id with the separator in it reads back whole.
        store.markSimOfferedAfterCall(listOf("mac:ana1"), "acc|7")
        assertEquals(setOf("acc|7"), store.simOfferedAfterCall(listOf("mac:ana1")))
        assertTrue(store.simAnswered(listOf("mac:ana1")).contains("sim2"))
    }
}
