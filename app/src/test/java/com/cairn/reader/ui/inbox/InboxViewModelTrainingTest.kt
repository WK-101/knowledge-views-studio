package com.cairn.reader.ui.inbox

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.cairn.reader.audio.AudioPlayer
import com.cairn.reader.audio.TtsReader
import com.cairn.reader.data.db.CairnDatabase
import com.cairn.reader.data.db.ItemEntity
import com.cairn.reader.data.prefs.PreferencesRepository
import com.cairn.reader.data.repo.FeedRepository
import com.cairn.reader.data.repo.ItemRepository
import com.cairn.reader.data.repo.SourceRepository
import com.cairn.reader.data.repo.TrainingRepository
import com.cairn.reader.domain.training.TrainerKind
import io.mockk.coEvery
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the on-device training filter (NewsBlur-style Focus / Hidden tiers) at the ViewModel
 * layer, over a real in-memory database: a muted author drops out of the inbox for everyone, a liked
 * author survives, an untrained author is neutral, and turning Focus on narrows the list to liked
 * stories only. Also proves the write path — [InboxViewModel.train] persists a trainer and the list
 * re-filters live — so the whole reactive pipeline (DAO → TrainingScorer → UI state) is exercised,
 * not just the pure scorer (which [com.cairn.reader.domain.training.TrainingScorerTest] covers).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InboxViewModelTrainingTest {

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun db() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), CairnDatabase::class.java,
    ).allowMainThreadQueries().build()

    /** InboxViewModel with real data repos over [db] and relaxed mocks for the Android-bound
     *  collaborators the training pipeline never touches. seedDefaultFeeds is stubbed to a no-op so
     *  construction doesn't try to sync. */
    private fun viewModel(db: CairnDatabase): InboxViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val itemRepo = ItemRepository(db.itemDao(), db.sourceDao(), db.syncDao(), mockk(relaxed = true))
        val trainingRepo = TrainingRepository(db.trainerDao())
        val prefs = PreferencesRepository(ctx)
        val feedRepo = mockk<FeedRepository>(relaxed = true)
        coEvery { feedRepo.seedDefaultFeedsIfEmpty() } returns false
        val sourceRepo = mockk<SourceRepository>(relaxed = true)
        val tts = mockk<TtsReader>(relaxed = true)
        val audio = mockk<AudioPlayer>(relaxed = true)
        return InboxViewModel(itemRepo, feedRepo, sourceRepo, prefs, tts, audio, trainingRepo)
    }

    private fun article(id: String, author: String, title: String, savedAt: Long) =
        ItemEntity(id = id, url = "https://x.com/$id", title = title, author = author, savedAt = savedAt, effectiveDate = savedAt)

    @Test fun `muted author is hidden, liked survives, focus narrows to liked only`() = runBlocking {
        val db = db()
        db.itemDao().upsertItem(article("a", "Jane Doe", "Alpha", 300))   // will be liked
        db.itemDao().upsertItem(article("b", "Spammer", "Beta", 200))     // will be muted
        db.itemDao().upsertItem(article("c", "Casey", "Gamma", 100))      // untrained → neutral

        val training = TrainingRepository(db.trainerDao())
        training.toggle(TrainerKind.AUTHOR, "jane doe", 1)
        training.toggle(TrainerKind.AUTHOR, "spammer", -1)

        val vm = viewModel(db)

        // Default (Focus off): muted 'b' drops out; liked 'a' and neutral 'c' remain.
        val shown = withTimeout(5_000) { vm.state.filter { !it.loading && it.items.size == 2 }.first() }
        val ids = shown.items.map { it.id }.toSet()
        assertEquals(setOf("a", "c"), ids)
        assertFalse("a muted author must not appear", ids.contains("b"))

        // Focus on: only liked (score > 0) survives.
        vm.setFocusOnly(true)
        val focused = withTimeout(5_000) { vm.state.filter { !it.loading && it.items.size == 1 }.first() }
        assertEquals(listOf("a"), focused.items.map { it.id })

        db.close()
    }

    @Test fun `train() persists a trainer and the inbox re-filters live`() = runBlocking {
        val db = db()
        db.itemDao().upsertItem(article("a", "Jane Doe", "Alpha", 300))
        db.itemDao().upsertItem(article("b", "Spammer", "Beta", 200))
        db.itemDao().upsertItem(article("c", "Casey", "Gamma", 100))

        val vm = viewModel(db)

        // With nothing trained, all three are visible (the filter is a no-op).
        val before = withTimeout(5_000) { vm.state.filter { !it.loading && it.items.size == 3 }.first() }
        assertEquals(3, before.items.size)
        assertFalse(vm.hasTraining.value)

        // Muting an author through the ViewModel write path drops that story live.
        vm.train(TrainerKind.AUTHOR, "spammer", -1)
        val after = withTimeout(5_000) { vm.state.filter { !it.loading && it.items.size == 2 }.first() }
        assertEquals(setOf("a", "c"), after.items.map { it.id }.toSet())
        assertTrue("training now exists", vm.hasTraining.filter { it }.first())

        db.close()
    }
}
