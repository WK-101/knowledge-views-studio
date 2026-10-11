package app.parley.data.recall

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.data.DataContainer
import app.parley.data.db.ContactMetaEntity
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Recall keeps what it read while nothing it was read from changed, and reads again when something did. */
@RunWith(RobolectricTestRunner::class)
class RecallKeptTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    @Test fun a_new_search_reuses_the_corpus_until_a_note_changes() = runBlocking {
        val recall = RecallSources(c)
        val access = RecallSources.Access(privateShown = false)
        val first = recall.load(access)
        assertSame("nothing changed: nothing read again", first, recall.load(access))
        // Another privacy rule is another corpus.
        assertNotSame(first, recall.load(RecallSources.Access(privateShown = true)))

        c.meta.setMeta(ContactMetaEntity("lk-ana", pinnedNote = "Owes me the ladder", contactId = 1))
        val again = withTimeoutOrNull(5_000) {
            var s = recall.load(access)
            while (s.notes.none { it.text == "Owes me the ladder" }) {
                delay(20)
                s = recall.load(access)
            }
            s
        }
        assertEquals("Owes me the ladder", again?.notes?.single()?.text)
    }
}
