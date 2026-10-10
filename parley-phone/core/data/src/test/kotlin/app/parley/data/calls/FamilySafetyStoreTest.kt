package app.parley.data.calls

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.ExpectedSource
import app.parley.common.calls.ExpectedWindow
import app.parley.common.calls.Helper
import app.parley.common.calls.SafeWord
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Family safety's sealed document: safe words are never readable at rest or in the summary, follow their labels, come
 * back after a restart; expected-call windows count only with the user's yes; a document that can't be opened is never
 * taken for an empty one.
 */
@RunWith(RobolectricTestRunner::class)
class FamilySafetyStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("family_safety", Context.MODE_PRIVATE)
    private val word = SafeWord("Name of our first dog?", "Biscuit")

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        VaultCrypto.appContext = app
        prefs.edit().clear().commit()
    }

    @Test fun a_safe_word_is_sealed_at_rest_and_only_its_label_shows() = runBlocking {
        val store = FamilySafetyStore(app)
        assertTrue(store.setSafeWord(" Family ", word))
        val stored = prefs.all.values.joinToString()
        assertFalse("Biscuit" in stored)
        assertFalse("first dog" in stored)
        assertEquals(setOf("Family"), store.summary.value.safeWordLabels)
        assertFalse(store.summary.value.toString().contains("Biscuit"))
        // A new process reads it back.
        assertEquals(word, FamilySafetyStore(app).safeWord("Family"))
    }

    @Test fun safe_words_follow_renamed_and_deleted_labels_and_come_back_on_undo() = runBlocking {
        val store = FamilySafetyStore(app)
        store.setSafeWord("Family", word)
        store.labelsRenamed(mapOf("Family" to "Home"))
        assertNull(store.safeWord("Family"))
        assertEquals(word, store.safeWord("Home"))
        val kept = store.storedSafeWords(setOf("Home"))
        store.labelsDeleted(setOf("Home"))
        assertTrue(store.summary.value.safeWordLabels.isEmpty())
        store.restoreSafeWords(kept)
        assertEquals(word, store.safeWord("Home"))
    }

    @Test fun expected_calls_count_only_with_a_yes_and_go_with_a_no() = runBlocking {
        val now = 1_760_000_000_000L
        val store = FamilySafetyStore(app)
        assertNull("not read yet: the call path doesn't guess", store.windowsNow(now))
        val w = ExpectedWindow(now - 1_000, now + 3_600_000, ExpectedSource.TO_CALL, "k1", "Dentist", "+442079460000")
        assertTrue(store.putWindow(w, now))
        assertTrue(store.windows(now).isEmpty())
        store.setConsent(ExpectedSource.TO_CALL, true)
        assertEquals(listOf(w), store.windows(now))
        assertEquals(listOf(w), store.windowsNow(now))
        store.setConsent(ExpectedSource.TO_CALL, false)
        assertTrue(store.summary.value.windows.isEmpty())
    }

    @Test fun a_document_that_cant_be_opened_is_kept_and_changes_are_refused() = runBlocking {
        prefs.edit().putString("state_v1", "bm90IHNlYWxlZA==").commit()
        val store = FamilySafetyStore(app)
        assertFalse(store.load())
        assertFalse(store.setHelpers(listOf(Helper("Mum", "+442079460001"))))
        assertEquals("bm90IHNlYWxlZA==", prefs.getString("state_v1", null))
    }
}
