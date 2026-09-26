package com.wkhan.hexis

import androidx.lifecycle.ViewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.ui.AppViewModel
import com.wkhan.hexis.ui.AppViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A2 (Phase 3) — pins the composition root. [AppViewModelFactory] is the one production path that constructs
 * [AppViewModel]; these tests prove it (a) builds an AppViewModel through the VM's single `(app, repo)`
 * constructor, wiring the injected repository, and (b) rejects any other ViewModel class instead of silently
 * mis-casting. The factory's `repo` override lets the whole thing run against an isolated in-memory database,
 * so no real DB or KeyStore is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppViewModelFactoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
    }

    @After fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** The production path: the factory constructs a working AppViewModel from the injected collaborators. */
    @Test fun `builds AppViewModel through the injected repo`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        // Request via the generic base type so the returned runtime class is the real assertion, not a
        // statically-guaranteed tautology.
        val vm: ViewModel = AppViewModelFactory(app, repo).create(AppViewModel::class.java)
        assertTrue("factory must return an AppViewModel", vm is AppViewModel)
    }

    /** The guard: asking for any other ViewModel is a programming error, not a bad cast at some later NPE. */
    @Test fun `rejects a foreign ViewModel class`() {
        val app = ApplicationProvider.getApplicationContext<App>()
        assertThrows(IllegalArgumentException::class.java) {
            AppViewModelFactory(app, repo).create(ForeignViewModel::class.java)
        }
    }

    private class ForeignViewModel : ViewModel()
}
