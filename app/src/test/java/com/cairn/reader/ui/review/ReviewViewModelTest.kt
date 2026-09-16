package com.cairn.reader.ui.review

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cairn.reader.data.db.CairnDatabase
import com.cairn.reader.data.db.HighlightEntity
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.repo.HighlightRepository
import com.cairn.reader.domain.review.Grade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end wiring of the Review pane over a real (in-memory) database: a freshly-created highlight
 * enrolls in the schedule as "due now" (null srDueAt), a session loads it, and grading advances the
 * queue and empties it. Guards the review surface against the "nothing to do / nothing responds"
 * regression where the queue never populates or grading fails to move on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReviewViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `start loads freshly-created highlights and grading advances then empties the queue`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CairnDatabase::class.java,
        ).allowMainThreadQueries().build()
        val repo = HighlightRepository(db.highlightDao(), db.syncDao())
        val now = 1_000_000L

        db.itemDao().upsertItem(ItemEntity(id = "i1", url = "https://x.com/a", title = "Article", savedAt = now, siteName = "X"))
        // Enroll two highlights the same way the reader does — no explicit due date is set.
        repo.add(itemId = "i1", blockIndex = 0, start = 0, end = 30, quote = "The quick brown fox jumps high", color = 1)
        repo.add(itemId = "i1", blockIndex = 1, start = 0, end = 30, quote = "Another memorable sentence to recall", color = 2)

        val vm = ReviewViewModel(repo)

        vm.start()
        val loaded = withTimeout(5_000) { vm.state.filter { !it.loading }.first() }
        assertNotNull("a new highlight must surface as due", loaded.face)
        assertEquals("both highlights are due", 2, loaded.remaining)
        assertEquals(0, loaded.reviewed)

        vm.reveal()
        assertTrue(vm.state.value.revealed)

        vm.grade(Grade.GOOD)
        val g1 = withTimeout(5_000) { vm.state.filter { it.reviewed == 1 }.first() }
        assertNotNull("advances to the next card", g1.face)
        assertEquals(1, g1.remaining)
        assertFalse("the next card starts hidden again", g1.revealed)

        vm.grade(Grade.GOOD)
        val g2 = withTimeout(5_000) { vm.state.filter { it.reviewed == 2 }.first() }
        assertNull("queue is empty after the last card", g2.face)
        assertEquals(0, g2.remaining)

        db.close()
    }

    @Test fun `Again requeues the card so it comes back this session`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CairnDatabase::class.java,
        ).allowMainThreadQueries().build()
        val repo = HighlightRepository(db.highlightDao(), db.syncDao())
        val now = 1_000_000L
        db.itemDao().upsertItem(ItemEntity(id = "i1", url = "https://x.com/a", title = "Article", savedAt = now, siteName = "X"))
        repo.add(itemId = "i1", blockIndex = 0, start = 0, end = 30, quote = "The quick brown fox jumps high", color = 1)

        val vm = ReviewViewModel(repo)
        vm.start()
        withTimeout(5_000) { vm.state.filter { !it.loading }.first() }

        // A single card graded "Again" is put back at the end of the session, so there is still a
        // card to show rather than an immediate empty state.
        vm.grade(Grade.AGAIN)
        val after = withTimeout(5_000) { vm.state.filter { it.reviewed == 1 }.first() }
        assertNotNull("lapsed card returns within the session", after.face)
        assertEquals(1, after.remaining)

        db.close()
    }
}
